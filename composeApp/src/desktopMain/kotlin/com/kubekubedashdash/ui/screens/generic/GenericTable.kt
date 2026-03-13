package com.kubekubedashdash.ui.screens.generic

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

private class GenericColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (GenericResourceInfo) -> CellData,
)

@Composable
internal fun GenericTable(
    resources: List<GenericResourceInfo>,
    namespacedKind: Boolean,
    selectedUid: String?,
    onClick: (GenericResourceInfo) -> Unit,
) {
    val extraKeys = resources.flatMap { it.extraColumns.keys }.distinct()
    val hasStatus = resources.any { it.status != null }

    val columns = buildList<GenericColumn> {
        add(GenericColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) })
        if (namespacedKind) {
            add(GenericColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace ?: "") })
        }
        if (hasStatus) {
            add(
                GenericColumn("Status", 0.8f, 0.dp) { r ->
                    CellData(r.status ?: "", r.status?.let { statusColor(it) })
                },
            )
        }
        extraKeys.forEachIndexed { i, key ->
            val minW = when {
                extraKeys.size <= 1 -> 0.dp
                i == extraKeys.lastIndex -> (500 + i * 100).dp
                else -> (450 + i * 100).dp
            }
            add(GenericColumn(key, 1f, minW) { r -> CellData(r.extraColumns[key] ?: "") })
        }
        add(GenericColumn("Age", 0.7f, 0.dp) { CellData(it.age) })
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = columns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = resources.map { r ->
            TableRow(id = r.uid, cells = visible.map { it.cell(r) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> resources.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No resources found",
        )
    }
}
