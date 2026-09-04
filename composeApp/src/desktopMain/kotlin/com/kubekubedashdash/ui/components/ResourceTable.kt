package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.arrow_downward_filled
import com.kubekubedashdash.resources.arrow_upward_filled
import com.kubekubedashdash.resources.check_filled
import com.kubekubedashdash.resources.star_filled
import com.kubekubedashdash.resources.star_outline
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

data class ColumnDef(
    val header: String,
    val weight: Float? = null,
    val width: Dp? = null,
    val headerExtra: (@Composable () -> Unit)? = null,
)

data class CellData(
    val text: String,
    val color: Color? = null,
    val sortValue: String? = null,
    val content: (@Composable () -> Unit)? = null,
)

/** kubectl's placeholder for an absent value; rendered as [EMPTY_DASH]. */
internal const val NONE_PLACEHOLDER = "<none>"
internal const val EMPTY_DASH = "—"

data class TableRow(
    val id: String,
    val cells: List<CellData>,
    val backgroundColor: Color? = null,
    /**
     * When set, `ResourceTable` synthesizes copy items at the top of this row's
     * menu — in BOTH the right-click menu and the hover `⋮` menu.
     */
    val identity: RowIdentity? = null,
    /**
     * Per-screen menu items for this row, appended AFTER the copy items that
     * `ResourceTable` synthesizes from [identity]. Put only screen-specific
     * actions here (view logs, open terminal, mutations) — copy-identity items
     * are the table's job, so every table gets them consistently. Mutations go
     * last: the menu has no separators, so order is the only safety signal.
     */
    val actions: List<RowAction> = emptyList(),
    /** Stable key used for pin persistence. Defaults to [id] when null. */
    val pinId: String? = null,
    /**
     * When false the row's checkbox renders disabled and inert and the row is
     * excluded from select-all / Cmd+A / shift-range selection (used for
     * departed/stale rows).
     */
    val selectable: Boolean = true,
)

/** A single entry in a row's right-click menu. */
data class RowAction(val label: String, val onSelect: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResourceTable(
    columns: List<ColumnDef>,
    rows: List<TableRow>,
    onRowClick: ((TableRow) -> Unit)? = null,
    selectedRowId: String? = null,
    emptyMessage: String = "No resources found",
    defaultSortColumn: Int = -1,
    /**
     * Initial sort column by NAME. Prefer this over [defaultSortColumn] for a
     * table whose columns are width-filtered: an index computed over the
     * filtered list is -1 on a narrow window, which silently means "unsorted".
     */
    defaultSortHeader: String? = null,
    defaultSortAscending: Boolean = true,
    identityHeader: String? = null,
    /**
     * Identifies this table for the per-table column-visibility preference and
     * enables the trailing options menu (column picker + density). Null (the
     * default) is today's behaviour: no menu, no hiding (D4).
     */
    tableKey: String? = null,
    scrollToTopOnChange: Boolean = false,
    pinnable: Boolean = false,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: ((String) -> Unit)? = null,
    /** When true, renders a leading checkbox column. Selection state is hoisted — see [selectedIds]. */
    selectable: Boolean = false,
    /** Hoisted checked-row id set; the table never stores selection itself. */
    selectedIds: Set<String> = emptySet(),
    /** Notified whenever the checked-row set changes. Only fires when [selectable] is true. */
    onSelectionChange: ((Set<String>) -> Unit)? = null,
) {
    // Resolved once, on first composition, per D5 — a positional index would
    // silently re-target when the responsive `columns` subset changes width.
    var sortHeader by remember { mutableStateOf(defaultSortHeader ?: columns.getOrNull(defaultSortColumn)?.header) }
    var sortAscending by remember { mutableStateOf(defaultSortAscending) }
    val copyToClipboard = rememberCopyToClipboard()

    // Collected once here, not inside TableRowItem, so a 1000-row list isn't
    // subscribed per row and non-skippable.
    val density by PreferenceRepository.tableDensity.collectAsState()
    val rowPadding = density.rowPadding
    val hiddenColumns by PreferenceRepository.hiddenTableColumns.collectAsState()

    // Full-list indices, never a filtered copy: the sort, defaultSortColumn
    // and hidden-column keys all speak positions/headers against the full
    // `columns` list, so a filtered `columns` copy would silently re-target
    // every one of them.
    val visibleIndices = remember(columns, tableKey, identityHeader, hiddenColumns) {
        visibleColumnIndices(columns, tableKey, identityHeader, hiddenColumns)
    }

    // A column you have hidden must not keep sorting the table from behind the
    // menu — that is the "invisible key" the header-based sort exists to avoid.
    val effectiveSortHeader = sortHeader?.takeIf { header -> visibleIndices.any { columns[it].header == header } }
    val sortedRows = remember(rows, columns, effectiveSortHeader, sortAscending, identityHeader, pinnedIds) {
        sortTableRows(
            rows = rows,
            columns = columns,
            sortHeader = effectiveSortHeader,
            ascending = sortAscending,
            identityHeader = identityHeader,
            pinnedIds = pinnedIds,
        )
    }

    val selectableRows = remember(sortedRows) { sortedRows.filter { it.selectable } }
    // Shift-range anchor tracked by row id (not index) so it survives the 5s
    // live-data refresh / re-sorts. Keying it on sortedRows reset it to -1 every
    // tick, so the first shift-click after a refresh degraded to a single toggle.
    var lastSelectedId by remember { mutableStateOf<String?>(null) }
    // Live modifier state at click time. Unlike key-event tracking, this does
    // not require the table to hold keyboard focus.
    val windowInfo = LocalWindowInfo.current

    // Shared by the checkbox and the Cmd/Ctrl+row-click paths so both extend
    // ranges from the same anchor. The anchor's CURRENT index is resolved by
    // id so a range stays correct across re-sorts / refreshes; -1 (anchor
    // gone) falls back to a single toggle.
    val toggleSelection: (TableRow, Int, Boolean) -> Unit = { row, index, extendRange ->
        val anchorIndex = lastSelectedId?.let { id -> sortedRows.indexOfFirst { it.id == id } } ?: -1
        val newIds = if (extendRange && anchorIndex >= 0) {
            val start = minOf(anchorIndex, index)
            val end = maxOf(anchorIndex, index)
            val rangeIds = sortedRows.slice(start..end).filter { it.selectable }.map { it.id }.toSet()
            if (row.id in selectedIds) selectedIds - rangeIds else selectedIds + rangeIds
        } else {
            if (row.id in selectedIds) selectedIds - row.id else selectedIds + row.id
        }
        lastSelectedId = row.id
        onSelectionChange?.invoke(newIds)
    }

    var keyboardIndex by remember(sortedRows) { mutableStateOf(-1) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var hasAutoFocused by remember { mutableStateOf(false) }
    LaunchedEffect(sortedRows.isNotEmpty()) {
        if (sortedRows.isNotEmpty() && !hasAutoFocused) {
            hasAutoFocused = true
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KdSurfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectable) {
                val allSelected = selectableRows.isNotEmpty() && selectableRows.all { it.id in selectedIds }
                Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    // Dp.Unspecified drops the 48dp touch-target minimum, which
                    // would otherwise dictate the row height of the whole table
                    // (this is a pointer-driven desktop grid, not a touch UI).
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                onSelectionChange?.invoke(if (checked) selectableRows.map { it.id }.toSet() else emptySet())
                            },
                            // Checkbox hard-codes a 20dp glyph (requiredSize), so a
                            // size modifier can't shrink it — scale it to sit next
                            // to bodySmall text without dominating the row.
                            modifier = Modifier.scale(0.75f),
                        )
                    }
                }
            }
            if (pinnable) Spacer(Modifier.width(30.dp))
            visibleIndices.forEach { index ->
                val col = columns[index]
                Row(
                    modifier = Modifier
                        .then(if (col.width != null) Modifier.width(col.width) else Modifier.weight(col.weight ?: 1f))
                        .clickable {
                            if (sortHeader == col.header) {
                                sortAscending = !sortAscending
                            } else {
                                sortHeader = col.header
                                sortAscending = true
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        col.header,
                        style = MaterialTheme.typography.labelMedium,
                        color = KdTextSecondary,
                        maxLines = 1,
                    )
                    if (sortHeader == col.header) {
                        Icon(
                            painterResource(if (sortAscending) Res.drawable.arrow_upward_filled else Res.drawable.arrow_downward_filled),
                            contentDescription = if (sortAscending) "Sorted ascending" else "Sorted descending",
                            modifier = Modifier.size(12.dp).padding(start = 2.dp),
                            tint = KdPrimary,
                        )
                    }
                    col.headerExtra?.invoke()
                }
            }
            // D9: the 24dp trailing slot is unconditional in the header AND
            // every row so the two stay in register; only the options `⋮`
            // inside is gated on tableKey != null.
            var optionsExpanded by remember { mutableStateOf(false) }
            Box(
                // The whole slot is the hit target, not just the glyph: this is
                // the only way into every control in the menu.
                modifier = Modifier
                    .width(24.dp)
                    .then(if (tableKey != null) Modifier.clickable { optionsExpanded = true } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (tableKey != null) {
                    Text(
                        text = "⋮",
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextSecondary,
                    )
                    DropdownMenu(expanded = optionsExpanded, onDismissRequest = { optionsExpanded = false }) {
                        Text(
                            "Columns",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        val identityIndex = identityColumnIndex(columns, identityHeader)
                        columns.indices.filter { it != identityIndex }.forEach { i ->
                            val col = columns[i]
                            val entryKey = tableColumnKey(tableKey, columns, i)
                            val hiddenNow = hiddenColumns[entryKey] == true
                            val locked = isLastVisibleColumn(columns, tableKey, identityHeader, hiddenColumns, i)
                            DropdownMenuItem(
                                enabled = !locked,
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = !hiddenNow,
                                            enabled = !locked,
                                            onCheckedChange = { checked ->
                                                PreferenceRepository.setTableColumnHidden(entryKey, !checked)
                                            },
                                        )
                                        Text(col.header, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                onClick = { PreferenceRepository.setTableColumnHidden(entryKey, !hiddenNow) },
                            )
                        }
                        HorizontalDivider()
                        Text(
                            "Density",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        TableDensity.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                            if (density == option) {
                                                Icon(
                                                    painterResource(Res.drawable.check_filled),
                                                    contentDescription = null,
                                                    tint = KdPrimary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (option == TableDensity.Comfortable) "Comfortable" else "Compact",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = { PreferenceRepository.setTableDensity(option) },
                            )
                        }
                    }
                }
            }
        }

        if (sortedRows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = KdTextSecondary)
            }
        } else {
            val lazyListState = rememberLazyListState()
            // Bring an externally-set selection into view (jump-to-pod and
            // friends land with the row far offscreen on long lists). Keyed on
            // the id, not just the rows: snapshots arrive continuously and
            // re-scrolling on each would yank the user back while they browse.
            // A row selected by clicking it is already visible, so the
            // fully-in-viewport check makes that case a no-op.
            var scrolledToId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(selectedRowId, sortedRows) {
                val id = selectedRowId
                if (id == null || id == scrolledToId) return@LaunchedEffect
                val index = sortedRows.indexOfFirst { it.id == id }
                if (index < 0) return@LaunchedEffect // not in this snapshot yet; retry on the next
                val info = lazyListState.layoutInfo
                val fullyVisible = info.visibleItemsInfo.any {
                    it.index == index &&
                        it.offset >= info.viewportStartOffset &&
                        it.offset + it.size <= info.viewportEndOffset
                }
                if (!fullyVisible) lazyListState.animateScrollToItem(index)
                scrolledToId = id
            }
            if (scrollToTopOnChange) {
                var previousSize by remember { mutableStateOf(sortedRows.size) }
                LaunchedEffect(sortedRows) {
                    val sizeDiff = sortedRows.size - previousSize
                    if (sizeDiff > 0 && lazyListState.firstVisibleItemIndex <= sizeDiff) {
                        lazyListState.animateScrollToItem(0)
                    }
                    previousSize = sortedRows.size
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (event.isMetaPressed || event.isCtrlPressed) {
                                        // Cmd+Down on macOS = jump to end. Instant scroll
                                        // (not animated) so it stays snappy on long lists.
                                        keyboardIndex = sortedRows.lastIndex
                                        coroutineScope.launch { lazyListState.scrollToItem(sortedRows.lastIndex) }
                                    } else {
                                        keyboardIndex = if (keyboardIndex < 0) {
                                            0
                                        } else {
                                            (keyboardIndex + 1).coerceAtMost(sortedRows.lastIndex)
                                        }
                                        coroutineScope.launch { lazyListState.animateScrollToItem(keyboardIndex) }
                                    }
                                    true
                                }

                                Key.DirectionUp -> {
                                    if (event.isMetaPressed || event.isCtrlPressed) {
                                        // Cmd+Up on macOS = jump to top.
                                        keyboardIndex = 0
                                        coroutineScope.launch { lazyListState.scrollToItem(0) }
                                    } else {
                                        if (keyboardIndex > 0) {
                                            keyboardIndex -= 1
                                        } else if (keyboardIndex < 0) {
                                            keyboardIndex = 0
                                        }
                                        coroutineScope.launch { lazyListState.animateScrollToItem(maxOf(0, keyboardIndex)) }
                                    }
                                    true
                                }

                                Key.MoveHome -> {
                                    keyboardIndex = 0
                                    coroutineScope.launch { lazyListState.scrollToItem(0) }
                                    true
                                }

                                Key.MoveEnd -> {
                                    keyboardIndex = sortedRows.lastIndex
                                    coroutineScope.launch { lazyListState.scrollToItem(sortedRows.lastIndex) }
                                    true
                                }

                                Key.Enter -> {
                                    if (keyboardIndex >= 0 && onRowClick != null) {
                                        onRowClick(sortedRows[keyboardIndex])
                                        keyboardIndex = -1
                                        true
                                    } else {
                                        false
                                    }
                                }

                                Key.Escape -> when {
                                    keyboardIndex >= 0 -> {
                                        keyboardIndex = -1
                                        true
                                    }

                                    selectable && selectedIds.isNotEmpty() -> {
                                        onSelectionChange?.invoke(emptySet())
                                        true
                                    }

                                    else -> false
                                }

                                Key.A -> if (selectable && (event.isMetaPressed || event.isCtrlPressed)) {
                                    val allIds = selectableRows.map { it.id }.toSet()
                                    onSelectionChange?.invoke(allIds)
                                    true
                                } else {
                                    false
                                }

                                Key.C -> if (event.isMetaPressed || event.isCtrlPressed) {
                                    // Mirror the right-click rule: the keyboard cursor wins
                                    // unless it is itself part of the checkbox selection;
                                    // otherwise the selection; otherwise the open row.
                                    // `false` on the no-target path so the key is not swallowed.
                                    val cursor = sortedRows.getOrNull(keyboardIndex)
                                    // isNotEmpty(), not size > 1: a single ticked checkbox is
                                    // still an explicit selection, and with no keyboard cursor
                                    // and no open row it was previously copying nothing at all.
                                    val useSelection =
                                        selectedIds.isNotEmpty() && (cursor == null || cursor.id in selectedIds)
                                    val targets = when {
                                        useSelection ->
                                            sortedRows.filter { it.id in selectedIds }.mapNotNull { it.identity }

                                        cursor != null -> listOfNotNull(cursor.identity)

                                        else -> listOfNotNull(
                                            sortedRows.firstOrNull { it.id == selectedRowId }?.identity,
                                        )
                                    }
                                    if (targets.isEmpty()) {
                                        false
                                    } else {
                                        copyToClipboard(
                                            targets.joinToString("\n") { it.name },
                                            if (targets.size == 1) "Copied" else "Copied ${targets.size} names",
                                        )
                                        true
                                    }
                                } else {
                                    false
                                }

                                else -> false
                            }
                        }
                        .kdFocusRing(),
                ) {
                    itemsIndexed(sortedRows, key = { _, row -> row.id }) { index, row ->
                        // A1/A5: ONE menu, TWO renderers. `hasMenu` is the cheap
                        // composition-time gate; `buildMenu` is evaluated only at
                        // open time by both renderers.
                        val hasMenu = row.identity != null || row.actions.isNotEmpty()
                        val buildMenu: () -> List<RowAction> = {
                            val multi = row.id in selectedIds && selectedIds.size > 1
                            val targets = if (multi) {
                                sortedRows.filter { it.id in selectedIds }.mapNotNull { it.identity }
                            } else {
                                listOfNotNull(row.identity)
                            }
                            // A6: in multi mode the row's own actions are clicked-row-scoped while
                            // the copies are selection-scoped; mixing them in a separator-less menu
                            // invites deleting one pod when the user meant five. Bulk mutations live
                            // in the bulk-selection bar, so multi mode shows copies only.
                            copyRowActions(targets, copyToClipboard) + if (multi) emptyList() else row.actions
                        }
                        val rowItem: @Composable () -> Unit = {
                            TableRowItem(
                                row = row,
                                columns = columns,
                                visibleIndices = visibleIndices,
                                rowPadding = rowPadding,
                                isEven = index % 2 == 0,
                                isSelected = row.id == selectedRowId,
                                isCursor = index == keyboardIndex,
                                onClick = if (onRowClick != null || selectable) {
                                    {
                                        val mods = windowInfo.keyboardModifiers
                                        if (selectable && (mods.isMetaPressed || mods.isCtrlPressed)) {
                                            // Cmd/Ctrl+click toggles selection instead of opening
                                            // the row; +Shift extends from the checkbox anchor.
                                            // Inert on non-selectable (stale) rows: the intent was
                                            // selection, so don't open the detail panel either.
                                            if (row.selectable) toggleSelection(row, index, mods.isShiftPressed)
                                        } else if (onRowClick != null) {
                                            keyboardIndex = -1
                                            onRowClick(row)
                                        }
                                    }
                                } else {
                                    null
                                },
                                pinnable = pinnable,
                                isPinned = (row.pinId ?: row.id) in pinnedIds,
                                onTogglePin = if (onTogglePin != null) {
                                    { onTogglePin(row.pinId ?: row.id) }
                                } else {
                                    null
                                },
                                selectable = selectable,
                                isChecked = row.id in selectedIds,
                                checkEnabled = row.selectable,
                                onSelectClick = if (selectable) {
                                    { toggleSelection(row, index, windowInfo.keyboardModifiers.isShiftPressed) }
                                } else {
                                    null
                                },
                                hasMenu = hasMenu,
                                buildMenu = buildMenu,
                            )
                        }
                        if (!hasMenu) {
                            rowItem()
                        } else {
                            ContextMenuArea(
                                items = { buildMenu().map { ContextMenuItem(it.label, it.onSelect) } },
                                content = rowItem,
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableRowItem(
    row: TableRow,
    columns: List<ColumnDef>,
    /** Indices into [columns] to render, in order — never a filtered copy of [columns]. */
    visibleIndices: List<Int>,
    rowPadding: Dp,
    @Suppress("UNUSED_PARAMETER") isEven: Boolean,
    isSelected: Boolean = false,
    isCursor: Boolean = false,
    onClick: (() -> Unit)?,
    pinnable: Boolean = false,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    selectable: Boolean = false,
    isChecked: Boolean = false,
    checkEnabled: Boolean = true,
    onSelectClick: (() -> Unit)? = null,
    hasMenu: Boolean = false,
    buildMenu: () -> List<RowAction> = { emptyList() },
) {
    var hovered by remember { mutableStateOf(false) }
    // Switched from zebra striping to a hairline border between rows.
    // The previous striping used 30% KdSurfaceVariant which read as
    // near-solid on dark theme and inconsistent on light, so the rows
    // had no clear separation either way.
    // Three states, not one: the open (selected) row keeps its tint under the
    // pointer and gets a leading accent stripe; the keyboard-cursor row shares
    // the hover tint but gets a muted stripe. Before this, selected and cursor
    // shared one colour with hover, so the row you had opened disappeared under
    // the row you were pointing at.
    val baseBg = when {
        isSelected -> KdSelected
        isCursor || hovered -> KdHover
        else -> Color.Transparent
    }
    val bg = if (row.backgroundColor != null && !isSelected && !isCursor && !hovered) {
        row.backgroundColor
    } else {
        baseBg
    }
    val separatorColor = KdBorder.copy(alpha = 0.6f)
    val stripeColor = when {
        isSelected -> KdPrimary
        isCursor -> KdTextSecondary
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .drawBehind {
                val strokeWidth = 1f
                drawLine(
                    color = separatorColor,
                    start = Offset(0f, size.height - strokeWidth / 2),
                    end = Offset(size.width, size.height - strokeWidth / 2),
                    strokeWidth = strokeWidth,
                )
                if (stripeColor != null) {
                    drawRect(color = stripeColor, size = Size(3.dp.toPx(), size.height))
                }
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 16.dp, vertical = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            // A disabled Checkbox does not consume its click, so it would fall through
            // to the row's own `clickable` and open the detail panel. When checkEnabled
            // is false, make the column inert instead of relying on the disabled state.
            Box(
                modifier = Modifier.width(30.dp)
                    .then(
                        if (!checkEnabled) {
                            Modifier.clickable(enabled = true, indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Same 48dp-minimum opt-out as the header checkbox: without it
                // every row inflates from text height to touch-target height.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Checkbox(
                        checked = isChecked,
                        enabled = checkEnabled,
                        onCheckedChange = { onSelectClick?.invoke() },
                        // Same 0.75 scale as the header checkbox (20dp glyph is
                        // requiredSize-locked; scaling is the only way down).
                        modifier = Modifier.scale(0.75f),
                    )
                }
            }
        }
        if (pinnable) {
            Box(
                modifier = Modifier.width(30.dp).clickable(enabled = onTogglePin != null) { onTogglePin?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (isPinned) Res.drawable.star_filled else Res.drawable.star_outline),
                    contentDescription = if (isPinned) "Unpin" else "Pin",
                    modifier = Modifier.size(14.dp),
                    tint = if (isPinned) KdPrimary else KdTextSecondary.copy(alpha = if (hovered) 0.6f else 0.25f),
                )
            }
        }
        visibleIndices.forEach { index ->
            val col = columns[index]
            val cell = row.cells.getOrNull(index)
            Box(modifier = if (col.width != null) Modifier.width(col.width) else Modifier.weight(col.weight ?: 1f)) {
                if (cell?.content != null) {
                    cell.content.invoke()
                } else {
                    // "<none>" reads as data; show a muted dash instead. Sort and
                    // search still see the raw value (CellData is untouched).
                    val isNone = cell?.text == NONE_PLACEHOLDER
                    OverflowTooltipText(
                        text = if (isNone) EMPTY_DASH else cell?.text ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isNone) KdTextSecondary else cell?.color ?: KdTextPrimary,
                    )
                }
            }
        }
        // D9: the 24dp trailing slot is unconditional so header and body stay
        // in register; a row with no menu renders the empty box.
        var menuExpanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.width(24.dp)
                .then(
                    if (hasMenu) {
                        Modifier.clickable { menuExpanded = true }.pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (hasMenu) {
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.bodyLarge,
                    color = KdTextSecondary.copy(alpha = if (hovered || menuExpanded) 1f else 0.3f),
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // Called HERE, never in the row body: DropdownMenu does not
                    // compose its content while collapsed, so this keeps the
                    // menu build lazy for the ⋮ path too.
                    val items = buildMenu()
                    if (items.isEmpty()) {
                        // Defer the close: writing menuExpanded during composition
                        // is a Compose state-write hazard. Unreachable once every
                        // selectable table sets `identity`, but cheap to fence.
                        LaunchedEffect(Unit) { menuExpanded = false }
                    } else {
                        items.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label, style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    menuExpanded = false
                                    action.onSelect()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResourceLoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = KdPrimary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
fun SkeletonRows(rowCount: Int = 6) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rowCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(0.4f).height(12.dp).background(KdSurfaceVariant.copy(alpha = alpha)))
                Box(Modifier.weight(0.15f).height(12.dp).background(KdSurfaceVariant.copy(alpha = alpha)))
                Box(Modifier.weight(0.15f).height(12.dp).background(KdSurfaceVariant.copy(alpha = alpha)))
                Box(Modifier.weight(0.1f).height(12.dp).background(KdSurfaceVariant.copy(alpha = alpha)))
                Box(Modifier.weight(0.2f).height(12.dp).background(KdSurfaceVariant.copy(alpha = alpha)))
            }
        }
    }
}

@Composable
fun ResourceErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    val copyToClipboard = rememberCopyToClipboard()
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Error", style = MaterialTheme.typography.titleMedium, color = KdError)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = KdTextSecondary)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) {
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
                        border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(KdBorder)),
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(
                    onClick = { copyToClipboard(message) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(KdBorder)),
                ) {
                    Text("Copy error", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OverflowTooltipText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    var overflowed by remember(text) { mutableStateOf(false) }

    val label = @Composable {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> overflowed = result.hasVisualOverflow },
        )
    }

    if (overflowed && text.isNotEmpty()) {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = KdSurface,
                    shadowElevation = 4.dp,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextPrimary,
                    )
                }
            },
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
            content = label,
        )
    } else {
        label()
    }
}

/**
 * Page-list header: kind title + count chip, optionally with trailing
 * actions on the right (filters, refresh, etc.). Lives directly above
 * the resource table.
 */
@Composable
fun ResourceCountHeader(
    count: Int,
    kind: String,
    liveDot: @Composable () -> Unit = {},
    actions: (@Composable RowScope.(compact: Boolean) -> Unit)? = null,
) {
    Column {
        BoxWithConstraints {
            val compact = maxWidth < 480.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    kind,
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                )
                liveDot()
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KdPrimary.copy(alpha = 0.15f),
                ) {
                    Text(
                        formatCount(count),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = KdPrimary,
                    )
                }
                if (actions != null) {
                    Spacer(Modifier.weight(1f))
                    actions(compact)
                }
            }
        }
        HorizontalDivider(color = KdBorder, thickness = 1.dp)
    }
}

private fun formatCount(n: Int): String = "%,d".format(n)
