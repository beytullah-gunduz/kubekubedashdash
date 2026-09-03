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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.search_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** A single navigable item in the command palette. */
data class PaletteEntry(
    val id: String,
    val label: String,
    val category: String,
    val icon: DrawableResource,
    val onActivate: () -> Unit,
    val sublabel: String? = null,
)

/** A parsed palette query: a recognised leading prefix (if any), and the remaining fuzzy-match text. */
internal data class PaletteQuery(val prefix: String?, val text: String)

/** One row in the palette's flattened list — a category header, or an entry that keeps its index into the filtered entry list. */
internal sealed interface PaletteRow {
    data class Header(val category: String) : PaletteRow
    data class Entry(val entry: PaletteEntry, val entryIndex: Int) : PaletteRow
}

private val KNOWN_WORD_PREFIXES = setOf("pod", "node", "ns", "dep", "crd", "go")

// The nine sidebar section titles screen entries are grouped under (ui/Sidebar.kt:152-206),
// plus "Cluster" for the five ungrouped top-level items — the `go:` prefix's scope.
private val GO_PREFIX_CATEGORIES = setOf(
    "Cluster",
    "Workloads",
    "Config",
    "Network",
    "Storage",
    "Access Control",
    "Autoscaling & Disruption",
    "Governance",
    "Admission Control",
)

/**
 * Parses a leading `>` or `<word>:` prefix off [raw]. An unrecognised
 * `word:` is NOT a prefix — it is left in [PaletteQuery.text], so a name
 * containing a colon still fuzzy-matches.
 */
internal fun parsePaletteQuery(raw: String): PaletteQuery {
    val trimmedStart = raw.trimStart()
    if (trimmedStart.startsWith(">")) {
        return PaletteQuery(prefix = ">", text = trimmedStart.removePrefix(">").trim())
    }
    val colonIndex = trimmedStart.indexOf(':')
    if (colonIndex > 0) {
        val word = trimmedStart.substring(0, colonIndex)
        if (word.none { it.isWhitespace() } && word.lowercase() in KNOWN_WORD_PREFIXES) {
            return PaletteQuery(prefix = "${word.lowercase()}:", text = trimmedStart.substring(colonIndex + 1).trim())
        }
    }
    return PaletteQuery(prefix = null, text = raw.trim())
}

/** The category names a recognised [prefix] narrows the search to, or null for no restriction (including an unrecognised prefix). */
internal fun categoriesForPrefix(prefix: String?): Set<String>? = when (prefix) {
    null -> null
    ">" -> setOf("Actions", "Verbs")
    "pod:" -> setOf("Pods")
    "node:" -> setOf("Nodes")
    "ns:" -> setOf("Namespaces")
    "dep:" -> setOf("Deployments")
    "crd:" -> setOf("Custom Resources")
    "go:" -> GO_PREFIX_CATEGORIES
    else -> emptySet()
}

/**
 * Resolves stored recent-use ids against the current [entries], most-recent
 * first (deduped, in case [recentIds] itself was not); an id that no longer
 * resolves (another cluster's pod, a deleted node) is skipped rather than
 * removed from the store. Every returned entry has its category overridden
 * to "Recent" — load-bearing: without the copy, `entries.groupBy { it.category }`
 * would merge these straight back into their own groups and no Recent header
 * would ever render, and the category override is also what keeps a recent
 * entry's row key distinct from its non-recent counterpart when the same
 * entry appears twice.
 */
internal fun recentEntries(entries: List<PaletteEntry>, recentIds: List<String>): List<PaletteEntry> {
    val byId = entries.associateBy { it.id }
    return recentIds.distinct().mapNotNull { id -> byId[id]?.copy(category = "Recent") }
}

/**
 * Flattens (already category-clustered) [entries] into rows, inserting one
 * [PaletteRow.Header] whenever the category changes from the previous entry.
 */
internal fun paletteRows(entries: List<PaletteEntry>): List<PaletteRow> {
    val rows = mutableListOf<PaletteRow>()
    var previousCategory: String? = null
    entries.forEachIndexed { index, entry ->
        if (entry.category != previousCategory) {
            rows += PaletteRow.Header(entry.category)
            previousCategory = entry.category
        }
        rows += PaletteRow.Entry(entry, index)
    }
    return rows
}

/** The index in [rows] of the [PaletteRow.Entry] whose `entryIndex` is [entryIndex], or -1 if absent. */
internal fun rowIndexOfEntry(rows: List<PaletteRow>, entryIndex: Int): Int = rows.indexOfFirst { it is PaletteRow.Entry && it.entryIndex == entryIndex }

/**
 * Cmd+K-style command palette. The screen owns the open/closed state and the
 * source-of-truth list of [PaletteEntry]; the palette filters by query, caps
 * each category, and exposes keyboard navigation (↑/↓ to move, Enter to
 * activate, Esc to dismiss).
 *
 * On an empty query with no prefix, a `Recent` group of previously-activated
 * entries (`PreferenceRepository.paletteRecents`) renders first. A leading
 * `>` or `<word>:` narrows the search to a fixed set of categories (see
 * [categoriesForPrefix]) and renders as a chip in the search bar; Backspace
 * on an empty search text drops it.
 *
 * Activating an entry records it as a recent, then calls its `onActivate`
 * and [onDismiss] — the caller does not need to close the palette explicitly.
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
    val recents by PreferenceRepository.paletteRecents.collectAsState()

    val parsedQuery = remember(query) { parsePaletteQuery(query) }

    // Fuzzy-filter + sort by score + cap per category. When the parsed text is
    // empty all (prefix-restricted) entries pass. When non-empty, entries are
    // scored by fuzzyScore against label and sublabel; non-matching entries
    // are dropped, and within each category the best-scoring (fewest gaps)
    // entries surface first.
    val visible by remember(entries, parsedQuery, recents) {
        derivedStateOf {
            val restrictedCategories = categoriesForPrefix(parsedQuery.prefix)
            val candidates = if (restrictedCategories != null) entries.filter { it.category in restrictedCategories } else entries
            val q = parsedQuery.text
            if (q.isEmpty()) {
                val grouped = candidates.groupBy { it.category }
                    .flatMap { (_, items) -> items.take(perCategoryCap) }
                if (parsedQuery.prefix == null) recentEntries(entries, recents) + grouped else grouped
            } else {
                candidates.mapNotNull { entry ->
                    val s = minOf(
                        fuzzyScore(q, entry.label) ?: Int.MAX_VALUE,
                        entry.sublabel?.let { fuzzyScore(q, it) } ?: Int.MAX_VALUE,
                    )
                    if (s < Int.MAX_VALUE) entry to s else null
                }
                    .groupBy { (entry, _) -> entry.category }
                    .flatMap { (_, scored) ->
                        scored.sortedBy { (_, score) -> score }
                            .take(perCategoryCap)
                            .map { (entry, _) -> entry }
                    }
            }
        }
    }
    val rows = remember(visible) { paletteRows(visible) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(visible) { selected = 0 }
    LaunchedEffect(selected, rows) {
        if (selected in visible.indices) {
            val rowIndex = rowIndexOfEntry(rows, selected)
            if (rowIndex >= 0) listState.animateScrollToItem(rowIndex)
        }
    }

    fun activateSelected() {
        visible.getOrNull(selected)?.let { entry ->
            PreferenceRepository.recordPaletteUse(entry.id)
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

                        // Drop the whole prefix chip in one keystroke once the search
                        // text is empty; otherwise let the text field handle it
                        // (deleting the last character) normally.
                        Key.Backspace -> {
                            if (parsedQuery.text.isEmpty() && parsedQuery.prefix != null) {
                                query = ""
                                true
                            } else {
                                false
                            }
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
                    prefix = parsedQuery.prefix,
                    text = parsedQuery.text,
                    onTextChange = { newText -> query = (parsedQuery.prefix ?: "") + newText },
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
                        items(
                            items = rows,
                            key = { row ->
                                when (row) {
                                    is PaletteRow.Header -> "h::${row.category}"
                                    is PaletteRow.Entry -> "e::${row.entry.category}::${row.entry.id}"
                                }
                            },
                        ) { row ->
                            when (row) {
                                is PaletteRow.Header -> CategoryHeader(row.category)

                                is PaletteRow.Entry -> EntryRow(
                                    entry = row.entry,
                                    isSelected = row.entryIndex == selected,
                                    onClick = {
                                        selected = row.entryIndex
                                        activateSelected()
                                    },
                                )
                            }
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
    prefix: String?,
    text: String,
    onTextChange: (String) -> Unit,
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
        if (prefix != null) {
            PrefixChip(prefix)
            Spacer(Modifier.width(8.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    "Jump to a screen, cluster, namespace, pod, or node…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KdTextSecondary,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
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
private fun PrefixChip(prefix: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = KdPrimary.copy(alpha = 0.15f),
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.labelSmall,
            color = KdPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
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
        // The header already names the category (D6) — the trailing slot is
        // reserved for the verb two-stage-flow hint instead (WS2).
        if (entry.id.startsWith("verb:")) {
            Text(
                "↵ to pick a target",
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
            )
        }
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

/**
 * Subsequence fuzzy scorer. Returns the number of gaps between matched
 * characters (lower = better match), or null if [query] is not a subsequence
 * of [text]. Case-insensitive.
 *
 * Example: "kbsy" scores 3 against "kube-system" (k|ube-|b|olt… wait —
 * k·ube-·b· e-·s·y·stem → 3 gaps), and null against "kube-proxy" (no 'y').
 */
private fun fuzzyScore(query: String, text: String): Int? {
    val q = query.lowercase()
    val t = text.lowercase()
    var qi = 0
    var gaps = 0
    for (ci in t.indices) {
        if (qi < q.length && t[ci] == q[qi]) {
            qi++
        } else if (qi > 0) {
            gaps++
        }
    }
    return if (qi == q.length) gaps else null
}
