package com.kubekubedashdash.util

import java.io.File

object SystemDirectories {
    private val osName: String = System.getProperty("os.name", "").lowercase()
    private val home: String = System.getProperty("user.home", "")

    private val isWindows: Boolean = osName.startsWith("windows")
    private val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    val dataDirectory: String by lazy {
        resolveDataDir().also { ensureDir(it) }
    }

    val logsDirectory: String by lazy {
        resolveLogsDir().also { ensureDir(it) }
    }

    /**
     * Test seam: the Gradle test task points this at a build directory so the
     * suite never opens the developer's real preferences store or session
     * file. Blank or unset in production.
     */
    private const val DATA_DIR_OVERRIDE_PROPERTY = "kkdd.dataDir"

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

    private fun resolveLogsDir(): String = when {
        isWindows -> {
            val localAppData = envOrNull("LOCALAPPDATA") ?: "$home\\AppData\\Local"
            validateUnderHome("$localAppData\\KubeKubeDashDash\\Logs", "$home\\AppData\\Local\\KubeKubeDashDash\\Logs")
        }

        isMac -> "$home/Library/Logs/KubeKubeDashDash"

        else -> {
            val xdg = envOrNull("XDG_STATE_HOME") ?: "$home/.local/state"
            validateUnderHome("$xdg/kubekubedashdash/logs", "$home/.local/state/kubekubedashdash/logs")
        }
    }

    private fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun dataDirOverride(): String? = System.getProperty(DATA_DIR_OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }

    private fun validateUnderHome(candidate: String, fallback: String): String = try {
        val homePath = File(home).toPath().toRealPath()
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
