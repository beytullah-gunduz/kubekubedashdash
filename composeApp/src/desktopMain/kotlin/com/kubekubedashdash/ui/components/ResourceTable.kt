package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary

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
)

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

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KdSurfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                            if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
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
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(sortedRows, key = { _, row -> row.id }) { index, row ->
                        TableRowItem(
                            row = row,
                            columns = columns,
                            isEven = index % 2 == 0,
                            isSelected = row.id == selectedRowId,
                            onClick = if (onRowClick != null) {
                                { onRowClick(row) }
                            } else {
                                null
                            },
                        )
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
    isEven: Boolean,
    isSelected: Boolean = false,
    onClick: (() -> Unit)?,
) {
    var hovered by remember { mutableStateOf(false) }
    val baseBg = when {
        isSelected -> KdSelected
        hovered -> KdHover
        isEven -> Color.Transparent
        else -> KdSurfaceVariant.copy(alpha = 0.3f)
    }
    val bg = if (row.backgroundColor != null && !isSelected && !hovered) {
        row.backgroundColor
    } else {
        baseBg
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEachIndexed { index, col ->
            val cell = row.cells.getOrNull(index)
            Box(modifier = if (col.width != null) Modifier.width(col.width) else Modifier.weight(col.weight ?: 1f)) {
                if (cell?.content != null) {
                    cell.content.invoke()
                } else {
                    Text(
                        text = cell?.text ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = cell?.color ?: KdTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
fun ResourceErrorMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Error", style = MaterialTheme.typography.titleMedium, color = KdError)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = KdTextSecondary)
        }
    }
}

@Composable
fun ResourceCountHeader(count: Int, kind: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            kind,
            style = MaterialTheme.typography.titleMedium,
            color = KdTextPrimary,
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = KdPrimary.copy(alpha = 0.15f),
        ) {
            Text(
                "$count",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = KdPrimary,
            )
        }
    }
}
