package com.kubekubedashdash.ui.screens.services

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.ServiceInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow

internal fun serviceTypeColor(type: String): Color? = when (type) {
    "LoadBalancer" -> KdPrimary
    "NodePort" -> KdWarning
    "ClusterIP" -> KdTextSecondary
    else -> null
}

private class SvcColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (ServiceInfo) -> CellData,
)

private val svcColumns = listOf(
    SvcColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) },
    SvcColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace) },
    SvcColumn("Type", 0.8f, 0.dp) { CellData(it.type, serviceTypeColor(it.type)) },
    SvcColumn("Cluster IP", 1.2f, 550.dp) { CellData(it.clusterIP) },
    SvcColumn("Ports", 1.5f, 650.dp) { CellData(it.ports) },
    SvcColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
internal fun ServiceTable(
    services: List<ServiceInfo>,
    selectedUid: String? = null,
    onClick: (ServiceInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = svcColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = services.map { svc ->
            TableRow(id = svc.uid, cells = visible.map { it.cell(svc) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> services.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No services found",
        )
    }
}
