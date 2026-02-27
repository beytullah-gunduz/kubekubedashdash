package com.kubedash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubedash.DeploymentInfo
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import com.kubedash.Screen
import com.kubedash.ui.CellData
import com.kubedash.ui.ColumnDef
import com.kubedash.ui.ResizeHandle
import com.kubedash.ui.ResourceCountHeader
import com.kubedash.ui.ResourceErrorMessage
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.ResourceTable
import com.kubedash.ui.TableRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DeploymentsScreen(
    kubeClient: KubeClient,
    namespace: String?,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    var state by remember { mutableStateOf<ResourceState<List<DeploymentInfo>>>(ResourceState.Loading) }
    var selected by remember { mutableStateOf<DeploymentInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(650f) }

    LaunchedEffect(namespace) {
        state = ResourceState.Loading
        selected = null
        while (true) {
            state = try {
                val deps = withContext(Dispatchers.IO) { kubeClient.getDeployments(namespace) }
                ResourceState.Success(deps)
            } catch (e: Exception) {
                if (state is ResourceState.Loading) {
                    ResourceState.Error(e.message ?: "Unknown error")
                } else {
                    state
                }
            }
            delay(5_000)
        }
    }

    LaunchedEffect(state) {
        val current = (state as? ResourceState.Success)?.data ?: return@LaunchedEffect
        selected = selected?.let { sel -> current.find { it.uid == sel.uid } }
    }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { dep ->
                searchQuery.isBlank() ||
                    dep.name.contains(searchQuery, ignoreCase = true) ||
                    dep.namespace.contains(searchQuery, ignoreCase = true)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(filtered.size, "Deployments")
                    DeploymentTable(
                        deployments = filtered,
                        selectedUid = selected?.uid,
                        onClick = { dep -> selected = if (selected?.uid == dep.uid) null else dep },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceAtLeast(280f) }
                        selected?.let { dep ->
                            val readyParts = dep.ready.split("/")
                            val isReady = readyParts.size == 2 && readyParts[0] == readyParts[1] && readyParts[0] != "0"
                            ResourceDetailPanel(
                                kind = "Deployment",
                                name = dep.name,
                                namespace = dep.namespace,
                                status = if (isReady) "Available" else "Progressing",
                                fields = listOf(
                                    DetailField("Namespace", dep.namespace),
                                    DetailField("Ready", dep.ready, if (isReady) KdSuccess else KdWarning),
                                    DetailField("Up-to-date", "${dep.upToDate}"),
                                    DetailField("Available", "${dep.available}", if (dep.available > 0) KdSuccess else KdWarning),
                                    DetailField("Strategy", dep.strategy),
                                    DetailField("Age", dep.age),
                                    *dep.conditions.map { DetailField("Condition", it) }.toTypedArray(),
                                ),
                                labels = dep.labels,
                                kubeClient = kubeClient,
                                onClose = { selected = null },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                                extraTabs = listOf(
                                    ExtraTab(
                                        label = "Graph",
                                        icon = Icons.Default.AccountTree,
                                    ) {
                                        DeploymentResourceGraphTab(
                                            deploymentName = dep.name,
                                            namespace = dep.namespace,
                                            kubeClient = kubeClient,
                                        )
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Adaptive table ──────────────────────────────────────────────────────────────

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
private fun DeploymentTable(
    deployments: List<DeploymentInfo>,
    selectedUid: String?,
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
