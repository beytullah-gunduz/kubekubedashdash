package com.kubekubedashdash.ui.yamledit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.data.repository.PreferenceRepository
import kotlinx.coroutines.flow.first
import javax.swing.SwingUtilities

/**
 * The one place the Swing editor is put on screen: [buffer]'s scroll pane in a [SwingPanel], themed
 * from the active palette and the UI zoom.
 *
 * The panel is drawn above everything Compose draws, and dialogs, menus and toasts are Compose
 * layers, so a caller must not compose this while a dialog is open (the window content swaps it for
 * a placeholder). Composing it again re-attaches the same scroll pane: text, caret, selection and
 * undo history live in [buffer], not in the composition.
 *
 * The node carries the description "YAML editor" with the text-field semantics `SetText` and
 * `InsertTextAtCursor`: the accessibility entry point for the editor, and what the Hot Reload MCP's
 * `type_text` drives in smoke tests (Swing text is invisible to the semantics tree otherwise).
 */
@Composable
internal fun YamlEditorHost(buffer: RstaBuffer, modifier: Modifier = Modifier) {
    val uiScalePercent by PreferenceRepository.uiScalePercent.collectAsState()
    // Swing keeps no link to Compose state: re-theme on a palette or zoom change. The first theming
    // happens in the factory, so the first paint is already themed.
    LaunchedEffect(ThemeManager.paletteKey, uiScalePercent) { applyRstaTheme(buffer, uiScalePercent) }
    // Typing starts right away when the editor appears (and again after a dialog closes): once the
    // window is active and the panel is attached, once per appearance, so coming back to the window
    // later never takes the focus from the search field.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(buffer) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        withFrameNanos { }
        buffer.textArea.requestFocusInWindow()
    }
    SwingPanel(
        factory = {
            applyRstaTheme(buffer, uiScalePercent)
            buffer.scrollPane
        },
        modifier = modifier.semantics {
            contentDescription = "YAML editor"
            setText {
                onEventDispatchThread { buffer.replaceAll(it.text, resetHistory = false) }
                true
            }
            insertTextAtCursor {
                onEventDispatchThread { buffer.textArea.replaceSelection(it.text) }
                true
            }
        },
    )
}

/** Runs [block] now when already on the AWT event thread, else queues it there (Swing is single-threaded). */
private fun onEventDispatchThread(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
}
