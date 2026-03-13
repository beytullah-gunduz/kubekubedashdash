package com.kubekubedashdash.ui.screens.namespaces

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.components.statusColor

private class NsColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (GenericResourceInfo) -> CellData,
)

private val nsColumns = listOf(
    NsColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) },
    NsColumn("Status", 1.0f, 0.dp) { CellData(it.status ?: "", it.status?.let { statusColor(it) }) },
    NsColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
internal fun NamespaceTable(
    namespaces: List<GenericResourceInfo>,
    selectedUid: String? = null,
    onClick: (GenericResourceInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = nsColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = namespaces.map { ns ->
            TableRow(id = ns.uid, cells = visible.map { it.cell(ns) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> namespaces.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No namespaces found",
        )
    }
}
