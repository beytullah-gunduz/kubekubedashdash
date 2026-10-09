package com.kubekubedashdash.ui.yamledit

import androidx.compose.runtime.Composable
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.yamledit.session.DirtyEdit
import com.kubekubedashdash.yamledit.session.DiscardPrompt

/** The most editors the dialog names; any more are summed up so the dialog stays on screen. */
private const val MAX_LISTED_EDITS = 8

/**
 * The question "these editors have unsaved text, close them anyway?" for [prompt]: its verb names
 * what the person was about to do ("Close this tab?", "Quit KubeKubeDashDash?"), the destructive
 * button goes on with it and the other button, Esc or a click outside keeps the editors.
 */
@Composable
fun DiscardPromptDialog(prompt: DiscardPrompt) {
    ConfirmActionDialog(
        title = prompt.verb.title,
        body = discardPromptBody(prompt.edits),
        confirmLabel = prompt.verb.confirmLabel,
        destructive = true,
        onConfirm = prompt.onDiscard,
        onDismiss = prompt.onKeepEditing,
    )
}

/** What the dialog says: how many editors, which, and what closing them means. */
internal fun discardPromptBody(edits: List<DirtyEdit>): String {
    val single = edits.size == 1
    return buildString {
        append(if (single) "1 editor has unsaved changes:" else "${edits.size} editors have unsaved changes:")
        edits.take(MAX_LISTED_EDITS).forEach { append("\n• ").append(editLine(it)) }
        if (edits.size > MAX_LISTED_EDITS) append("\n• … and ${edits.size - MAX_LISTED_EDITS} more")
        append(if (single) "\n\nIt will close without applying." else "\n\nThey will close without applying.")
    }
}

/** "ConfigMap example-ns/demo-cm (cluster-a)"; an Apply YAML editor's label already names its cluster. */
private fun editLine(edit: DirtyEdit): String = if (edit.label.endsWith("(${edit.context})")) edit.label else "${edit.label} (${edit.context})"
