package com.kubekubedashdash.util

import org.slf4j.LoggerFactory
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * macOS GUI apps launched from Finder/Dock inherit a minimal PATH (/usr/bin:/bin:/usr/sbin:/sbin)
 * that doesn't include user-installed tools like `aws`, `gcloud`, etc. This object resolves a
 * usable PATH from the user's shell profile + well-known install locations and applies it
 * explicitly to every subprocess we spawn, so we don't depend on the JVM's own env map
 * propagating correctly to child processes.
 *
 * Windows starts a GUI app with the user's full PATH, so there the PATH is only searched —
 * with PATHEXT, since `aws` is `aws.exe` and `gcloud` is `gcloud.cmd` — and never rewritten.
 */
object ShellEnvironment {
    private val log = LoggerFactory.getLogger(ShellEnvironment::class.java)
    private val sep: String = File.pathSeparator
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    @Volatile
    private var resolvedPath: String? = null
    private val commandCache = ConcurrentHashMap<String, Optional<String>>()

    val augmentedPath: String
        get() {
            val cached = resolvedPath
            if (cached != null) return cached
            return synchronized(this) {
                resolvedPath ?: resolveAugmentedPath().also { resolvedPath = it }
            }
        }

    fun applyTo(pb: ProcessBuilder) {
        // Windows: the inherited PATH is already complete; see installIntoJvmEnv.
        if (isWindows) return
        pb.environment()["PATH"] = augmentedPath
    }

    /**
     * Inject the augmented PATH into the JVM's own process-environment map so any subprocess
     * spawned via `ProcessBuilder` — including those inside third-party libs we can't reach
     * (notably fabric8's `KubeConfigUtils`, which shells out to `aws` / `gcloud` / `kubelogin`
     * for kubeconfig `exec` credential plugins) — inherits a usable PATH.
     *
     * Without this, .app bundles launched from Finder/Dock get the minimal GUI PATH
     * (`/usr/bin:/bin:/usr/sbin:/sbin`), the exec plugin returns "command not found", fabric8
     * fails to obtain a bearer token, and every API call comes back 401 Unauthorized — even
     * though the same kubeconfig works fine when the JVM is launched from a terminal.
     *
     * Requires `--add-opens java.base/java.lang=ALL-UNNAMED` on JDK 17+ (set in
     * composeApp/build.gradle.kts).
     *
     * Skipped on Windows: Explorer hands a GUI app the user's full PATH, so there is nothing to
     * fix. Injecting there used to replace the whole PATH with the Unix fallback directories
     * (the Windows entries never passed the absolute-path filter), and the JVM's env map is
     * case-sensitive, so writing `PATH` also left a second variable beside the inherited `Path`
     * in every child's environment — fabric8 launches exec plugins as `cmd.exe /c aws …`.
     */
    fun installIntoJvmEnv() {
        if (isWindows) {
            log.info("Windows: keeping the inherited PATH")
            return
        }
        try {
            val path = augmentedPath
            val peClass = Class.forName("java.lang.ProcessEnvironment")
            val envField = peClass.getDeclaredField("theEnvironment").apply { isAccessible = true }
            val env = envField.get(null)

            // Unix/macOS: theEnvironment is Map<Variable, Value> with byte-array-backed wrapper types.
            val varClass = Class.forName("java.lang.ProcessEnvironment\$Variable")
            val valClass = Class.forName("java.lang.ProcessEnvironment\$Value")
            val varOf = varClass.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }
            val valOf = valClass.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (env as MutableMap<Any, Any>)[varOf.invoke(null, "PATH")] = valOf.invoke(null, path)
            log.info("Injected augmented PATH into JVM process env ({} chars)", path.length) // log-safe: the length only
        } catch (t: Throwable) {
            log.warn(
                "Could not inject PATH into JVM process env: {}. Exec credential plugins (aws/gcloud/kubelogin) may fail in packaged .app bundles.",
                describeFailureShape(t),
            )
            log.debug("Could not inject PATH into JVM process env", t)
        }
    }

    /**
     * Drop cached command-resolution results so a tool installed after launch
     * is re-discovered on the next [resolveCommand] — without this the "install
     * the CLI, then try again" flow never works in-session because a negative
     * ("not found") result is cached for the JVM's lifetime. The resolved PATH
     * is left intact: standard installers (brew, system dirs) land in
     * directories already on it.
     */
    fun invalidate() {
        commandCache.clear()
    }

    /**
     * The CLIs that act on the developer's real cloud accounts. With [DISABLE_CLOUD_CLIS_PROPERTY]
     * set to "true" — every hot run sets it (build.gradle.kts) — they resolve as missing, so
     * neither EKS/GKE discovery nor anything else can start them, whatever an agent or the UI
     * smoke clicks. Exec credential plugins named in a kubeconfig bypass this; hot runs use the
     * empty test kubeconfig.
     */
    private val cloudClis = setOf("aws", "gcloud", "gke-gcloud-auth-plugin", "az", "kubelogin")

    const val DISABLE_CLOUD_CLIS_PROPERTY = "kkdd.disableCloudClis"

    fun resolveCommand(command: String): String? {
        // Checked on every call and never cached, so the switch can't leave a stale answer behind.
        if (command in cloudClis && System.getProperty(DISABLE_CLOUD_CLIS_PROPERTY) == "true") {
            log.debug("Command '{}' treated as missing: cloud CLIs are disabled ({})", command, DISABLE_CLOUD_CLIS_PROPERTY)
            return null
        }
        val cached = commandCache[command]
        if (cached != null) return cached.orElse(null)
        val found = scanForExecutable(command)
        commandCache[command] = Optional.ofNullable(found)
        if (found != null) {
            // INFO, so app.log (INFO and up) shows which CLI a support report found; the path stays at
            // DEBUG, as app.log carries no paths. Cached, so once per command until the next invalidate().
            log.info("Resolved '{}'", command)
            log.debug("Resolved '{}' -> {}", command, displayPath(found))
        } else {
            log.debug("Command '{}' not found on augmented PATH", command)
        }
        return found
    }

    private fun scanForExecutable(command: String): String? = findExecutable(command, augmentedPath.split(sep), isWindows, System.getenv("PATHEXT"))

    // The PATHEXT extensions CreateProcess can start (.bat/.cmd through cmd.exe), in the default
    // PATHEXT order. PATHEXT also lists script types (.vbs, .js, …) that ProcessBuilder can't run.
    private val windowsLaunchableExtensions = listOf(".com", ".exe", ".bat", ".cmd")

    /**
     * The first of [dirs] that holds [command], as an absolute path. On Windows a name without
     * an extension is tried with each launchable PATHEXT extension, in PATHEXT order within each
     * directory as cmd.exe does, and never bare: the Google Cloud SDK ships an extensionless
     * `gcloud` shell script beside `gcloud.cmd`, and Windows can't start it.
     */
    internal fun findExecutable(command: String, dirs: List<String>, windows: Boolean, pathExt: String?): String? {
        val names = if (windows) windowsCandidateNames(command, pathExt) else listOf(command)
        for (dir in dirs) {
            if (dir.isBlank()) continue
            for (name in names) {
                val candidate = File(dir, name)
                // Windows has no execute bit; there canExecute() only says the file exists.
                if (candidate.isFile && (windows || candidate.canExecute())) {
                    return candidate.absolutePath
                }
            }
        }
        return null
    }

    private fun windowsCandidateNames(command: String, pathExt: String?): List<String> {
        if (windowsLaunchableExtensions.any { command.endsWith(it, ignoreCase = true) }) return listOf(command)
        val extensions = pathExt.orEmpty().split(';')
            .map { it.trim().lowercase() }
            .filter { it in windowsLaunchableExtensions }
            .distinct()
            .ifEmpty { windowsLaunchableExtensions }
        return extensions.map { command + it }
    }

    /**
     * Windows: the inherited PATH, then the default install directories of the AWS CLI and the
     * Google Cloud SDK, so a CLI installed after launch — whose installer updated the PATH this
     * process no longer reads — is found on the next check.
     */
    internal fun windowsSearchDirs(env: (String) -> String?): List<String> {
        val seen = LinkedHashSet<String>()
        seen += pathEntries(env("PATH"), windows = true)
        env("ProgramFiles")?.let { seen += pathEntries("$it\\Amazon\\AWSCLIV2", windows = true) }
        env("LOCALAPPDATA")?.let { seen += pathEntries("$it\\Google\\Cloud SDK\\google-cloud-sdk\\bin", windows = true) }
        return seen.toList()
    }

    private fun resolveAugmentedPath(): String {
        if (isWindows) {
            val dirs = windowsSearchDirs(System::getenv)
            log.info("Resolved augmented PATH: {} entries", dirs.size)
            log.debug("Augmented PATH: {}", dirs.joinToString(sep) { displayPath(it) })
            return dirs.joinToString(sep)
        }

        val seen = LinkedHashSet<String>()
        addPathDirs(seen, System.getenv("PATH"))

        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (osName.contains("mac")) {
            addPathDirs(seen, runPathHelper())
        }
        addPathDirs(seen, runShellPathQuery(login = true))
        addPathDirs(seen, runShellPathQuery(login = false))

        addPathDirs(seen, "/opt/homebrew/bin")
        addPathDirs(seen, "/opt/homebrew/sbin")
        addPathDirs(seen, "/usr/local/bin")
        addPathDirs(seen, "/usr/local/sbin")
        addPathDirs(seen, "/usr/local/aws-cli")
        addPathDirs(seen, "/opt/local/bin")

        // Google Cloud SDK keeps its own bin directory, and `gcloud components install`
        // drops new binaries (notably gke-gcloud-auth-plugin) there WITHOUT linking them
        // into a directory that is normally on PATH — Homebrew only symlinks the formula's
        // declared binaries (gcloud/gsutil/bq) at install time. Without these entries the
        // plugin looks "not installed" even though it is present and working; gcloud itself
        // works around the same problem by writing the absolute SDK path into the kubeconfig
        // exec block (api_lib/container/kubeconfig.py, "Provide SDK Full path if command is
        // not in PATH … Eg: brew").
        addPathDirs(seen, "/opt/homebrew/share/google-cloud-sdk/bin")
        addPathDirs(seen, "/usr/local/share/google-cloud-sdk/bin")
        addPathDirs(seen, "/opt/google-cloud-sdk/bin")
        addPathDirs(seen, "/usr/lib/google-cloud-sdk/bin")

        val home = System.getProperty("user.home").orEmpty()
        if (home.isNotBlank()) {
            addPathDirs(seen, "$home/.local/bin")
            addPathDirs(seen, "$home/bin")
            addPathDirs(seen, "$home/google-cloud-sdk/bin")
        }

        val joined = seen.joinToString(sep)
        log.info("Resolved augmented PATH: {} entries", seen.size)
        log.debug("Augmented PATH: {}", seen.joinToString(sep) { displayPath(it) })
        return joined
    }

    private fun addPathDirs(seen: MutableSet<String>, raw: String?) {
        seen += pathEntries(raw, windows = false)
    }

    // C:\dir, C:/dir or a UNC share (\\server\share) — not C:dir (relative to that drive's
    // current directory) nor \dir (relative to the current drive).
    private val windowsAbsolutePath = Regex("""^(?:[A-Za-z]:[\\/]|\\\\[^\\]).*""")

    /**
     * The absolute directories in a PATH value. Relative entries (".", "bin") are dropped so
     * the working directory can never shadow a CLI. Windows entries may be quoted.
     */
    internal fun pathEntries(raw: String?, windows: Boolean): List<String> {
        if (raw == null) return emptyList()
        return raw.split(if (windows) ';' else ':')
            .map { entry -> entry.trim().let { if (windows) it.removeSurrounding("\"") else it } }
            .filter { if (windows) windowsAbsolutePath.matches(it) else it.startsWith("/") }
    }

    private fun runPathHelper(): String? {
        try {
            val pb = ProcessBuilder("/usr/libexec/path_helper", "-s").redirectErrorStream(false)
            val p = pb.start()
            val finished = p.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) return null
            val text = p.inputStream.bufferedReader().readText()
            val marker = "PATH="
            val idx = text.indexOf(marker)
            if (idx < 0) return null
            val after = text.substring(idx + marker.length)
            val end = after.indexOf(';').let { if (it >= 0) it else after.length }
            return after.substring(0, end).trim().trim('"')
        } catch (e: Exception) {
            log.debug("path_helper failed: {}", e.message)
            return null
        }
    }

    private val ALLOWED_SHELLS = setOf(
        "/bin/bash", "/bin/zsh", "/bin/sh",
        "/usr/bin/bash", "/usr/bin/zsh", "/usr/bin/sh",
        "/usr/local/bin/bash", "/usr/local/bin/zsh",
        "/opt/homebrew/bin/bash", "/opt/homebrew/bin/zsh",
    )

    private const val SENTINEL_BEGIN = "::KKDD_PATH_BEGIN::"
    private const val SENTINEL_END = "::KKDD_PATH_END::"

    private fun runShellPathQuery(login: Boolean): String? {
        try {
            val candidate = System.getenv("SHELL")?.takeIf { it.isNotBlank() }
            val shell = if (candidate != null && candidate in ALLOWED_SHELLS) candidate else "/bin/sh"
            if (candidate != null && candidate != shell) {
                log.info("\$SHELL not in allowlist, falling back to /bin/sh")
            }
            val echoCmd = "echo \"$SENTINEL_BEGIN\$PATH$SENTINEL_END\""
            val args = if (login) listOf(shell, "-l", "-c", echoCmd) else listOf(shell, "-c", echoCmd)
            val pb = ProcessBuilder(args).redirectErrorStream(false)
            val p = pb.start()
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) return null
            val out = p.inputStream.bufferedReader().readText()
            val begin = out.indexOf(SENTINEL_BEGIN)
            val end = out.indexOf(SENTINEL_END, startIndex = begin + SENTINEL_BEGIN.length)
            return if (begin >= 0 && end > begin) out.substring(begin + SENTINEL_BEGIN.length, end) else null
        } catch (e: Exception) {
            log.debug("shell PATH query failed: {}", e.message)
            return null
        }
    }
}
