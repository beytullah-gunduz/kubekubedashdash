package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.ui.crt.crtCardReveal
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
    /** Replaces the default confirm-phase body (confirmBody + item list). */
    confirmContent: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
    /** Overrides the confirm button label (default verb.actionLabel). */
    confirmLabel: String? = null,
    /** Overrides verb.destructive for the confirm button colour. */
    confirmDestructive: Boolean? = null,
    /** Appended to the clean-run toast title: " to 3 replicas". */
    doneTitleSuffix: String = "",
    /** D11: offer "Restore K" in a partial Finished summary, built from [undo] over bulkSucceeded(items, finished). */
    undoAfterPartialRun: Boolean = false,
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
    val finished = runState as? BulkRunState.Finished<T>
    val partialUndo = remember(finished, items) {
        if (finished != null && cleanFinish == null && undoAfterPartialRun && undo != null && items.size == finished.total) {
            bulkSucceeded(items, finished).takeIf { it.isNotEmpty() }?.let { s -> undo(s)?.let { s.size to it } }
        } else {
            null
        }
    }
    LaunchedEffect(cleanFinish) {
        val done = cleanFinish ?: return@LaunchedEffect
        val undoAction = if (undo != null && items.size == done.total) undo(items) else null
        feedback.success(bulkDoneTitle(done.verb, done.attempted, kindSingular, kindPlural) + doneTitleSuffix, undo = undoAction)
        onDismiss()
    }
    // Header from the attached run when one exists: the runner outlives
    // screen compositions, so the mounted snapshot may not be the run the
    // body is rendering — the title must count what is actually shown.
    val shownVerb = runState?.verb ?: verb
    val shownCount = runState?.total ?: items.size
    AlertDialog(
        modifier = Modifier.crtCardReveal(),
        onDismissRequest = { if (runState !is BulkRunState.Running) onDismiss() },
        title = { Text("${shownVerb.actionLabel} $shownCount ${if (shownCount == 1) kindSingular else kindPlural}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (runState) {
                    null -> {
                        if (confirmContent != null) {
                            confirmContent()
                        } else {
                            Text(confirmBody, style = MaterialTheme.typography.bodyMedium)
                            Column(
                                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                            ) {
                                items.forEach { item ->
                                    Text(itemLabel(item), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    is BulkRunState.Running -> {
                        ItemProgressBar(done = runState.done, total = runState.total, modifier = Modifier.fillMaxWidth())
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
                    TextButton(onClick = onConfirm, enabled = confirmEnabled, shape = kdRoundShape) {
                        // An explicit colour would override the disabled content colour,
                        // so a disabled confirm (bulk Scale with no valid target) keeps the
                        // button's own dimmed colour.
                        val confirmColor = when {
                            !confirmEnabled -> Color.Unspecified
                            confirmDestructive ?: verb.destructive -> KdError
                            else -> KdTextPrimary
                        }
                        Text(confirmLabel ?: verb.actionLabel, color = confirmColor)
                    }

                is BulkRunState.Running ->
                    TextButton(onClick = onCancelRun, enabled = !runState.cancelRequested, shape = kdRoundShape) {
                        Text(if (runState.cancelRequested) "Stopping…" else "Stop")
                    }

                is BulkRunState.Finished ->
                    TextButton(onClick = onDismiss, shape = kdRoundShape) { Text("Close") }
            }
        },
        dismissButton = when {
            runState == null -> ({ TextButton(onClick = onDismiss, shape = kdRoundShape) { Text("Cancel") } })

            partialUndo != null -> (
                {
                    TextButton(onClick = {
                        feedback.runUndo(partialUndo.second)
                        onDismiss()
                    }, shape = kdRoundShape) {
                        Text("Restore ${partialUndo.first}")
                    }
                }
                )

            else -> null
        },
    )
}
