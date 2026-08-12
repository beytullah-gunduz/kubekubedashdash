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
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
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
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.arrow_downward_filled
import com.kubekubedashdash.resources.arrow_upward_filled
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

data class TableRow(
    val id: String,
    val cells: List<CellData>,
    val backgroundColor: Color? = null,
    /**
     * Right-click / Ctrl-click menu items for this row. When non-empty,
     * `ResourceTable` wraps the row in a [ContextMenuArea]. Keep this list
     * short — typically 2–4 items — and start with the safe ones (copy
     * name, copy a `kubectl` command). Mutations belong in their own pass.
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
    defaultSortAscending: Boolean = true,
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
    var sortColumn by remember { mutableStateOf(defaultSortColumn) }
    var sortAscending by remember { mutableStateOf(defaultSortAscending) }

    val sortedRows = remember(rows, sortColumn, sortAscending) {
        if (sortColumn < 0 || sortColumn >= columns.size) {
            rows
        } else {
            val sorted = rows.sortedBy { row ->
                val cell = row.cells.getOrNull(sortColumn)
                cell?.sortValue ?: cell?.text ?: ""
            }
            if (sortAscending) sorted else sorted.reversed()
        }
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
            columns.forEachIndexed { index, col ->
                Row(
                    modifier = Modifier
                        .then(if (col.width != null) Modifier.width(col.width) else Modifier.weight(col.weight ?: 1f))
                        .clickable {
                            if (sortColumn == index) {
                                sortAscending = !sortAscending
                            } else {
                                sortColumn = index
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
                    if (sortColumn == index) {
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
                                    if (event.isMetaPressed) {
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
                                    if (event.isMetaPressed) {
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

                                else -> false
                            }
                        }
                        .kdFocusRing(),
                ) {
                    itemsIndexed(sortedRows, key = { _, row -> row.id }) { index, row ->
                        val rowItem: @Composable () -> Unit = {
                            TableRowItem(
                                row = row,
                                columns = columns,
                                isEven = index % 2 == 0,
                                isSelected = row.id == selectedRowId || index == keyboardIndex,
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
                            )
                        }
                        if (row.actions.isEmpty()) {
                            rowItem()
                        } else {
                            ContextMenuArea(
                                items = {
                                    row.actions.map { action ->
                                        ContextMenuItem(action.label, action.onSelect)
                                    }
                                },
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
    @Suppress("UNUSED_PARAMETER") isEven: Boolean,
    isSelected: Boolean = false,
    onClick: (() -> Unit)?,
    pinnable: Boolean = false,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    selectable: Boolean = false,
    isChecked: Boolean = false,
    checkEnabled: Boolean = true,
    onSelectClick: (() -> Unit)? = null,
) {
    var hovered by remember { mutableStateOf(false) }
    // Switched from zebra striping to a hairline border between rows.
    // The previous striping used 30% KdSurfaceVariant which read as
    // near-solid on dark theme and inconsistent on light, so the rows
    // had no clear separation either way.
    val baseBg = when {
        isSelected -> KdSelected
        hovered -> KdHover
        else -> Color.Transparent
    }
    val bg = if (row.backgroundColor != null && !isSelected && !hovered) {
        row.backgroundColor
    } else {
        baseBg
    }
    val separatorColor = KdBorder.copy(alpha = 0.6f)

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
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 16.dp, vertical = 7.dp),
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
        columns.forEachIndexed { index, col ->
            val cell = row.cells.getOrNull(index)
            Box(modifier = if (col.width != null) Modifier.width(col.width) else Modifier.weight(col.weight ?: 1f)) {
                if (cell?.content != null) {
                    cell.content.invoke()
                } else {
                    OverflowTooltipText(
                        text = cell?.text ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = cell?.color ?: KdTextPrimary,
                    )
                }
            }
        }
        if (row.actions.isNotEmpty()) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.bodyLarge,
                    color = KdTextSecondary.copy(alpha = if (hovered || menuExpanded) 1f else 0.3f),
                    modifier = Modifier.padding(horizontal = 4.dp).clickable { menuExpanded = true },
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    row.actions.forEach { action ->
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
