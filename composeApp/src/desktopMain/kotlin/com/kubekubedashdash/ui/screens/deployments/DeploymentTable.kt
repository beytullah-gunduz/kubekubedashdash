package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.TableRow

private class DeployColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (DeploymentInfo) -> CellData,
)

private val deployColumns = listOf(
    DeployColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) },
    DeployColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace) },
    DeployColumn("Ready", 0.8f, 0.dp) { dep ->
        val parts = dep.ready.split("/")
        val ok = parts.size == 2 && parts[0] == parts[1] && parts[0] != "0"
        CellData(dep.ready, if (ok) KdSuccess else KdWarning)
    },
    DeployColumn("Up-to-date", 0.8f, 550.dp) { CellData("${it.upToDate}") },
    DeployColumn("Available", 0.8f, 500.dp) { CellData("${it.available}") },
    DeployColumn("Strategy", 1.0f, 650.dp) { CellData(it.strategy) },
    DeployColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
internal fun DeploymentTable(
    deployments: List<DeploymentInfo>,
    selectedUid: String? = null,
    onClick: (DeploymentInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = deployColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = deployments.map { dep ->
            TableRow(id = dep.uid, cells = visible.map { it.cell(dep) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> deployments.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No deployments found",
        )
    }
}
