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
 */
object ShellEnvironment {
    private val log = LoggerFactory.getLogger(ShellEnvironment::class.java)
    private val sep: String = File.pathSeparator

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
     */
    fun installIntoJvmEnv() {
        try {
            val path = augmentedPath
            val peClass = Class.forName("java.lang.ProcessEnvironment")
            val envField = peClass.getDeclaredField("theEnvironment").apply { isAccessible = true }
            val env = envField.get(null)

            // Windows: theEnvironment is Map<String, String> and there's a sibling
            // theCaseInsensitiveEnvironment that the launcher reads from. Unix/macOS:
            // theEnvironment is Map<Variable, Value> with byte-array-backed wrapper types.
            val ciField = runCatching { peClass.getDeclaredField("theCaseInsensitiveEnvironment") }.getOrNull()
            if (ciField != null) {
                @Suppress("UNCHECKED_CAST")
                (env as MutableMap<String, String>)["PATH"] = path
                ciField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (ciField.get(null) as MutableMap<String, String>)["PATH"] = path
            } else {
                val varClass = Class.forName("java.lang.ProcessEnvironment\$Variable")
                val valClass = Class.forName("java.lang.ProcessEnvironment\$Value")
                val varOf = varClass.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }
                val valOf = valClass.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                (env as MutableMap<Any, Any>)[varOf.invoke(null, "PATH")] = valOf.invoke(null, path)
            }
            log.info("Injected augmented PATH into JVM process env ({} chars)", path.length)
        } catch (t: Throwable) {
            log.warn(
                "Could not inject PATH into JVM process env: {}. Exec credential plugins (aws/gcloud/kubelogin) may fail in packaged .app bundles.",
                t.message,
            )
        }
    }

    fun resolveCommand(command: String): String? {
        val cached = commandCache[command]
        if (cached != null) return cached.orElse(null)
        val found = scanForExecutable(command)
        commandCache[command] = Optional.ofNullable(found)
        if (found != null) {
            log.debug("Resolved '{}' -> {}", command, found)
        } else {
            log.debug("Command '{}' not found on augmented PATH", command)
        }
        return found
    }

    private fun scanForExecutable(command: String): String? {
        for (dir in augmentedPath.split(sep)) {
            val candidate = File(dir.trim(), command)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }

    private fun resolveAugmentedPath(): String {
        val seen = LinkedHashSet<String>()
        addPathDirs(seen, System.getenv("PATH"))

        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (osName.contains("mac")) {
            addPathDirs(seen, runPathHelper())
        }
        if (!osName.contains("windows")) {
            addPathDirs(seen, runShellPathQuery(login = true))
            addPathDirs(seen, runShellPathQuery(login = false))
        }

        addPathDirs(seen, "/opt/homebrew/bin")
        addPathDirs(seen, "/opt/homebrew/sbin")
        addPathDirs(seen, "/usr/local/bin")
        addPathDirs(seen, "/usr/local/sbin")
        addPathDirs(seen, "/usr/local/aws-cli")
        addPathDirs(seen, "/opt/local/bin")
        val home = System.getProperty("user.home").orEmpty()
        if (home.isNotBlank()) {
            addPathDirs(seen, "$home/.local/bin")
            addPathDirs(seen, "$home/bin")
        }

        val joined = seen.joinToString(sep)
        log.info("Resolved augmented PATH: {} entries", seen.size)
        log.debug("Augmented PATH: {}", joined)
        return joined
    }

    private fun addPathDirs(seen: MutableSet<String>, raw: String?) {
        if (raw == null) return
        for (dir in raw.split(sep)) {
            val trimmed = dir.trim()
            if (trimmed.isNotBlank() && trimmed.startsWith("/")) {
                seen += trimmed
            }
        }
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
