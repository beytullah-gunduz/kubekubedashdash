package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.filter_list_filled
import org.jetbrains.compose.resources.painterResource

/**
 * Toolbar chip that filters a list by a string-keyed dimension (status,
 * type, node, etc.). Shows the [label] when no filter is applied and
 * "[label]: N/M" when at least one value is excluded. Clicking opens a
 * multi-select checklist of every value currently present in the data
 * set.
 *
 * The component is stateless — the screen owns the [selected] set and
 * applies it to the list. By convention, an empty [selected] set means
 * "show nothing" and a set equal to [available] means "no filter applied".
 *
 * [itemContent] renders each row in the dropdown next to its checkbox.
 * The default uses [StatusCell] so callers filtering on Pod/Node status
 * keep the colored-icon look. Override it for non-status dimensions
 * (e.g. plain [Text] for node names, an icon-aware row for event types).
 */
@Composable
fun StatusFilterMenu(
    available: Set<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    label: String = "Status",
    itemContent: @Composable (String) -> Unit = { value -> StatusCell(status = value) },
    pulseOnEntry: Boolean = false,
) {
    if (available.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val active = selected.size != available.size

    // Pulse 0f..1f, fires once on first composition if pulseOnEntry — matches
    // the MapSelectorChip animation so cross-screen filter cues feel consistent.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (pulseOnEntry) {
            repeat(2) {
                pulse.animateTo(1f, tween(150, easing = LinearOutSlowInEasing))
                pulse.animateTo(0f, tween(150, easing = LinearOutSlowInEasing))
            }
        }
    }

    val baseBg = if (active) KdPrimary.copy(alpha = 0.08f) else KdSurface
    val pulseBg = KdPrimary.copy(alpha = 0.08f + 0.42f * pulse.value)
    val bgColor = if (pulse.value > 0f) pulseBg else baseBg
    val baseBorder = if (active) KdPrimary.copy(alpha = 0.6f) else KdBorder
    val borderColor = if (pulse.value > 0f) KdPrimary.copy(alpha = 0.6f + 0.4f * pulse.value) else baseBorder

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(6.dp),
                )
                .background(bgColor)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.filter_list_filled),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (active) KdPrimary else KdTextSecondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (active) "$label: ${selected.size}/${available.size}" else label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) KdPrimary else KdTextPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(Res.drawable.expand_more_filled),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (active) KdPrimary else KdTextSecondary,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "All",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable(onClick = onSelectAll)
                        .padding(8.dp),
                )
                Text(
                    "None",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable(onClick = onSelectNone)
                        .padding(8.dp),
                )
            }
            HorizontalDivider()
            available.sorted().forEach { value ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = value in selected,
                                onCheckedChange = { onToggle(value) },
                                colors = CheckboxDefaults.colors(checkedColor = KdPrimary),
                            )
                            itemContent(value)
                        }
                    },
                    onClick = { onToggle(value) },
                )
            }
        }
    }
}
