package com.kubekubedashdash.ui.screens.events

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow

@Composable
internal fun EventTable(
    events: List<EventInfo>,
    availableTypes: Set<String>,
    selectedTypes: Set<String>,
    onToggleType: (String) -> Unit,
    onSelectAllTypes: () -> Unit,
    onSelectNoTypes: () -> Unit,
    availableNodes: Set<String>,
    selectedNodes: Set<String>,
    onToggleNode: (String) -> Unit,
    onSelectAllNodes: () -> Unit,
    onSelectNoNodes: () -> Unit,
    selectedUid: String? = null,
    onEventClick: ((EventInfo) -> Unit)? = null,
) {
    var showTypeFilter by remember { mutableStateOf(false) }
    var showNodeFilter by remember { mutableStateOf(false) }
    val allTypesSelected = selectedTypes.size == availableTypes.size
    val allNodesSelected = selectedNodes.size == availableNodes.size

    BoxWithConstraints {
        val tableWidth = maxWidth

        val allColumns = listOf(
            EventColumn(
                def = ColumnDef(
                    header = "Type",
                    width = 50.dp,
                    headerExtra = {
                        ColumnFilterDropdown(
                            expanded = showTypeFilter,
                            active = !allTypesSelected,
                            onToggle = { showTypeFilter = !showTypeFilter },
                            onDismiss = { showTypeFilter = false },
                            availableValues = availableTypes,
                            selectedValues = selectedTypes,
                            onToggleValue = onToggleType,
                            onSelectAll = onSelectAllTypes,
                            onSelectNone = onSelectNoTypes,
                        )
                    },
                ),
                cell = { ev ->
                    CellData(
                        text = ev.type,
                        content = { EventTypeIcon(ev.type) },
                    )
                },
            ),
            EventColumn(
                def = ColumnDef("Reason", width = 150.dp),
                cell = { ev -> CellData(ev.reason) },
            ),
            EventColumn(
                def = ColumnDef("Object", 1.5f),
                cell = { ev -> CellData(ev.objectRef, KdPrimary) },
                minTableWidth = 800.dp,
            ),
            EventColumn(
                def = ColumnDef("Message", 3f),
                cell = { ev -> CellData(ev.message) },
            ),
            EventColumn(
                def = ColumnDef("Count", 0.2f),
                cell = { ev -> CellData("${ev.count}", sortValue = ev.count.toString().padStart(10, '0')) },
                minTableWidth = 1100.dp,
            ),
            EventColumn(
                def = ColumnDef("Last Seen", 0.35f),
                cell = { ev -> CellData(ev.lastSeen, sortValue = ev.lastSeenTimestamp) },
                minTableWidth = 950.dp,
            ),
            EventColumn(
                def = ColumnDef(
                    header = "Node",
                    weight = 1f,
                    headerExtra = {
                        ColumnFilterDropdown(
                            expanded = showNodeFilter,
                            active = !allNodesSelected,
                            onToggle = { showNodeFilter = !showNodeFilter },
                            onDismiss = { showNodeFilter = false },
                            availableValues = availableNodes,
                            selectedValues = selectedNodes,
                            onToggleValue = onToggleNode,
                            onSelectAll = onSelectAllNodes,
                            onSelectNone = onSelectNoNodes,
                        )
                    },
                ),
                cell = { ev -> CellData(ev.node.ifEmpty { "-" }) },
                minTableWidth = 800.dp,
            ),
            EventColumn(
                def = ColumnDef("Namespace", width = 100.dp),
                cell = { ev -> CellData(ev.namespace) },
                minTableWidth = 650.dp,
            ),
        )

        val visibleColumns = allColumns.filter { it.minTableWidth == null || tableWidth >= it.minTableWidth }
        val columns = visibleColumns.map { it.def }
        val defaultSortIndex = visibleColumns.indexOfFirst { it.def.header == "Last Seen" }

        val rows = events.map { ev ->
            val rowBg = when (ev.type) {
                "Warning" -> KdWarning.copy(alpha = 0.10f)
                "Error" -> KdError.copy(alpha = 0.10f)
                else -> null
            }
            TableRow(
                id = ev.uid,
                cells = visibleColumns.map { it.cell(ev) },
                backgroundColor = rowBg,
            )
        }

        val eventsByUid = remember(events) { events.associateBy { it.uid } }

        ResourceTable(
            columns = columns,
            rows = rows,
            onRowClick = if (onEventClick != null) {
                { row -> eventsByUid[row.id]?.let { onEventClick(it) } }
            } else {
                null
            },
            selectedRowId = selectedUid,
            emptyMessage = "No events found",
            defaultSortColumn = defaultSortIndex,
            defaultSortAscending = false,
            scrollToTopOnChange = true,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventTypeIcon(type: String) {
    val (icon: ImageVector, tint: Color) = when (type) {
        "Warning" -> Icons.Default.Warning to KdWarning
        "Error" -> Icons.Default.Error to KdError
        "Normal" -> Icons.Outlined.Info to KdSuccess
        else -> Icons.Outlined.Info to KdTextSecondary
    }
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = KdSurface,
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = type,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = tint,
                )
            }
        },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = type,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

@Composable
private fun ColumnFilterDropdown(
    expanded: Boolean,
    active: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    availableValues: Set<String>,
    selectedValues: Set<String>,
    onToggleValue: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    Box {
        Icon(
            Icons.Default.FilterList,
            contentDescription = "Filter",
            modifier = Modifier
                .size(14.dp)
                .padding(start = 2.dp)
                .clickable(onClick = onToggle),
            tint = if (active) KdPrimary else KdTextSecondary,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "All",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onSelectAll).padding(8.dp),
                )
                Text(
                    "None",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onSelectNone).padding(8.dp),
                )
            }
            HorizontalDivider()
            availableValues.sorted().forEach { value ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = value in selectedValues,
                                onCheckedChange = { onToggleValue(value) },
                                colors = CheckboxDefaults.colors(checkedColor = KdPrimary),
                            )
                            Text(value)
                        }
                    },
                    onClick = { onToggleValue(value) },
                )
            }
        }
    }
}

private data class EventColumn(
    val def: ColumnDef,
    val cell: (EventInfo) -> CellData,
    val minTableWidth: Dp? = null,
)
