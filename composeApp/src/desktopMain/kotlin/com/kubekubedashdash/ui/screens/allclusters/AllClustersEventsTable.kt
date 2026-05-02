package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.screens.events.EventColumn
import com.kubekubedashdash.ui.screens.events.EventTypeIcon

/**
 * Aggregated events table for the AllClusters view. Identical column set to the
 * single-cluster [com.kubekubedashdash.ui.screens.events.EventTable] except a
 * "Cluster" column is inserted immediately before the Node column so users
 * can see which cluster each event originated from.
 */
@Composable
internal fun AllClustersEventsTable(events: List<EventInfo>) {
    BoxWithConstraints {
        val tableWidth = maxWidth

        val allColumns = listOf(
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
            // Cluster column inserted before Node
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
            onRowClick = null,
            selectedRowId = null,
            emptyMessage = "No events found",
            defaultSortColumn = defaultSortIndex,
            defaultSortAscending = false,
            scrollToTopOnChange = true,
        )
    }
}
