package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.components.statusColor

private class PodColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (PodInfo) -> CellData,
)

private val podColumns = listOf(
    PodColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) },
    PodColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace) },
    PodColumn("Status", 1.0f, 0.dp) { CellData(it.status, statusColor(it.status)) },
    PodColumn("Ready", 0.6f, 300.dp) { CellData(it.ready) },
    PodColumn("Restarts", 0.7f, 500.dp) { pod ->
        CellData(
            "${pod.restarts}",
            if (pod.restarts > 50) {
                KdError
            } else if (pod.restarts > 10) {
                KdWarning
            } else {
                null
            },
        )
    },
    PodColumn("Node", 1.2f, 600.dp) { CellData(it.node) },
    PodColumn("IP", 1.0f, 750.dp) { CellData(it.ip) },
    PodColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
internal fun PodTable(
    pods: List<PodInfo>,
    selectedUid: String? = null,
    onPodClick: (PodInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = podColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = pods.map { pod ->
            TableRow(id = pod.uid, cells = visible.map { it.cell(pod) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> pods.find { it.uid == row.id }?.let(onPodClick) },
            emptyMessage = "No pods found",
        )
    }
}
