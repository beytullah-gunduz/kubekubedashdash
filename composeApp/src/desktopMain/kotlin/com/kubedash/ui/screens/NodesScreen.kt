package com.kubedash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubedash.EventInfo
import com.kubedash.KdError
import com.kubedash.KdPrimary
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.NodeInfo
import com.kubedash.PodInfo
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
import com.kubedash.ui.statusColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun NodesScreen(
    kubeClient: KubeClient,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    var state by remember { mutableStateOf<ResourceState<List<NodeInfo>>>(ResourceState.Loading) }
    var selected by remember { mutableStateOf<NodeInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(480f) }

    LaunchedEffect(Unit) {
        state = ResourceState.Loading
        selected = null
        while (true) {
            state = try {
                val nodes = withContext(Dispatchers.IO) { kubeClient.getNodes() }
                ResourceState.Success(nodes)
            } catch (e: Exception) {
                if (state is ResourceState.Loading) {
                    ResourceState.Error(e.message ?: "Unknown error")
                } else {
                    state
                }
            }
            delay(10_000)
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
            val filtered = s.data.filter { node ->
                searchQuery.isBlank() ||
                    node.name.contains(searchQuery, ignoreCase = true) ||
                    node.roles.contains(searchQuery, ignoreCase = true) ||
                    node.status.contains(searchQuery, ignoreCase = true)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(filtered.size, "Nodes")
                    NodeTable(
                        nodes = filtered,
                        selectedUid = selected?.uid,
                        onClick = { node -> selected = if (selected?.uid == node.uid) null else node },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceIn(280f, 900f) }
                        selected?.let { node ->
                            ResourceDetailPanel(
                                kind = "Node",
                                name = node.name,
                                namespace = null,
                                status = node.status,
                                fields = listOf(
                                    DetailField("Status", node.status, statusColor(node.status)),
                                    DetailField("Roles", node.roles),
                                    DetailField("Version", node.version),
                                    DetailField("OS", node.os),
                                    DetailField("Architecture", node.arch),
                                    DetailField("Container Runtime", node.containerRuntime),
                                    DetailField("CPU", node.cpu),
                                    DetailField("Memory", node.memory),
                                    DetailField("Pods Capacity", node.pods),
                                    DetailField("Age", node.age),
                                ),
                                labels = node.labels,
                                kubeClient = kubeClient,
                                onClose = { selected = null },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                                extraTabs = listOf(
                                    ExtraTab("Pods", Icons.AutoMirrored.Filled.ViewList) {
                                        NodePodsTab(node.name, kubeClient)
                                    },
                                    ExtraTab("Events", Icons.AutoMirrored.Filled.EventNote) {
                                        NodeEventsTab(node.name, kubeClient)
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

// ── Node Pods Tab ───────────────────────────────────────────────────────────────

@Composable
private fun NodePodsTab(nodeName: String, kubeClient: KubeClient) {
    var pods by remember(nodeName) { mutableStateOf<List<PodInfo>>(emptyList()) }
    var loading by remember(nodeName) { mutableStateOf(true) }

    LaunchedEffect(nodeName) {
        loading = true
        pods = withContext(Dispatchers.IO) {
            try {
                kubeClient.getPodsByNode(nodeName)
            } catch (_: Exception) {
                emptyList()
            }
        }
        loading = false
    }

    if (loading) {
        ResourceLoadingIndicator()
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "${pods.size} pods on this node",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
            }
            items(pods.size) { i -> NodePodItem(pods[i]) }
        }
    }
}

@Composable
private fun NodePodItem(pod: PodInfo) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(pod.status)))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pod.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pod.namespace, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                    Text("·", color = KdTextSecondary)
                    Text(pod.status, style = MaterialTheme.typography.labelSmall, color = statusColor(pod.status))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Ready ${pod.ready}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Restarts ${pod.restarts}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pod.restarts > 10) KdWarning else KdTextSecondary,
                    )
                    Text(
                        pod.age,
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                }
            }
        }
    }
}

// ── Node Events Tab ─────────────────────────────────────────────────────────────

@Composable
private fun NodeEventsTab(nodeName: String, kubeClient: KubeClient) {
    var events by remember(nodeName) { mutableStateOf<List<EventInfo>>(emptyList()) }
    var loading by remember(nodeName) { mutableStateOf(true) }

    LaunchedEffect(nodeName) {
        loading = true
        events = withContext(Dispatchers.IO) {
            try {
                kubeClient.getEventsForNode(nodeName)
            } catch (_: Exception) {
                emptyList()
            }
        }
        loading = false
    }

    if (loading) {
        ResourceLoadingIndicator()
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "${events.size} events",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        "No events for this node",
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextSecondary,
                    )
                }
            }
            items(events.size) { i -> NodeEventItem(events[i]) }
        }
    }
}

@Composable
private fun NodeEventItem(event: EventInfo) {
    val typeColor = when (event.type.lowercase()) {
        "warning" -> KdWarning
        "error" -> KdError
        else -> KdTextSecondary
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(typeColor),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        event.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        event.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = typeColor,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    event.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    maxLines = 3,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (event.count > 1) {
                        Text(
                            "×${event.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "Last seen ${event.lastSeen}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                }
            }
        }
    }
}

// ── Adaptive table ──────────────────────────────────────────────────────────────

private class NodeColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (NodeInfo) -> CellData,
)

private val nodeColumns = listOf(
    NodeColumn("Name", 2.0f, 0.dp) { CellData(it.name, KdPrimary) },
    NodeColumn("Status", 0.8f, 0.dp) { CellData(it.status, statusColor(it.status)) },
    NodeColumn("Roles", 1.0f, 350.dp) { CellData(it.roles) },
    NodeColumn("Version", 1.0f, 450.dp) { CellData(it.version) },
    NodeColumn("CPU", 0.6f, 550.dp) { CellData(it.cpu) },
    NodeColumn("Memory", 0.8f, 600.dp) { CellData(it.memory) },
    NodeColumn("Pods", 0.5f, 700.dp) { CellData(it.pods) },
    NodeColumn("Arch", 0.8f, 800.dp) { CellData(it.arch) },
    NodeColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
private fun NodeTable(
    nodes: List<NodeInfo>,
    selectedUid: String?,
    onClick: (NodeInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = nodeColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = nodes.map { node ->
            TableRow(id = node.uid, cells = visible.map { it.cell(node) })
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
