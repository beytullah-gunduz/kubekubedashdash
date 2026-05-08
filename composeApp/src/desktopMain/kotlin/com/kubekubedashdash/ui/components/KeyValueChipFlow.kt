package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextSecondary

private val NoisyAnnotationKeys = setOf(
    "kubectl.kubernetes.io/last-applied-configuration",
)

/**
 * Flow-laid-out chip grid for labels or annotations. Replaces the
 * `chunked(2) { Row { LabelChip... } }` two-column hack which collapsed
 * one chip to ~1 char wide whenever its sibling was very long, causing
 * the inner `Text` to wrap character-by-character.
 *
 * Hides `kubectl.kubernetes.io/last-applied-configuration` (the entire
 * manifest as JSON) by default behind a disclosure — the YAML tab is
 * the right place for that payload.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyValueChipFlow(
    entries: Map<String, String>,
    activeFilter: Map<String, String>,
    onToggle: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(entries) { entries.filterKeys { it !in NoisyAnnotationKeys } }
    val hiddenCount = entries.size - visible.size
    var showHidden by remember(entries) { mutableStateOf(false) }
    val displayed = if (showHidden) entries else visible

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            displayed.forEach { (k, v) ->
                LabelChip(
                    key = k,
                    value = v,
                    active = activeFilter[k] == v,
                    onClick = { onToggle(k, v) },
                )
            }
        }
        if (hiddenCount > 0) {
            TextButton(
                onClick = { showHidden = !showHidden },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    if (showHidden) {
                        "Hide last-applied-configuration"
                    } else {
                        "Show $hiddenCount hidden (last-applied-configuration)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
            }
        }
    }
}
