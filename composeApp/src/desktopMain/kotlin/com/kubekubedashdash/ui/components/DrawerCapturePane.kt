package com.kubekubedashdash.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextBright
import com.kubekubedashdash.services.ActiveCaptureTask
import com.kubekubedashdash.services.logcapture.CapturePhase
import com.kubekubedashdash.services.logcapture.ContainerOutcome
import com.kubekubedashdash.services.logcapture.PodCaptureProgress
import com.kubekubedashdash.services.logcapture.humanBytes
import java.awt.Desktop
import java.io.File

private fun revealCaptureFolder(outputDir: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(File(outputDir))
        }
    }
}

private fun revealButtonLabel(): String {
    val osName = System.getProperty("os.name") ?: ""
    return when {
        osName.contains("Mac") -> "Reveal in Finder"
        osName.contains("Win") -> "Show in Explorer"
        else -> "Open folder"
    }
}

/** Pod row status: a glyph + label pair, never colour alone. */
private fun podStatus(pod: PodCaptureProgress): Pair<String, String> = when {
    pod.hasFailure -> "✕" to "failed"
    !pod.done -> "…" to "capturing"
    pod.outcomes.isNotEmpty() && pod.outcomes.values.all { it is ContainerOutcome.Skipped } -> "–" to "skipped"
    else -> "✓" to "captured"
}

@Composable
fun DrawerCapturePane(tab: ActiveCaptureTask, modifier: Modifier = Modifier) {
    val state by tab.task.state.collectAsState()
    val listState = rememberLazyListState()
    val phase = state.phase

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                when (phase) {
                    is CapturePhase.Listing -> Text(
                        "Counting pods…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextBright,
                    )

                    is CapturePhase.Running -> {
                        Text(
                            "Capturing \"${state.namespace}\"…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextBright,
                        )
                        Text(
                            "${state.completedPods}/${state.totalPods} pods · ${humanBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextBright,
                        )
                    }

                    is CapturePhase.Completed -> Text(
                        phase.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextBright,
                    )

                    is CapturePhase.Cancelled -> Text(
                        phase.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextBright,
                    )

                    is CapturePhase.Failed -> Text(
                        phase.summary ?: phase.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextBright,
                    )
                }
            }

            if (phase is CapturePhase.Running) {
                OutlinedButton(
                    onClick = { tab.task.cancel() },
                ) {
                    Text("Cancel")
                }
            } else if (phase is CapturePhase.Completed || phase is CapturePhase.Cancelled || phase is CapturePhase.Failed) {
                OutlinedButton(
                    onClick = { revealCaptureFolder(state.outputDir) },
                ) {
                    Text(revealButtonLabel())
                }
            }
        }

        if (phase is CapturePhase.Running) {
            LinearProgressIndicator(
                progress = {
                    if (state.totalPods > 0) state.completedPods.toFloat() / state.totalPods else 0f
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }

        val sortedPods = remember(state.pods) {
            state.pods.sortedWith(compareByDescending<PodCaptureProgress> { it.hasFailure }.thenBy { it.podName })
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(sortedPods, key = { it.podName }) { pod ->
                    CapturePodRow(pod)
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CapturePodRow(pod: PodCaptureProgress) {
    val (glyph, label) = podStatus(pod)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Red is carried by the glyph only: KdError is 3.3:1 on the dark drawer
            // surface, so tinting the message text would cost more contrast than the
            // colour is worth. The adjacent "failed" label already says it in words.
            Text(
                glyph,
                style = MaterialTheme.typography.bodyMedium,
                color = if (pod.hasFailure) KdError else KdTextBright,
            )
            Text(
                pod.podName,
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextBright,
                modifier = Modifier.weight(1f),
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = KdTextBright)
        }
        if (pod.hasFailure) {
            val failures = pod.outcomes.filterValues { it is ContainerOutcome.Failed }
            SelectionContainer {
                Column(modifier = Modifier.padding(start = 22.dp)) {
                    failures.forEach { (containerName, outcome) ->
                        val message = (outcome as ContainerOutcome.Failed).message
                        Text(
                            "$containerName: $message",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextBright,
                        )
                    }
                }
            }
        }
    }
}
