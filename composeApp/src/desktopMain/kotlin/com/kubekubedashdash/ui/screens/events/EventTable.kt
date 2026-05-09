package com.kubekubedashdash.ui.screens.events

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.info
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun EventTable(
    events: List<EventInfo>,
    selectedUid: String? = null,
    onEventClick: ((EventInfo) -> Unit)? = null,
) {
    BoxWithConstraints {
        val tableWidth = maxWidth

        val allColumns = listOf(
            EventColumn(
                def = ColumnDef(
                    header = "Type",
                    width = 50.dp,
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
internal fun EventTypeIcon(type: String) {
    val (icon: DrawableResource, tint: Color) = when (type) {
        "Warning" -> Res.drawable.warning_filled to KdWarning
        "Error" -> Res.drawable.error_filled to KdError
        "Normal" -> Res.drawable.info to KdSuccess
        else -> Res.drawable.info to KdTextSecondary
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
            painter = painterResource(icon),
            contentDescription = type,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

internal data class EventColumn(
    val def: ColumnDef,
    val cell: (EventInfo) -> CellData,
    val minTableWidth: Dp? = null,
)
