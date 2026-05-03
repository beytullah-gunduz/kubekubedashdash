package com.kubekubedashdash.ui.screens.nodes

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowAction
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow

private class NodeColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (NodeInfo) -> CellData,
)

private val nodeColumns = listOf(
    NodeColumn("Name", 2.0f, 0.dp) { CellData(it.name, KdPrimary) },
    NodeColumn("Status", 0.8f, 0.dp) { node ->
        CellData(text = node.status, sortValue = node.status, content = { StatusCell(node.status) })
    },
    NodeColumn("Roles", 1.0f, 350.dp) { CellData(it.roles) },
    NodeColumn("Version", 1.0f, 450.dp) { CellData(it.version) },
    NodeColumn("CPU", 0.6f, 550.dp) { CellData(it.cpu) },
    NodeColumn("Memory", 0.8f, 600.dp) { CellData(it.memory) },
    NodeColumn("Pods", 0.5f, 700.dp) { CellData(it.pods) },
    NodeColumn("Arch", 0.8f, 800.dp) { CellData(it.arch) },
    NodeColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
internal fun NodeTable(
    nodes: List<NodeInfo>,
    selectedUid: String? = null,
    onClick: (NodeInfo) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = nodeColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = nodes.map { node ->
            TableRow(
                id = node.uid,
                cells = visible.map { it.cell(node) },
                actions = listOf(
                    RowAction("Copy name") {
                        clipboard.setText(AnnotatedString(node.name))
                    },
                    RowAction("Copy kubectl get") {
                        clipboard.setText(AnnotatedString("kubectl get node ${node.name}"))
                    },
                    RowAction("Copy kubectl describe") {
                        clipboard.setText(AnnotatedString("kubectl describe node ${node.name}"))
                    },
                ),
            )
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> nodes.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No nodes found",
        )
    }
}
