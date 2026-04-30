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

    private fun resolveDataDir(): String = when {
        isWindows -> {
            val appData = envOrNull("APPDATA") ?: "$home\\AppData\\Roaming"
            "$appData\\KubeKubeDashDash"
        }

        isMac -> "$home/Library/Application Support/KubeKubeDashDash"

        else -> {
            val xdg = envOrNull("XDG_DATA_HOME") ?: "$home/.local/share"
            "$xdg/kubekubedashdash"
        }
    }

    private fun resolveLogsDir(): String = when {
        isWindows -> {
            val localAppData = envOrNull("LOCALAPPDATA") ?: "$home\\AppData\\Local"
            "$localAppData\\KubeKubeDashDash\\Logs"
        }

        isMac -> "$home/Library/Logs/KubeKubeDashDash"

        else -> {
            val xdg = envOrNull("XDG_STATE_HOME") ?: "$home/.local/state"
            "$xdg/kubekubedashdash/logs"
        }
    }

    private fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun ensureDir(path: String) {
        runCatching { File(path).mkdirs() }
    }
}
