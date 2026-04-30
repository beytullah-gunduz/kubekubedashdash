package com.kubekubedashdash

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.ui.App
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.SystemDirectories
import org.slf4j.LoggerFactory

fun main() {
    System.setProperty("LOG_DIR", SystemDirectories.logsDirectory)

    // Route every uncaught exception/error (including LinkageError and
    // NoClassDefFoundError from any thread — Netty event-loops, Vert.x
    // workers, the AWT EDT, coroutine workers) into logback. Without this
    // they go to System.err, which is /dev/null when the .app bundle is
    // launched from Finder, and the app silently hangs instead of telling
    // us what blew up.
    val crashLogger = LoggerFactory.getLogger("UncaughtException")
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        crashLogger.error("Uncaught exception in thread {}", thread.name, throwable)
    }

    ShellEnvironment.inheritShellPath()

    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val appIcon = BitmapPainter(useResource("icon.png", ::loadImageBitmap))

        Window(
            onCloseRequest = ::exitApplication,
            title = "KubeKubeDashDash",
            state = windowState,
            icon = appIcon,
            undecorated = true,
        ) {
            App(
                windowScope = this,
                windowState = windowState,
                onClose = ::exitApplication,
            )
        }
    }
}
