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
import com.kubedash.KdPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import com.kubedash.Screen
import com.kubedash.ServiceInfo
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
fun ServicesScreen(
    kubeClient: KubeClient,
    namespace: String?,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    var state by remember { mutableStateOf<ResourceState<List<ServiceInfo>>>(ResourceState.Loading) }
    var selected by remember { mutableStateOf<ServiceInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(650f) }

    LaunchedEffect(namespace) {
        state = ResourceState.Loading
        selected = null
        while (true) {
            state = try {
                val svcs = withContext(Dispatchers.IO) { kubeClient.getServices(namespace) }
                ResourceState.Success(svcs)
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
            val filtered = s.data.filter { svc ->
                searchQuery.isBlank() ||
                    svc.name.contains(searchQuery, ignoreCase = true) ||
                    svc.namespace.contains(searchQuery, ignoreCase = true) ||
                    svc.type.contains(searchQuery, ignoreCase = true)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(filtered.size, "Services")
                    ServiceTable(
                        services = filtered,
                        selectedUid = selected?.uid,
                        onClick = { svc -> selected = if (selected?.uid == svc.uid) null else svc },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceAtLeast(280f) }
                        selected?.let { svc ->
                            val selectorFields = svc.selector.map { (k, v) -> DetailField("Selector", "$k=$v") }
                            ResourceDetailPanel(
                                kind = "Service",
                                name = svc.name,
                                namespace = svc.namespace,
                                status = svc.type,
                                fields = listOf(
                                    DetailField("Type", svc.type, serviceTypeColor(svc.type)),
                                    DetailField("Namespace", svc.namespace),
                                    DetailField("Cluster IP", svc.clusterIP),
                                    DetailField("Ports", svc.ports),
                                    DetailField("Age", svc.age),
                                ) + selectorFields,
                                labels = svc.labels,
                                kubeClient = kubeClient,
                                onClose = { selected = null },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun serviceTypeColor(type: String) = when (type) {
    "LoadBalancer" -> KdPrimary
    "NodePort" -> KdWarning
    "ClusterIP" -> KdTextSecondary
    else -> null
}

// ── Adaptive table ──────────────────────────────────────────────────────────────

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
private fun ServiceTable(
    services: List<ServiceInfo>,
    selectedUid: String?,
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
