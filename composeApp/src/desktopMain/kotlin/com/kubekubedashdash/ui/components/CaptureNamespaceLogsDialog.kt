package com.kubekubedashdash.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.services.logcapture.CaptureOptions
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureGateway
import com.kubekubedashdash.util.DirectoryPicker
import java.io.File

/** Time-window choices offered by the dialog, mapped to [CaptureOptions.sinceSeconds]. */
internal enum class CaptureWindow(val label: String, val seconds: Int?) {
    EVERYTHING("Everything available", null),
    LAST_15_MINUTES("Last 15 minutes", 900),
    LAST_HOUR("Last hour", 3600),
    LAST_6_HOURS("Last 6 hours", 21600),
    LAST_24_HOURS("Last 24 hours", 86400),
}

internal fun totalContainers(pods: List<CapturePodSpec>): Int = pods.sumOf { it.containers.size }

internal fun podsWithRestarts(pods: List<CapturePodSpec>): Int = pods.count { pod -> pod.containers.any { it.restartCount > 0 } }

internal fun showPreviousOption(pods: List<CapturePodSpec>): Boolean = podsWithRestarts(pods) > 0

internal fun isLargeCapture(pods: List<CapturePodSpec>): Boolean = pods.size > 100

internal fun startEnabled(pods: List<CapturePodSpec>?, destinationWritable: Boolean): Boolean = pods != null && pods.isNotEmpty() && destinationWritable

/**
 * Dialog offered from the Namespaces row menu and the command palette that
 * captures every pod's container logs in [namespace] to disk. Runs a fresh
 * [NamespaceLogCaptureGateway.listPods] scan on open so the pod/container
 * counts and restart-driven "include previous logs" option are current, then
 * hands the chosen [CaptureOptions] to [onStart].
 */
@Composable
fun CaptureNamespaceLogsDialog(
    namespace: String,
    gateway: NamespaceLogCaptureGateway,
    onStart: (CaptureOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var podsResult by remember(namespace) { mutableStateOf<Result<List<CapturePodSpec>>?>(null) }
    LaunchedEffect(namespace) {
        podsResult = gateway.listPods(namespace)
    }

    val pods = podsResult?.getOrNull()
    val listError = podsResult?.exceptionOrNull()?.message
    val pending = podsResult == null

    var selectedWindow by remember { mutableStateOf(CaptureWindow.EVERYTHING) }
    var includePrevious by remember { mutableStateOf(false) }
    var windowMenuExpanded by remember { mutableStateOf(false) }

    val destinationDir by PreferenceRepository.captureDestinationDir.collectAsState()
    val destinationWritable = remember(destinationDir) { File(destinationDir).canWrite() }

    val canStart = startEnabled(pods, destinationWritable)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture Namespace Logs") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.width(480.dp),
            ) {
                when {
                    pending -> Text("Counting pods…", style = MaterialTheme.typography.bodyMedium)

                    listError != null -> Text(
                        listError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdError,
                    )

                    pods != null && pods.isEmpty() -> Text(
                        "There are no pods to capture.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    pods != null -> {
                        Text(
                            "${pods.size} pods · ${totalContainers(pods)} containers in \"$namespace\".",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (isLargeCapture(pods)) {
                            Text(
                                "Large capture — ${pods.size} pods. Consider a time window.",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                }

                if (pods != null && pods.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Time window", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, KdBorder, RoundedCornerShape(4.dp))
                                    .clickable { windowMenuExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(selectedWindow.label, style = MaterialTheme.typography.bodyMedium)
                            }
                            DropdownMenu(
                                expanded = windowMenuExpanded,
                                onDismissRequest = { windowMenuExpanded = false },
                            ) {
                                CaptureWindow.entries.forEach { window ->
                                    DropdownMenuItem(
                                        text = { Text(window.label) },
                                        onClick = {
                                            selectedWindow = window
                                            windowMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        if (showPreviousOption(pods)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = includePrevious,
                                    onCheckedChange = { includePrevious = it },
                                    colors = CheckboxDefaults.colors(checkedColor = KdPrimary),
                                )
                                Text(
                                    "${podsWithRestarts(pods)} pods have restarts — include previous (crashed) container logs",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        Text("Destination", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                destinationDir,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    DirectoryPicker.pick(destinationDir)?.let {
                                        PreferenceRepository.setCaptureDestinationDir(it)
                                    }
                                },
                            ) {
                                Text("Choose…")
                            }
                        }
                        if (!destinationWritable) {
                            Text(
                                "Can't write to this folder. Choose another destination.",
                                style = MaterialTheme.typography.labelSmall,
                                color = KdError,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStart(
                        CaptureOptions(
                            sinceSeconds = selectedWindow.seconds,
                            includePrevious = includePrevious,
                            destinationDir = destinationDir,
                        ),
                    )
                },
                enabled = canStart,
            ) {
                Text("Start capture")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
