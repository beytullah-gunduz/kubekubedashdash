package com.kubekubedashdash

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.App
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.SystemDirectories
import org.slf4j.LoggerFactory

fun main() {
    System.setProperty("LOG_DIR", SystemDirectories.logsDirectory)

    // Must run before any code that may spawn a subprocess inheriting the JVM env — most
    // critically fabric8's KubeConfigUtils, which shells out to `aws`/`gcloud`/`kubelogin`
    // for kubeconfig exec credential plugins. .app bundles launched from Finder inherit a
    // minimal PATH; without this, those plugins fail and every API call returns 401.
    ShellEnvironment.installIntoJvmEnv()

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

    application {
        val workspaces by WorkspaceManager.workspaces.collectAsState()
        val appIcon = remember { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }

        // Decision 2: closing the last window quits the app. WorkspaceManager
        // removes a workspace when its last session closes (or when the OS
        // window-close fires below); when the list is empty, no Window blocks
        // are emitted, but Compose's `application` block doesn't exit on its
        // own — so we trigger exitApplication() explicitly here.
        // Guard: only exit if workspaces were non-empty at least once, so the app
        // doesn't quit immediately at startup before WorkspaceManager finishes init.
        var hasEverBeenNonEmpty by remember { mutableStateOf(false) }
        LaunchedEffect(workspaces) {
            if (workspaces.isNotEmpty()) hasEverBeenNonEmpty = true
            if (hasEverBeenNonEmpty && workspaces.isEmpty()) exitApplication()
        }

        workspaces.forEach { workspace ->
            key(workspace.id) {
                val windowState = rememberWindowState(
                    size = DpSize(1440.dp, 960.dp),
                    position = workspace.initialPosition ?: WindowPosition.PlatformDefault,
                )
                Window(
                    onCloseRequest = { WorkspaceManager.closeWorkspace(workspace.id) },
                    title = "KubeKubeDashDash",
                    state = windowState,
                    icon = appIcon,
                    undecorated = true,
                ) {
                    App(
                        workspace = workspace,
                        windowScope = this,
                        windowState = windowState,
                        onClose = { WorkspaceManager.closeWorkspace(workspace.id) },
                    )
                }
            }
        }
    }
}
