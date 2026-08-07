package com.kubekubedashdash.ui.screens.generic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.delay

/** Where a "View logs" click on a Job should land, based on its live pods. */
internal sealed interface JobLogTarget {
    data class AutoOpen(val pod: PodInfo, val container: String?) : JobLogTarget

    data class PickPod(val pods: List<PodInfo>) : JobLogTarget

    data class PickContainer(val pod: PodInfo) : JobLogTarget

    data object NoPods : JobLogTarget
}

/** [pods] is expected newest-first (listJobPods guarantees it). */
internal fun resolveJobLogTarget(pods: List<PodInfo>): JobLogTarget = when {
    pods.isEmpty() -> JobLogTarget.NoPods
    pods.size > 1 -> JobLogTarget.PickPod(pods)
    pods.single().containers.size > 1 -> JobLogTarget.PickContainer(pods.single())
    else -> JobLogTarget.AutoOpen(pods.single(), container = null)
}

private sealed interface JobLogsDialogState {
    data object Loading : JobLogsDialogState

    data class Error(val message: String) : JobLogsDialogState

    data class Resolved(val target: JobLogTarget) : JobLogsDialogState
}

/**
 * Resolves a Job's live pods and either opens the log viewer directly (a
 * single pod with a single container) or asks the user to pick a pod and/or
 * container first. Mirrors [com.kubekubedashdash.ui.screens.pods.TerminalContainerPickerDialog]'s
 * dialog shape.
 */
@Composable
fun JobLogsDialog(
    job: GenericResourceInfo,
    client: ReactiveKubeClient,
    onOpenLogs: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember(job.uid) { mutableStateOf<JobLogsDialogState>(JobLogsDialogState.Loading) }
    // Lets a PickPod selection drill into that pod's container list without
    // re-fetching — the pod list we already have carries the containers too.
    var pickedPod by remember(job.uid) { mutableStateOf<PodInfo?>(null) }
    var showLoading by remember(job.uid) { mutableStateOf(false) }

    LaunchedEffect(job.uid) {
        val result = client.listJobPods(job.namespace ?: "", job.uid)
        state = result.fold(
            onSuccess = { pods -> JobLogsDialogState.Resolved(resolveJobLogTarget(pods)) },
            onFailure = { e -> JobLogsDialogState.Error(e.message ?: "Failed to list pods") },
        )
    }

    // Loading never paints until this fires — a fast AutoOpen resolution
    // never shows a dialog at all; only a slow lookup gets a spinner.
    LaunchedEffect(job.uid) {
        delay(300)
        showLoading = true
    }

    when (val s = state) {
        is JobLogsDialogState.Loading -> if (showLoading) LoadingDialog(job, onDismiss)

        is JobLogsDialogState.Error -> ErrorDialog(job, s.message, onDismiss)

        is JobLogsDialogState.Resolved -> {
            val effectiveTarget = pickedPod?.let { JobLogTarget.PickContainer(it) } ?: s.target

            if (effectiveTarget is JobLogTarget.AutoOpen) {
                LaunchedEffect(effectiveTarget) {
                    onOpenLogs(effectiveTarget.pod.name, effectiveTarget.pod.namespace, effectiveTarget.container)
                    onDismiss()
                }
            }

            when (effectiveTarget) {
                // Handled by the LaunchedEffect above; nothing to render.
                is JobLogTarget.AutoOpen -> Unit

                is JobLogTarget.NoPods -> NoPodsDialog(job, onDismiss)

                is JobLogTarget.PickPod -> PickPodDialog(
                    job = job,
                    pods = effectiveTarget.pods,
                    onPick = { pod ->
                        if (pod.containers.size > 1) {
                            pickedPod = pod
                        } else {
                            onOpenLogs(pod.name, pod.namespace, null)
                            onDismiss()
                        }
                    },
                    onDismiss = onDismiss,
                )

                is JobLogTarget.PickContainer -> PickContainerDialog(
                    job = job,
                    pod = effectiveTarget.pod,
                    onPick = { container ->
                        onOpenLogs(effectiveTarget.pod.name, effectiveTarget.pod.namespace, container)
                        onDismiss()
                    },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun JobLogsAlertDialog(
    job: GenericResourceInfo,
    onDismiss: () -> Unit,
    dismissLabel: String,
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KdSurface,
        title = {
            Text(
                "Job logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KdTextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${job.namespace}/${job.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                body()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}

@Composable
private fun LoadingDialog(job: GenericResourceInfo, onDismiss: () -> Unit) {
    JobLogsAlertDialog(job = job, onDismiss = onDismiss, dismissLabel = "Cancel") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Looking up pods…",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextPrimary,
            )
        }
    }
}

@Composable
private fun ErrorDialog(job: GenericResourceInfo, message: String, onDismiss: () -> Unit) {
    JobLogsAlertDialog(job = job, onDismiss = onDismiss, dismissLabel = "Close") {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = KdTextPrimary)
    }
}

@Composable
private fun NoPodsDialog(job: GenericResourceInfo, onDismiss: () -> Unit) {
    JobLogsAlertDialog(job = job, onDismiss = onDismiss, dismissLabel = "Close") {
        Text(
            "No pods found for this job. They may have been deleted or garbage-collected " +
                "(ttlSecondsAfterFinished).",
            style = MaterialTheme.typography.bodyMedium,
            color = KdTextPrimary,
        )
    }
}

@Composable
private fun PickPodDialog(
    job: GenericResourceInfo,
    pods: List<PodInfo>,
    onPick: (PodInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    JobLogsAlertDialog(job = job, onDismiss = onDismiss, dismissLabel = "Cancel") {
        pods.forEach { pod ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { onPick(pod) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pod.name, style = MaterialTheme.typography.bodyMedium, color = KdTextPrimary)
                            Text(
                                pod.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor(pod.status),
                            )
                        }
                        Text(pod.age, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickContainerDialog(
    job: GenericResourceInfo,
    pod: PodInfo,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    JobLogsAlertDialog(job = job, onDismiss = onDismiss, dismissLabel = "Cancel") {
        pod.containers.forEach { container ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { onPick(container.name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        container.name,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextPrimary,
                    )
                }
            }
        }
    }
}
