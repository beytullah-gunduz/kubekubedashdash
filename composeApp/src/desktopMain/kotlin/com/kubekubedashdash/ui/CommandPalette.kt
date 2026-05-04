package com.kubekubedashdash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.search_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** A single navigable item in the command palette. */
data class PaletteEntry(
    val label: String,
    val category: String,
    val icon: DrawableResource,
    val onActivate: () -> Unit,
    val sublabel: String? = null,
)

/**
 * Cmd+K-style command palette. The screen owns the open/closed state and the
 * source-of-truth list of [PaletteEntry]; the palette filters by query, caps
 * each category, and exposes keyboard navigation (↑/↓ to move, Enter to
 * activate, Esc to dismiss).
 *
 * Activating an entry calls its `onActivate` and then [onDismiss] — the
 * caller does not need to close the palette explicitly.
 */
@Composable
fun CommandPalette(
    entries: List<PaletteEntry>,
    onDismiss: () -> Unit,
    perCategoryCap: Int = 8,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf(0) }

    // Filter + cap per category. Keeps the palette legible when a cluster has
    // hundreds of pods — a search is the way to find a deep one.
    val visible by remember(entries, query) {
        derivedStateOf {
            val q = query.trim()
            val matched = entries.filter { entry ->
                q.isEmpty() ||
                    entry.label.contains(q, ignoreCase = true) ||
                    (entry.sublabel?.contains(q, ignoreCase = true) ?: false)
            }
            matched
                .groupBy { it.category }
                .flatMap { (_, items) -> items.take(perCategoryCap) }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(visible) { selected = 0 }
    LaunchedEffect(selected) {
        if (selected in visible.indices) listState.animateScrollToItem(selected)
    }

    fun activateSelected() {
        visible.getOrNull(selected)?.let { entry ->
            entry.onActivate()
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(top = 96.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 640.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 480.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            onDismiss()
                            true
                        }

                        Key.DirectionDown -> {
                            if (visible.isNotEmpty()) {
                                selected = (selected + 1).coerceAtMost(visible.lastIndex)
                            }
                            true
                        }

                        Key.DirectionUp -> {
                            if (visible.isNotEmpty()) selected = (selected - 1).coerceAtLeast(0)
                            true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            activateSelected()
                            true
                        }

                        else -> false
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(10.dp),
            color = KdSurface,
            border = BorderStroke(1.dp, KdBorder),
            shadowElevation = 16.dp,
        ) {
            Column {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    focusRequester = focusRequester,
                )

                if (visible.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No matches",
                            style = MaterialTheme.typography.bodyMedium,
                            color = KdTextSecondary,
                        )
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        var prevCategory: String? = null
                        itemsIndexed(visible, key = { idx, e -> "${e.category}::${e.label}::$idx" }) { idx, entry ->
                            val showHeader = entry.category != prevCategory
                            prevCategory = entry.category
                            if (showHeader) CategoryHeader(entry.category)
                            EntryRow(
                                entry = entry,
                                isSelected = idx == selected,
                                onClick = {
                                    selected = idx
                                    activateSelected()
                                },
                            )
                        }
                    }
                }
                Footer()
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdSurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.search_filled),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = KdTextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Jump to a screen, cluster, pod, or node…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KdTextSecondary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(KdPrimary),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = KdTextPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable(),
            )
        }
    }
}

@Composable
private fun CategoryHeader(category: String) {
    Text(
        text = category.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = KdTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryRow(
    entry: PaletteEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) KdSelected else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (isSelected) KdPrimary.copy(alpha = 0.15f) else KdHover.copy(alpha = 0.6f),
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(entry.icon),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isSelected) KdPrimary else KdTextSecondary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.sublabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            entry.category,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
        )
    }
}

@Composable
private fun Footer() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdSurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HintKey("↑")
            HintKey("↓")
            Spacer(Modifier.width(4.dp))
            Text("navigate", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            Spacer(Modifier.width(16.dp))
            HintKey("↵")
            Spacer(Modifier.width(4.dp))
            Text("activate", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            Spacer(Modifier.width(16.dp))
            HintKey("esc")
            Spacer(Modifier.width(4.dp))
            Text("close", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
        }
    }
}

@Composable
private fun HintKey(label: String) {
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = KdSurface,
        border = BorderStroke(1.dp, KdBorder),
        modifier = Modifier.height(16.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 4.dp).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
        }
    }
}
