package com.kubekubedashdash.ui.screens.nodes

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowIdentity
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.components.ageSortKey

private class NodeColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (NodeInfo, NodeResourceUsage?) -> CellData,
)

private val nodeColumns = listOf(
    NodeColumn("Name", 2.0f, 0.dp) { node, _ -> CellData(node.name, KdPrimary) },
    NodeColumn("Status", 0.8f, 0.dp) { node, _ ->
        CellData(text = node.status, sortValue = node.status, content = { StatusCell(node.status) })
    },
    NodeColumn("Roles", 1.0f, 350.dp) { node, _ -> CellData(node.roles) },
    NodeColumn("Version", 1.0f, 450.dp) { node, _ -> CellData(node.version) },
    // Usage vs allocatable (D4); allocatable alone without a sample.
    NodeColumn("CPU", 1.3f, 550.dp) { node, usage -> nodeUsageCell(nodeCpuModel(node, usage)) },
    NodeColumn("Memory", 1.5f, 600.dp) { node, usage -> nodeUsageCell(nodeMemoryModel(node, usage)) },
    NodeColumn("Pods", 0.5f, 700.dp) { node, _ -> CellData(node.pods, sortNumber = node.pods.toDoubleOrNull()) },
    NodeColumn("Arch", 0.8f, 800.dp) { node, _ -> CellData(node.arch) },
    NodeColumn("Age", 0.7f, 0.dp) { node, _ -> CellData(node.age, sortNumber = ageSortKey(node.creationTimestamp)) },
)

@Composable
internal fun NodeTable(
    nodes: List<NodeInfo>,
    selectedUid: String? = null,
    onClick: (NodeInfo) -> Unit,
    // UIDs of nodes that have left the cluster but are briefly retained (see
    // NodesScreenViewModel.staleNodes). Rendered greyed with a "Removed"
    // status so the lingering row reads as gone, not live.
    staleUids: Set<String> = emptySet(),
    selectedUids: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: ((String) -> Unit)? = null,
    // Per-node usage keyed by node name (ReactiveKubeClient.nodeUsages).
    usages: Map<String, NodeResourceUsage> = emptyMap(),
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = nodeColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = nodes.map { node ->
            val isStale = node.uid in staleUids
            TableRow(
                id = node.uid,
                pinId = "node:${node.name}",
                selectable = !isStale,
                identity = RowIdentity("Node", node.name),
                cells = visible.map { col ->
                    val base = col.cell(node, usages[node.name])
                    when {
                        !isStale -> base

                        // Frozen snapshot of a node that no longer exists —
                        // drop the live status/colors.
                        col.header == "Status" -> CellData("Removed", KdTextSecondary)

                        // A removed node has no live usage.
                        col.header == "CPU" || col.header == "Memory" -> CellData(NONE_PLACEHOLDER)

                        else -> base.copy(color = KdTextSecondary, content = null)
                    }
                },
            )
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> nodes.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No nodes found",
            tableKey = "Nodes",
            pinnable = onTogglePin != null,
            pinnedIds = pinnedIds,
            onTogglePin = onTogglePin,
            selectable = onSelectionChange != null,
            selectedIds = selectedUids,
            onSelectionChange = onSelectionChange,
        )
    }
}
