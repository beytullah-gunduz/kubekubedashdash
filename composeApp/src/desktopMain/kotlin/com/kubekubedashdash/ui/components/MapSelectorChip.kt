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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.resources.filter_list_filled
import com.kubekubedashdash.resources.sell_filled
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
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

/** One selectable `key=value` with how many loaded resources carry it. */
data class MapSelectorOption(val key: String, val value: String, val count: Int)

/**
 * Every distinct key=value across [entries] with its count, **sorted by key
 * then value**. [entries] is one map per loaded resource. Keys in
 * [NoisyAnnotationKeys] are excluded: they are unique per resource and would
 * fill the list with count-1 rows nobody can filter by.
 */
fun mapSelectorOptions(entries: List<Map<String, String>>): List<MapSelectorOption> {
    val counts = LinkedHashMap<Pair<String, String>, Int>()
    for (entry in entries) {
        for ((key, value) in entry) {
            if (key in NoisyAnnotationKeys) continue
            val pair = key to value
            counts[pair] = (counts[pair] ?: 0) + 1
        }
    }
    return counts.entries
        .map { (pair, count) -> MapSelectorOption(pair.first, pair.second, count) }
        .sortedWith(compareBy({ it.key }, { it.value }))
}

/**
 * [options] narrowed by a case-insensitive substring match over "key=value",
 * then ordered by count descending, then key, then value, then capped.
 */
fun visibleMapSelectorOptions(
    options: List<MapSelectorOption>,
    search: String,
    cap: Int = 200,
): List<MapSelectorOption> {
    val needle = search.trim().lowercase()
    val matched = if (needle.isEmpty()) {
        options
    } else {
        options.filter { "${it.key}=${it.value}".lowercase().contains(needle) }
    }
    return matched
        .sortedWith(compareByDescending<MapSelectorOption> { it.count }.thenBy { it.key }.thenBy { it.value })
        .take(cap)
}

/**
 * [query] without the entry for [key], preserving the order of the rest.
 * Round-trips through [parseMapSelector], so unparseable fragments the user
 * typed are dropped and duplicate keys collapse — acceptable, and the reason
 * this is not a string edit.
 */
fun removeSelectorEntry(query: String, key: String): String {
    val pairs = parseMapSelector(query).toMutableMap()
    pairs.remove(key)
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
    compact: Boolean = false,
    options: List<MapSelectorOption>? = null,
    icon: DrawableResource = Res.drawable.filter_list_filled,
) {
    val active = query.isNotBlank()
    val matchCount = remember(query) { parseMapSelector(query).size }
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

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
                    painterResource(icon),
                    contentDescription = "Filter by ${title.lowercase()}",
                    modifier = Modifier.size(14.dp),
                    tint = if (active) KdPrimary else KdTextSecondary,
                )
                if (!compact) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (active) "$title: $matchCount" else title,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) KdPrimary else KdTextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
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
                if (options != null) {
                    if (options.isEmpty()) {
                        Text(
                            "No ${title.lowercase()} on the loaded resources",
                            style = MaterialTheme.typography.bodySmall,
                            color = KdTextSecondary,
                        )
                    } else {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = {
                                Text(
                                    "Search ${title.lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        val cap = 200
                        val selected = remember(query) { parseMapSelector(query) }
                        val visible = remember(options, search) { visibleMapSelectorOptions(options, search, cap) }
                        val matchedCount = remember(options, search) {
                            visibleMapSelectorOptions(options, search, cap = Int.MAX_VALUE).size
                        }
                        Column(
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            visible.forEach { option ->
                                val applied = selected[option.key] == option.value
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onQueryChange(toggleSelectorEntry(query, option.key, option.value))
                                        }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${option.key} = ${option.value}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (applied) KdPrimary else Color.Unspecified,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "${option.count}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KdTextSecondary,
                                    )
                                    if (applied) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            painterResource(Res.drawable.check_filled),
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = KdPrimary,
                                        )
                                    }
                                }
                            }
                        }
                        if (matchedCount > cap) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "+${matchedCount - cap} more — type to narrow",
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary.copy(alpha = 0.6f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
    compact: Boolean = false,
    options: List<MapSelectorOption>? = null,
) = MapSelectorChip(
    query = query,
    onQueryChange = onQueryChange,
    title = "Labels",
    placeholder = "app=nginx, tier=backend",
    modifier = modifier,
    pulseOnEntry = pulseOnEntry,
    compact = compact,
    options = options,
    icon = Res.drawable.sell_filled,
)

@Composable
fun AnnotationSelectorChip(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    pulseOnEntry: Boolean = false,
    compact: Boolean = false,
    options: List<MapSelectorOption>? = null,
) = MapSelectorChip(
    query = query,
    onQueryChange = onQueryChange,
    title = "Annotations",
    placeholder = "owner=team-a, prometheus.io/scrape=true",
    modifier = modifier,
    pulseOnEntry = pulseOnEntry,
    compact = compact,
    options = options,
    icon = Res.drawable.description_filled,
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
    compact: Boolean = false,
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
            if (!compact) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
            }
        }
    }
}
