package com.kubekubedashdash.ui.yamledit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KubeDashTheme
import com.kubekubedashdash.ui.KeepResizableOnMac
import com.kubekubedashdash.ui.TitleBar
import com.kubekubedashdash.yamledit.session.ApplyYamlSession
import com.kubekubedashdash.yamledit.session.EditorWindowModel
import com.kubekubedashdash.yamledit.session.YamlEditRegistry
import com.kubekubedashdash.yamledit.session.YamlEditSession

/**
 * One window per open editor of [registry], keyed by the editor's id, with the same undecorated
 * chrome as the workspace windows (the app's [TitleBar], minus the workspace-only controls),
 * so an editor keeps its window while others open and close. Rendered once, beside the workspace
 * windows, inside `application { }`: the editors belong to the process, not to a workspace.
 *
 * A window is gone when its model leaves the registry (applied, closed, its tab closed); the title
 * bar's close button only asks ([EditorWindowModel.requestClose]), which may raise the
 * "Discard your changes?" prompt first.
 */
@Composable
fun YamlEditorWindows(registry: YamlEditRegistry = YamlEditRegistry.Default, icon: Painter) {
    val models by registry.windows.collectAsState()
    models.forEach { model ->
        key(model.id) { EditorWindow(model, icon) }
    }
}

@Composable
private fun EditorWindow(model: EditorWindowModel, icon: Painter) {
    val windowState = rememberWindowState(size = DpSize(980.dp, 760.dp))
    Window(
        onCloseRequest = model::requestClose,
        title = model.title,
        state = windowState,
        icon = icon,
        undecorated = true,
    ) {
        KeepResizableOnMac(window)
        // A repeated Edit click (or a guard's "Keep editing") bumps the counter: bring this window forward.
        val focus by model.focusRequests.collectAsState()
        LaunchedEffect(focus) {
            if (focus > 0) {
                // A minimized window ignores toFront: de-iconify it first, like WorkspaceManager.raiseWindow.
                if ((window.extendedState and java.awt.Frame.ICONIFIED) != 0) {
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
                window.toFront()
                window.requestFocus()
            }
        }
        KubeDashTheme {
            Box(
                modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !(event.isMetaPressed || event.isCtrlPressed)) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.W -> {
                            model.requestClose()
                            true
                        }

                        Key.S -> {
                            when (model) {
                                is YamlEditSession -> model.review()
                                is ApplyYamlSession -> model.review()
                            }
                            true
                        }

                        else -> false
                    }
                },
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // The workspace windows' own chrome, so the editor looks and moves like them. The
                    // red light asks first when there are unsaved edits, like the close shortcut.
                    TitleBar(
                        title = model.title,
                        windowState = windowState,
                        onClose = model::requestClose,
                        sidebarCollapsed = false,
                        onToggleSidebar = {},
                        onOpenSettings = {},
                        windowTools = false,
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (model) {
                            is YamlEditSession -> {
                                // The registry's buffer factory makes an RstaBuffer on the EDT; nothing else creates sessions.
                                val buffer = model.buffer as? RstaBuffer
                                if (buffer != null) EditWindowContent(model, buffer) else UnavailableNote("This editor has no text area.")
                            }

                            is ApplyYamlSession -> {
                                val buffer = model.buffer as? RstaBuffer
                                if (buffer != null) ApplyWindowContent(model, buffer) else UnavailableNote("This editor has no text area.")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnavailableNote(text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(text, color = KdTextSecondary, modifier = Modifier.padding(24.dp))
    }
}
