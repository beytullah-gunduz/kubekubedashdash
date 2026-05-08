package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.chevron_right_filled
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.Sparkline
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.components.kdFocusRing
import com.kubekubedashdash.ui.screens.events.EventColumn
import com.kubekubedashdash.ui.screens.events.EventTypeIcon
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Aggregated events table for the AllClusters view. Supports both Raw and Grouped
 * view modes, and shows an empty-state prompt when filters reduce to zero results.
 */
@Composable
internal fun AllClustersEventsTable(
    events: List<EventInfo>,
    groupedEvents: List<EventGroup>,
    mode: ViewMode,
    hasActiveFilters: Boolean,
    onClearFilters: () -> Unit,
) {
    BoxWithConstraints {
        val tableWidth = maxWidth

        when {
            events.isEmpty() && hasActiveFilters -> EmptyFilterState(onClearFilters)
            events.isEmpty() -> EmptyFilterState(onClearFilters = null)
            mode == ViewMode.RAW -> RawEventsTable(events = events, tableWidth = tableWidth)
            else -> GroupedEventsTable(groups = groupedEvents, tableWidth = tableWidth)
        }
    }
}

// ── Raw mode ─────────────────────────────────────────────────────────────────

@Composable
private fun RawEventsTable(events: List<EventInfo>, tableWidth: Dp) {
    val allColumns = buildRawColumns()
    val visibleColumns = allColumns.filter { it.minTableWidth == null || tableWidth >= it.minTableWidth }
    val columns = visibleColumns.map { it.def }
    val defaultSortIndex = visibleColumns.indexOfFirst { it.def.header == "Last Seen" }

    val rows = events.map { ev ->
        val rowBg = rowBackground(ev.type)
        TableRow(
            id = "${ev.sessionId.orEmpty()}:${ev.uid}",
            cells = visibleColumns.map { it.cell(ev) },
            backgroundColor = rowBg,
        )
    }

    ResourceTable(
        columns = columns,
        rows = rows,
        onRowClick = null,
        selectedRowId = null,
        emptyMessage = "No events found",
        defaultSortColumn = defaultSortIndex,
        defaultSortAscending = false,
        scrollToTopOnChange = true,
    )
}

// ── Grouped mode ──────────────────────────────────────────────────────────────

@Composable
private fun GroupedEventsTable(groups: List<EventGroup>, tableWidth: Dp) {
    val expandedKeys = remember { mutableStateSetOf<GroupKey>() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(groups.isNotEmpty()) {
        if (groups.isNotEmpty()) runCatching { focusRequester.requestFocus() }
    }

    val rawColumns = buildRawColumns()
    val visibleRaw = rawColumns.filter { it.minTableWidth == null || tableWidth >= it.minTableWidth }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header row (mirrors ResourceTable header style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KdSurfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chevron spacer (matches the 16dp icon in GroupHeaderRow so column
            // headers align with the data cells underneath)
            Box(modifier = Modifier.width(16.dp))
            GroupedColumnHeader("Type", Modifier.width(50.dp))
            GroupedColumnHeader("Reason", Modifier.width(150.dp))
            if (tableWidth >= 800.dp) GroupedColumnHeader("Object", Modifier.weight(1.5f))
            GroupedColumnHeader("Message", Modifier.weight(3f))
            if (tableWidth >= 1100.dp) GroupedColumnHeader("Count", Modifier.weight(0.2f))
            if (tableWidth >= 950.dp) GroupedColumnHeader("Last Seen", Modifier.weight(0.35f))
            if (tableWidth >= 650.dp) GroupedColumnHeader("Clusters", Modifier.weight(1f))
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // Total item count includes group headers + expanded
                        // members; ask the LazyColumn directly so we don't have
                        // to recompute it from `groups` and `expandedKeys`.
                        val lastIndex = listState.layoutInfo.totalItemsCount - 1
                        when (event.key) {
                            Key.MoveHome -> {
                                coroutineScope.launch { listState.scrollToItem(0) }
                                true
                            }

                            Key.MoveEnd -> {
                                if (lastIndex >= 0) {
                                    coroutineScope.launch { listState.scrollToItem(lastIndex) }
                                }
                                true
                            }

                            Key.DirectionUp -> if (event.isMetaPressed) {
                                coroutineScope.launch { listState.scrollToItem(0) }
                                true
                            } else {
                                false
                            }

                            Key.DirectionDown -> if (event.isMetaPressed) {
                                if (lastIndex >= 0) {
                                    coroutineScope.launch { listState.scrollToItem(lastIndex) }
                                }
                                true
                            } else {
                                false
                            }

                            else -> false
                        }
                    }
                    .kdFocusRing(),
            ) {
                groups.forEach { group ->
                    val isExpanded = group.key in expandedKeys
                    item(key = group.key) {
                        GroupHeaderRow(
                            group = group,
                            isExpanded = isExpanded,
                            tableWidth = tableWidth,
                            onClick = {
                                if (isExpanded) expandedKeys.remove(group.key) else expandedKeys.add(group.key)
                            },
                        )
                    }
                    if (isExpanded) {
                        items(group.members, key = { ev -> "member:${ev.sessionId.orEmpty()}:${ev.uid}" }) { ev ->
                            MemberRow(ev = ev, tableWidth = tableWidth, visibleColumns = visibleRaw)
                        }
                    }
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
private fun GroupedColumnHeader(header: String, modifier: Modifier) {
    Text(
        header,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = KdTextSecondary,
        maxLines = 1,
    )
}

@Composable
private fun GroupHeaderRow(
    group: EventGroup,
    isExpanded: Boolean,
    tableWidth: Dp,
    onClick: () -> Unit,
) {
    val bg = rowBackground(group.key.type)
    val borderColor = KdBorder.copy(alpha = 0.6f)
    val isSingleMember = group.members.size == 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg ?: androidx.compose.ui.graphics.Color.Transparent)
            .let { if (isSingleMember) it else it.clickable(onClick = onClick) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSingleMember) {
            Box(modifier = Modifier.size(16.dp))
        } else {
            Icon(
                painterResource(Res.drawable.chevron_right_filled),
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (isExpanded) 90f else 0f),
                tint = KdTextSecondary,
            )
        }

        Box(modifier = Modifier.width(50.dp)) {
            EventTypeIcon(group.key.type)
        }
        Box(modifier = Modifier.width(150.dp)) {
            if (!isSingleMember) {
                Sparkline(
                    buckets = group.bucketHistogram,
                    modifier = Modifier.matchParentSize(),
                    color = KdPrimary,
                )
            }
            CellText(group.key.reason)
        }
        if (tableWidth >= 800.dp) {
            Box(modifier = Modifier.weight(1.5f)) {
                CellText(group.key.objectRef, color = KdPrimary)
            }
        }
        Box(modifier = Modifier.weight(3f)) {
            CellText(group.latestMessage)
        }
        if (tableWidth >= 1100.dp) {
            Box(modifier = Modifier.weight(0.2f)) {
                CellText("${group.totalCount}")
            }
        }
        if (tableWidth >= 950.dp) {
            Box(modifier = Modifier.weight(0.35f)) {
                CellText(group.lastSeen)
            }
        }
        if (tableWidth >= 650.dp) {
            Box(modifier = Modifier.weight(1f)) {
                val omitCount = isSingleMember && group.perClusterCounts.size == 1
                val summary = group.perClusterCounts.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { (cluster, count) ->
                        val name = cluster.substringAfterLast("/").take(12)
                        if (omitCount) name else "$name ($count)"
                    }
                CellText(summary)
            }
        }
    }
}

@Composable
private fun MemberRow(
    ev: EventInfo,
    tableWidth: Dp,
    visibleColumns: List<EventColumn>,
) {
    val bg = rowBackground(ev.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg ?: androidx.compose.ui.graphics.Color.Transparent)
            .padding(start = 40.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleColumns.forEach { col ->
            val cell = col.cell(ev)
            Box(
                modifier = if (col.def.width != null) Modifier.width(col.def.width) else Modifier.weight(col.def.weight ?: 1f),
            ) {
                if (cell.content != null) {
                    cell.content.invoke()
                } else {
                    CellText(cell.text, color = cell.color)
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyFilterState(onClearFilters: (() -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (onClearFilters != null) "No events match — try adjusting filters" else "No events",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
            )
            if (onClearFilters != null) {
                Button(onClick = onClearFilters) {
                    Text("Clear filters")
                }
            }
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun CellText(
    text: String,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: KdTextPrimary,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

private fun rowBackground(type: String) = when (type) {
    "Warning" -> KdWarning.copy(alpha = 0.10f)
    "Error" -> KdError.copy(alpha = 0.10f)
    else -> null
}

private fun buildRawColumns() = listOf(
    EventColumn(
        def = ColumnDef(header = "Type", width = 50.dp),
        cell = { ev -> CellData(text = ev.type, content = { EventTypeIcon(ev.type) }) },
    ),
    EventColumn(
        def = ColumnDef("Reason", width = 150.dp),
        cell = { ev -> CellData(ev.reason) },
    ),
    EventColumn(
        def = ColumnDef("Object", weight = 1.5f),
        cell = { ev -> CellData(ev.objectRef, KdPrimary) },
        minTableWidth = 800.dp,
    ),
    EventColumn(
        def = ColumnDef("Message", weight = 3f),
        cell = { ev -> CellData(ev.message) },
    ),
    EventColumn(
        def = ColumnDef("Count", weight = 0.2f),
        cell = { ev -> CellData("${ev.count}", sortValue = ev.count.toString().padStart(10, '0')) },
        minTableWidth = 1100.dp,
    ),
    EventColumn(
        def = ColumnDef("Last Seen", weight = 0.35f),
        cell = { ev -> CellData(ev.lastSeen, sortValue = ev.lastSeenTimestamp) },
        minTableWidth = 950.dp,
    ),
    EventColumn(
        def = ColumnDef("Cluster", width = 140.dp),
        cell = { ev -> CellData(ev.cluster ?: "—") },
        minTableWidth = 650.dp,
    ),
    EventColumn(
        def = ColumnDef("Node", weight = 1f),
        cell = { ev -> CellData(ev.node.ifEmpty { "-" }) },
        minTableWidth = 800.dp,
    ),
    EventColumn(
        def = ColumnDef("Namespace", width = 100.dp),
        cell = { ev -> CellData(ev.namespace) },
        minTableWidth = 650.dp,
    ),
)
