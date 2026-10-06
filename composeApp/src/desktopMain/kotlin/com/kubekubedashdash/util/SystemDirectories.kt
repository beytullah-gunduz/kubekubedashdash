package com.kubekubedashdash.util

import java.io.File

object SystemDirectories {
    private val osName: String = System.getProperty("os.name", "").lowercase()
    private val home: String = System.getProperty("user.home", "")

    private val isWindows: Boolean = isWindowsOs(osName)
    private val isMac: Boolean = isMacOs(osName)

    val dataDirectory: String by lazy {
        resolveDataDir().also { ensureDir(it) }
    }

    val logsDirectory: String by lazy {
        resolveLogsDir().also { ensureDir(it) }
    }

    /**
     * Test seam: the Gradle test task points this at a build directory so the
     * suite never opens the developer's real preferences store or session
     * file. Blank or unset in production. The logs directory follows it.
     */
    private const val DATA_DIR_OVERRIDE_PROPERTY = "kkdd.dataDir"

    private fun isWindowsOs(osName: String): Boolean = osName.lowercase().startsWith("windows")

    private fun isMacOs(osName: String): Boolean = osName.lowercase().let { it.contains("mac") || it.contains("darwin") }

    private fun resolveDataDir(): String = when {
        dataDirOverride() != null -> dataDirOverride()!!

        isWindows -> {
            val appData = envOrNull("APPDATA") ?: "$home\\AppData\\Roaming"
            validateUnderHome("$appData\\KubeKubeDashDash", "$home\\AppData\\Roaming\\KubeKubeDashDash")
        }

        isMac -> "$home/Library/Application Support/KubeKubeDashDash"

        else -> {
            val xdg = envOrNull("XDG_DATA_HOME") ?: "$home/.local/share"
            validateUnderHome("$xdg/kubekubedashdash", "$home/.local/share/kubekubedashdash")
        }
    }

    /**
     * With the data directory overridden, the logs go to its `logs` subdirectory, so a test,
     * screenshot or hot-run JVM never appends to the developer's real app.log. The parameters
     * let the tests pin each platform's path against a fake home; production passes none.
     */
    internal fun resolveLogsDir(
        dataDirProperty: String? = System.getProperty(DATA_DIR_OVERRIDE_PROPERTY),
        os: String = osName,
        userHome: String = home,
        env: (String) -> String? = ::envOrNull,
    ): String {
        dataDirOverride(dataDirProperty)?.let { return File(it, "logs").path }
        return when {
            isWindowsOs(os) -> {
                val localAppData = env("LOCALAPPDATA") ?: "$userHome\\AppData\\Local"
                validateUnderHome("$localAppData\\KubeKubeDashDash\\Logs", "$userHome\\AppData\\Local\\KubeKubeDashDash\\Logs", userHome)
            }

            isMacOs(os) -> "$userHome/Library/Logs/KubeKubeDashDash"

            else -> {
                val xdg = env("XDG_STATE_HOME") ?: "$userHome/.local/state"
                validateUnderHome("$xdg/kubekubedashdash/logs", "$userHome/.local/state/kubekubedashdash/logs", userHome)
            }
        }
    }

    private fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun dataDirOverride(property: String? = System.getProperty(DATA_DIR_OVERRIDE_PROPERTY)): String? = property?.takeIf { it.isNotBlank() }

    private fun validateUnderHome(candidate: String, fallback: String, userHome: String = home): String = try {
        val homePath = File(userHome).toPath().toRealPath()
        val candidatePath = File(candidate).toPath()
        val resolved = if (candidatePath.toFile().exists()) candidatePath.toRealPath() else candidatePath.normalize()
        if (resolved.startsWith(homePath)) candidate else fallback
    } catch (e: Exception) {
        fallback
    }

    private fun ensureDir(path: String) {
        runCatching {
            val file = File(path)
            file.mkdirs()
            if (!isWindows) {
                // Best-effort 0700 on POSIX so logs and DataStore aren't world-readable.
                // Only the leaf directory is restricted; intermediate dirs under $HOME are
                // already user-owned and typically 0755.
                runCatching {
                    java.nio.file.Files.setPosixFilePermissions(
                        file.toPath(),
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
                    )
                }
            }
        }
    }
}
