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
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowAction
import com.kubekubedashdash.ui.components.RowIdentity
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow

private class GenericColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (GenericResourceInfo) -> CellData,
)

@Composable
internal fun GenericTable(
    resources: List<GenericResourceInfo>,
    kind: String,
    namespacedKind: Boolean,
    selectedUid: String?,
    onClick: (GenericResourceInfo) -> Unit,
    onDelete: ((GenericResourceInfo) -> Unit)? = null,
    extraActions: ((GenericResourceInfo) -> List<RowAction>)? = null,
    selectedUids: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
) {
    // CRD printer columns almost always include their own "Age"; the table
    // appends a built-in Age column below, so drop the duplicate here. Assumes
    // the printer Age tracks metadata.creationTimestamp (the kubectl
    // convention); a CRD that redefines it loses the printer value.
    val extraKeys = resources.flatMap { it.extraColumns.keys }.distinct()
        .filterNot { it.equals("Age", ignoreCase = true) }
    val hasStatus = resources.any { it.status != null }

    val columns = buildList<GenericColumn> {
        add(GenericColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) })
        if (namespacedKind) {
            add(GenericColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace ?: "") })
        }
        if (hasStatus) {
            add(
                GenericColumn("Status", 0.8f, 0.dp) { r ->
                    val s = r.status.orEmpty()
                    CellData(text = s, sortValue = s, content = { StatusCell(s) })
                },
            )
        }
        extraKeys.forEachIndexed { i, key ->
            val minW = when {
                extraKeys.size <= 1 -> 0.dp
                i == extraKeys.lastIndex -> (500 + i * 100).dp
                else -> (450 + i * 100).dp
            }
            // A printer column named "Status" carries the same vocabulary as the
            // built-in status column (Running / Completed / Failed …); give it the
            // icon + colour treatment when the row has no real status column.
            val isStatusKey = !hasStatus && key.equals("Status", ignoreCase = true)
            add(
                GenericColumn(key, 1f, minW) { r ->
                    val v = r.extraColumns[key] ?: ""
                    if (isStatusKey && v.isNotBlank() && v != NONE_PLACEHOLDER) {
                        CellData(text = v, sortValue = v, content = { StatusCell(v) })
                    } else {
                        CellData(v)
                    }
                },
            )
        }
        add(GenericColumn("Age", 0.7f, 0.dp) { CellData(it.age) })
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = columns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = resources.map { r ->
            TableRow(
                id = r.uid,
                identity = RowIdentity(kind, r.name, r.namespace),
                cells = visible.map { it.cell(r) },
                actions = (extraActions?.invoke(r) ?: emptyList()) +
                    (if (onDelete != null) listOf(RowAction("Delete") { onDelete(r) }) else emptyList()),
            )
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> resources.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No resources found",
            selectable = onSelectionChange != null,
            selectedIds = selectedUids,
            onSelectionChange = onSelectionChange,
        )
    }
}
