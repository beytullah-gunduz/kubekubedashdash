package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.filter_list_filled
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

fun parseMapSelector(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split(",")
        .mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }
        .toMap()
}

fun matchesMapSelector(entries: Map<String, String>, selector: Map<String, String>): Boolean = selector.all { (k, v) -> entries[k] == v }

/**
 * Toggles a single key=value entry in a map-selector query string. If the
 * pair is already present, removes it; if a different value is bound to
 * the same key, replaces it; if absent, appends it. Used by detail-panel
 * chips for one-click filter add/remove.
 */
fun toggleSelectorEntry(query: String, key: String, value: String): String {
    val pairs = parseMapSelector(query).toMutableMap()
    if (pairs[key] == value) {
        pairs.remove(key)
    } else {
        pairs[key] = value
    }
    return pairs.entries.joinToString(", ") { "${it.key}=${it.value}" }
}

@Composable
fun MapSelectorChip(
    query: String,
    onQueryChange: (String) -> Unit,
    title: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    pulseOnEntry: Boolean = false,
) {
    val active = query.isNotBlank()
    val matchCount = remember(query) { parseMapSelector(query).size }
    var expanded by remember { mutableStateOf(false) }

    // Pulse intensity 0f..1f. Two 300 ms cycles fire once when this chip first
    // enters composition with pulseOnEntry=true (no scale/translation motion).
    // Driving from a per-instance LaunchedEffect avoids races with AnimatedContent
    // briefly composing both the outgoing and incoming screen's chips at once.
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (pulseOnEntry) {
            repeat(2) {
                pulse.animateTo(1f, tween(150, easing = LinearOutSlowInEasing))
                pulse.animateTo(0f, tween(150, easing = LinearOutSlowInEasing))
            }
        }
    }

    val baseColor = if (active) KdPrimary.copy(alpha = 0.15f) else KdSurfaceVariant
    val pulseColor = KdPrimary.copy(alpha = 0.15f + 0.35f * pulse.value)
    val surfaceColor = if (pulse.value > 0f) pulseColor else baseColor
    val borderColor = if (pulse.value > 0f) KdPrimary.copy(alpha = pulse.value) else Color.Transparent

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surfaceColor,
            modifier = Modifier
                .clickable {
                    if (pulse.value > 0f) scope.launch { pulse.snapTo(0f) }
                    expanded = !expanded
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .border(1.5.dp, borderColor, RoundedCornerShape(16.dp)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(Res.drawable.filter_list_filled),
                    contentDescription = "Filter by ${title.lowercase()}",
                    modifier = Modifier.size(14.dp),
                    tint = if (active) KdPrimary else KdTextSecondary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (active) "$title: $matchCount" else title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) KdPrimary else KdTextSecondary,
                )
                if (active) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painterResource(Res.drawable.close_filled),
                        contentDescription = "Clear ${title.lowercase()} filter",
                        modifier = Modifier
                            .size(12.dp)
                            .clickable { onQueryChange("") }
                            .pointerHoverIcon(PointerIcon.Hand),
                        tint = KdPrimary,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(min = 220.dp, max = 300.dp),
            ) {
                Text(
                    "Filter by ${title.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(placeholder, style = MaterialTheme.typography.bodySmall)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Comma-separated key=value pairs",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
fun LabelSelectorChip(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    pulseOnEntry: Boolean = false,
) = MapSelectorChip(
    query = query,
    onQueryChange = onQueryChange,
    title = "Labels",
    placeholder = "app=nginx, tier=backend",
    modifier = modifier,
    pulseOnEntry = pulseOnEntry,
)

@Composable
fun AnnotationSelectorChip(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    pulseOnEntry: Boolean = false,
) = MapSelectorChip(
    query = query,
    onQueryChange = onQueryChange,
    title = "Annotations",
    placeholder = "owner=team-a, prometheus.io/scrape=true",
    modifier = modifier,
    pulseOnEntry = pulseOnEntry,
)

/**
 * Small "Clear" chip that wipes the shared label + annotation selectors
 * in one click. Render conditionally — only worth showing when at least
 * one of those selectors is non-blank.
 */
@Composable
fun ClearFiltersChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = KdSurfaceVariant,
        modifier = modifier
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(Res.drawable.close_filled),
                contentDescription = "Clear all filters",
                modifier = Modifier.size(12.dp),
                tint = KdTextSecondary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Clear",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
        }
    }
}
