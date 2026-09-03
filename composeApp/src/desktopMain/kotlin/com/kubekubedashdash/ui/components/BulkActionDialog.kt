package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.ui.feedback.UndoAction

@Composable
internal fun <T> BulkActionDialog(
    verb: BulkVerb,
    items: List<T>,
    itemLabel: (T) -> String,
    kindSingular: String,
    kindPlural: String,
    confirmBody: String,
    runState: BulkRunState<T>?,
    onConfirm: () -> Unit,
    onCancelRun: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Inverse of a clean run over the mounted [items], attached to the
     * completion toast as Undo. Null (the default) for verbs with no cheap,
     * safe inverse.
     */
    undo: ((List<T>) -> UndoAction?)? = null,
) {
    // A run that finished with every item succeeding closes the dialog on its
    // own and reports through the toast layer; a partial or stopped run keeps
    // the dialog up with its failure list, exactly as before. Undo is offered
    // only when the mounted snapshot is the run that finished — a dialog that
    // reattached to a run already in flight mounts with no snapshot.
    val feedback = LocalActionFeedback.current
    // Keyed on the clean Finished value (null while Running), not on every
    // progress tick, so the effect is created once per outcome.
    val cleanFinish = cleanBulkFinish(runState)
    LaunchedEffect(cleanFinish) {
        val finished = cleanFinish ?: return@LaunchedEffect
        val undoAction = if (undo != null && items.size == finished.total) undo(items) else null
        feedback.success(bulkDoneTitle(finished.verb, finished.attempted, kindSingular, kindPlural), undo = undoAction)
        onDismiss()
    }
    // Header from the attached run when one exists: the runner outlives
    // screen compositions, so the mounted snapshot may not be the run the
    // body is rendering — the title must count what is actually shown.
    val shownVerb = runState?.verb ?: verb
    val shownCount = runState?.total ?: items.size
    AlertDialog(
        onDismissRequest = { if (runState !is BulkRunState.Running) onDismiss() },
        title = { Text("${shownVerb.actionLabel} $shownCount ${if (shownCount == 1) kindSingular else kindPlural}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (runState) {
                    null -> {
                        Text(confirmBody, style = MaterialTheme.typography.bodyMedium)
                        Column(
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        ) {
                            items.forEach { item ->
                                Text(itemLabel(item), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    is BulkRunState.Running -> {
                        LinearProgressIndicator(
                            progress = { runState.done.toFloat() / runState.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${verb.progressLabel} ${(runState.done + 1).coerceAtMost(runState.total)} of " +
                                "${runState.total} — ${runState.currentItemLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    is BulkRunState.Finished -> {
                        val succeeded = runState.attempted - runState.failures.size
                        val skipped = if (runState.cancelled) {
                            ", ${runState.total - runState.attempted} skipped (stopped early)"
                        } else {
                            ""
                        }
                        Text(
                            "$succeeded succeeded, ${runState.failures.size} failed$skipped",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (runState.failures.isNotEmpty()) {
                            Column(
                                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                            ) {
                                runState.failures.forEach { f ->
                                    Text(
                                        "${itemLabel(f.item)} — ${f.reason}",
                                        color = KdError,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (runState) {
                null ->
                    TextButton(onClick = onConfirm) {
                        Text(verb.actionLabel, color = if (verb.destructive) KdError else KdTextPrimary)
                    }

                is BulkRunState.Running ->
                    TextButton(onClick = onCancelRun, enabled = !runState.cancelRequested) {
                        Text(if (runState.cancelRequested) "Stopping…" else "Stop")
                    }

                is BulkRunState.Finished ->
                    TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (runState == null) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else {
            null
        },
    )
}
