package com.kubekubedashdash

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.mcp.McpServerManager
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.services.portforward.PortForwardRegistry
import com.kubekubedashdash.ui.App
import com.kubekubedashdash.ui.yamledit.EditorWarmup
import com.kubekubedashdash.ui.yamledit.YamlEditorWindows
import com.kubekubedashdash.util.DEFAULT_WINDOW_SIZE
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.toGeometry
import com.kubekubedashdash.yamledit.session.QuitGuard
import com.kubekubedashdash.yamledit.session.YamlEditRegistry
import org.slf4j.LoggerFactory
import java.awt.Desktop
import javax.swing.SwingUtilities

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

    // Graceful shutdown (audit C2). exitApplication() (fired when the last
    // window closes) — and every other JVM exit path — otherwise just
    // abandons the embedded MCP Ktor server (accept loop + thread pool) and
    // the live pod-log watch HTTP connections to abrupt process death. A
    // shutdown hook stops them on EVERY exit route, in one place that can't
    // be bypassed. Both calls are idempotent and no-ops when nothing is
    // running, so registering unconditionally is safe.
    Runtime.getRuntime().addShutdownHook(
        Thread({
            val shutdownLog = LoggerFactory.getLogger("Shutdown")
            shutdownLog.info("Shutdown hook: stopping MCP server, log streams and port forwards")
            runCatching { McpServerManager.stop() }
                .onFailure { shutdownLog.warn("MCP server stop failed: {}", it.message) }
            runCatching { LogStreamRegistry.clearAll() }
                .onFailure { shutdownLog.warn("Log stream teardown failed: {}", it.message) }
            runCatching { PortForwardRegistry.stopAll() }
                .onFailure { shutdownLog.warn("Port forward teardown failed: {}", it.message) }
        }, "app-shutdown"),
    )

    // Compose 1.12 added selection auto-scroll: every move of a text-selection drag asks
    // each scrollable ancestor to bring the pointer into view. The cluster-tab pager obeys
    // that despite userScrollEnabled = false and rounds it up to a whole page, so dragging
    // a YAML selection left past the start of the text left the pager stuck most of the
    // way to the next tab (and the Events|YAML pager part-way to the previous one). Off
    // restores the pre-1.12 behaviour: a selection no longer scrolls its pane when dragged
    // past the edge. Upstream marks the flag temporary; when it is removed this line stops
    // compiling, so re-try that drag with two cluster tabs open before deleting it.
    ComposeFoundationFlags.isSelectionAutoScrollEnabled = false

    installQuitHandler()

    // The YAML editor's first window otherwise froze the app for seconds on macOS while AWT
    // registered every installed font on the EDT; do that now, in the background.
    EditorWarmup.start()

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
                    placement = if (workspace.initialMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                    size = workspace.initialSize ?: DEFAULT_WINDOW_SIZE,
                    position = workspace.initialPosition ?: WindowPosition.PlatformDefault,
                )
                // Mirror the live geometry into the workspace so session
                // persistence can save it. workspace.geometry.value is read as a
                // plain (untracked) previous value; snapshotFlow re-evaluates only
                // on WindowState changes, which is exactly what we want.
                LaunchedEffect(windowState) {
                    snapshotFlow { windowState.toGeometry(workspace.geometry.value) }
                        .collect { workspace.updateGeometry(it) }
                }
                Window(
                    onCloseRequest = { WorkspaceManager.requestCloseWorkspace(workspace.id) },
                    title = "KubeKubeDashDash",
                    state = windowState,
                    icon = appIcon,
                    undecorated = true,
                ) {
                    App(
                        workspace = workspace,
                        windowScope = this,
                        windowState = windowState,
                        onClose = { WorkspaceManager.requestCloseWorkspace(workspace.id) },
                    )
                }
            }
        }

        // The YAML editor windows (one per object being edited) live beside the workspace windows,
        // not inside one: they belong to the process-wide registry, not to a workspace.
        YamlEditorWindows(icon = appIcon)
    }
}

/**
 * Cmd+Q on macOS: asks about unsaved YAML edits before the app goes down. Without a handler the
 * OS quits the JVM at once and every open editor with it. [QuitGuard] performs the quit
 * itself when nothing is unsaved, and otherwise shows its question in a window
 * ([WorkspaceManager.showQuitPrompt]); either way the answer reaches the OS through the quit
 * response exactly once. `performQuit()` is the call macOS makes today with no handler, so the same JVM
 * shutdown hooks run (`app-shutdown` above, and `session-save`). Other platforms have no quit
 * handler: closing the last window goes through [WorkspaceManager.requestCloseWorkspace].
 *
 * Must run before `application { }` so the handler is in place before the first window opens.
 */
private fun installQuitHandler() {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) return
    val log = LoggerFactory.getLogger("QuitGuard")
    Desktop.getDesktop().setQuitHandler { _, response ->
        // The registry reads each editor's live text, which is EDT-only.
        SwingUtilities.invokeLater {
            var answered = false
            try {
                QuitGuard.onQuitRequested(
                    registry = YamlEditRegistry.Default,
                    show = WorkspaceManager::showQuitPrompt,
                    perform = {
                        answered = true
                        response.performQuit()
                    },
                    cancel = {
                        answered = true
                        response.cancelQuit()
                    },
                )
            } catch (t: Throwable) {
                // A guard that fails before it answered must not leave the OS waiting. Quitting
                // anyway could drop unsaved edits without a word, so the quit is cancelled: the
                // person can press Cmd+Q again or close the windows (each asks on its own).
                log.warn("Quit guard failed: {}", t::class.simpleName)
                if (!answered) response.cancelQuit()
            }
        }
    }
}
