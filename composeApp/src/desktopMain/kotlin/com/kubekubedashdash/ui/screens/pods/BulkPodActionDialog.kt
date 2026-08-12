package com.kubekubedashdash.ui.screens.pods

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
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.ui.screens.pods.viewmodel.BulkPodVerb
import com.kubekubedashdash.ui.screens.pods.viewmodel.BulkRunState

@Composable
internal fun BulkPodActionDialog(
    verb: BulkPodVerb,
    pods: List<PodInfo>,
    runState: BulkRunState?,
    onConfirm: () -> Unit,
    onCancelRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (runState !is BulkRunState.Running) onDismiss() },
        title = { Text("${verb.actionLabel} ${pods.size} ${if (pods.size == 1) "Pod" else "Pods"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (runState) {
                    null -> {
                        val body = when (verb) {
                            BulkPodVerb.DELETE -> "Delete ${pods.size} pods? This cannot be undone."

                            BulkPodVerb.EVICT ->
                                "Evict ${pods.size} pods? Each pod is gracefully removed and rescheduled by its " +
                                    "controller. Evictions respect PodDisruptionBudgets and may be rejected."
                        }
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                        Column(
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        ) {
                            pods.forEach { pod ->
                                Text("${pod.namespace}/${pod.name}", style = MaterialTheme.typography.bodySmall)
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
                                "${runState.total} — ${runState.currentPodName}",
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
                                        "${f.pod.namespace}/${f.pod.name} — ${f.reason}",
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
