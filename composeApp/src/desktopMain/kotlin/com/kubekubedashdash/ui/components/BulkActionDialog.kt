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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary

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
) {
    AlertDialog(
        onDismissRequest = { if (runState !is BulkRunState.Running) onDismiss() },
        title = { Text("${verb.actionLabel} ${items.size} ${if (items.size == 1) kindSingular else kindPlural}") },
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
