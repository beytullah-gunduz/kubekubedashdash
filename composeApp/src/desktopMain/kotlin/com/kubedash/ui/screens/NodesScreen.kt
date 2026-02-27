package com.kubedash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubedash.EventInfo
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdPrimary
import com.kubedash.KdSurface
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.NodeInfo
import com.kubedash.PodInfo
import com.kubedash.ResourceState
import com.kubedash.ResourceUsageSummary
import com.kubedash.Screen
import com.kubedash.formatCpuCores
import com.kubedash.formatMemorySize
import com.kubedash.parseCpuToMillis
import com.kubedash.parseMemoryToBytes
import com.kubedash.ui.CellData
import com.kubedash.ui.CircularUsageIndicator
import com.kubedash.ui.ColumnDef
import com.kubedash.ui.LabelChip
import com.kubedash.ui.ResizeHandle
import com.kubedash.ui.ResourceCountHeader
import com.kubedash.ui.ResourceErrorMessage
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.ResourceTable
import com.kubedash.ui.StatusBadge
import com.kubedash.ui.TableRow
import com.kubedash.ui.UsageHistoryBar
import com.kubedash.ui.statusColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MAX_HISTORY_SIZE = 20

@Composable
fun NodesScreen(
    kubeClient: KubeClient,
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
) {
    var state by remember { mutableStateOf<ResourceState<List<NodeInfo>>>(ResourceState.Loading) }
    var selected by remember { mutableStateOf<NodeInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(650f) }
    var statsExpanded by remember { mutableStateOf(true) }
    var resourceUsage by remember { mutableStateOf<ResourceUsageSummary?>(null) }
    var cpuHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    var memHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
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

    LaunchedEffect(Unit) {
        while (true) {
            val usage = try {
                withContext(Dispatchers.IO) { kubeClient.getResourceUsage(null) }
            } catch (e: Exception) {
                null
            }
            resourceUsage = usage
            if (usage != null && usage.metricsAvailable) {
                val cpuF = if (usage.cpuCapacityMillis > 0) {
                    usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis.toFloat()
                } else {
                    0f
                }
                val memF = if (usage.memoryCapacityBytes > 0) {
                    usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes.toFloat()
                } else {
                    0f
                }
                cpuHistory = (cpuHistory + cpuF).takeLast(MAX_HISTORY_SIZE)
                memHistory = (memHistory + memF).takeLast(MAX_HISTORY_SIZE)
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
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val showStats = maxWidth >= 900.dp
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Hide stats panel when left view is too small (< 900dp)
                        if (showStats) {
                            NodeStatsPanel(
                                nodes = s.data,
                                usage = resourceUsage,
                                cpuHistory = cpuHistory,
                                memHistory = memHistory,
                                kubeClient = kubeClient,
                                expanded = statsExpanded,
                                onToggle = { statsExpanded = !statsExpanded },
                            )
                        }
                        ResourceCountHeader(filtered.size, "Nodes")
                        NodeTable(
                            nodes = filtered,
                            selectedUid = selected?.uid,
                            onClick = { node -> selected = if (selected?.uid == node.uid) null else node },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceAtLeast(280f) }
                        selected?.let { node ->
                            NodeDetailPanel(
                                node = node,
                                kubeClient = kubeClient,
                                onClose = { selected = null },
                                onPodClick = { pod -> onNavigate(Screen.Pods(selectPodUid = pod.uid)) },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Node Detail Panel ───────────────────────────────────────────────────────────

private enum class NodeDetailTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Info),
    Pods("Pods", Icons.AutoMirrored.Filled.ViewList),
    Events("Events", Icons.AutoMirrored.Filled.EventNote),
    Yaml("YAML", Icons.Default.Code),
}

private val nodeTallTabs = listOf(NodeDetailTab.Overview, NodeDetailTab.Yaml)
private val nodeCompactTabs = NodeDetailTab.entries.toList()

@Composable
private fun NodeDetailPanel(
    node: NodeInfo,
    kubeClient: KubeClient,
    onClose: () -> Unit,
    onPodClick: (PodInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(NodeDetailTab.Overview) }
    LaunchedEffect(node.uid) { activeTab = NodeDetailTab.Overview }

    var pods by remember(node.name) { mutableStateOf<List<PodInfo>>(emptyList()) }
    var podsLoading by remember(node.name) { mutableStateOf(true) }
    var events by remember(node.name) { mutableStateOf<List<EventInfo>>(emptyList()) }
    var eventsLoading by remember(node.name) { mutableStateOf(true) }

    LaunchedEffect(node.name) {
        podsLoading = true
        pods = withContext(Dispatchers.IO) {
            try {
                kubeClient.getPodsByNode(node.name)
            } catch (_: Exception) {
                emptyList()
            }
        }
        podsLoading = false
    }

    LaunchedEffect(node.name) {
        eventsLoading = true
        events = withContext(Dispatchers.IO) {
            try {
                kubeClient.getEventsForNode(node.name)
            } catch (_: Exception) {
                emptyList()
            }
        }
        eventsLoading = false
    }

    Surface(modifier = modifier, color = KdSurface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTall = maxHeight >= 1000.dp
            val tabs = if (isTall) nodeTallTabs else nodeCompactTabs

            LaunchedEffect(isTall) {
                if (isTall && activeTab != NodeDetailTab.Overview && activeTab != NodeDetailTab.Yaml) {
                    activeTab = NodeDetailTab.Overview
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KdSurfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = KdTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusBadge(node.status)
                            Text("Node", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", Modifier.size(16.dp), tint = KdTextSecondary)
                    }
                }

                SecondaryTabRow(
                    selectedTabIndex = tabs.indexOf(activeTab).coerceAtLeast(0),
                    containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
                    contentColor = KdPrimary,
                    divider = { HorizontalDivider(color = KdBorder) },
                ) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == activeTab,
                            onClick = { activeTab = tab },
                            selectedContentColor = KdPrimary,
                            unselectedContentColor = KdTextSecondary,
                        ) {
                            Row(modifier = Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(tab.icon, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(tab.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                when (activeTab) {
                    NodeDetailTab.Overview -> {
                        if (isTall) {
                            NodeOverviewCombinedTab(node, pods, podsLoading, events, eventsLoading, onPodClick)
                        } else {
                            NodeDetailsOnlyTab(node)
                        }
                    }

                    NodeDetailTab.Pods -> NodePodsTab(pods, podsLoading, onPodClick)

                    NodeDetailTab.Events -> NodeEventsTab(events, eventsLoading)

                    NodeDetailTab.Yaml -> GenericYamlTab("Node", node.name, null, kubeClient)
                }
            }
        }
    }
}

@Composable
private fun NodeOverviewCombinedTab(
    node: NodeInfo,
    pods: List<PodInfo>,
    podsLoading: Boolean,
    events: List<EventInfo>,
    eventsLoading: Boolean,
    onPodClick: (PodInfo) -> Unit,
) {
    val fields = listOf(
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
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Details ─────────────────────────────────────────────────────────
        item {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
        }
        item {
            Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    fields.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(f.label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                            if (f.valueColor != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(f.valueColor))
                                    Spacer(Modifier.width(5.dp))
                                    Text(f.value, style = MaterialTheme.typography.bodySmall, color = f.valueColor, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                Text(f.value, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // ── Labels ──────────────────────────────────────────────────────────
        if (node.labels.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Labels", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.labels.entries.toList().chunked(2).forEach { chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            chunk.forEach { (k, v) -> LabelChip(k, v) }
                        }
                    }
                }
            }
        }

        // ── Pods ────────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pods", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
                if (!podsLoading) {
                    Text("${pods.size}", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                }
            }
        }
        if (podsLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KdPrimary)
                }
            }
        } else if (pods.isEmpty()) {
            item { Text("No pods on this node", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary) }
        } else {
            items(pods.size) { i -> NodePodItem(pods[i]) { onPodClick(pods[i]) } }
        }

        // ── Events ──────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Events", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
                if (!eventsLoading) {
                    Text("${events.size}", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                }
            }
        }
        if (eventsLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KdPrimary)
                }
            }
        } else if (events.isEmpty()) {
            item { Text("No events for this node", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary) }
        } else {
            items(events.size) { i -> NodeEventItem(events[i]) }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ── Compact-mode tabs ───────────────────────────────────────────────────────────

@Composable
private fun NodeDetailsOnlyTab(node: NodeInfo) {
    val fields = listOf(
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
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
        }
        item {
            Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    fields.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(f.label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                            if (f.valueColor != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(6.dp).clip(CircleShape).background(f.valueColor))
                                    Spacer(Modifier.width(5.dp))
                                    Text(f.value, style = MaterialTheme.typography.bodySmall, color = f.valueColor, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                Text(f.value, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        if (node.labels.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Labels", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.labels.entries.toList().chunked(2).forEach { chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            chunk.forEach { (k, v) -> LabelChip(k, v) }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun NodePodsTab(pods: List<PodInfo>, podsLoading: Boolean, onPodClick: (PodInfo) -> Unit) {
    if (podsLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = KdPrimary)
        }
    } else if (pods.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
            Text("No pods on this node", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "${pods.size} Pods",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(pods.size) { i -> NodePodItem(pods[i]) { onPodClick(pods[i]) } }
        }
    }
}

@Composable
private fun NodeEventsTab(events: List<EventInfo>, eventsLoading: Boolean) {
    if (eventsLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = KdPrimary)
        }
    } else if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
            Text("No events for this node", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "${events.size} Events",
                    style = MaterialTheme.typography.labelLarge,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(events.size) { i -> NodeEventItem(events[i]) }
        }
    }
}

@Composable
private fun NodePodItem(pod: PodInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
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

// ── Stats Panel ─────────────────────────────────────────────────────────────────

@Composable
private fun NodeStatsPanel(
    nodes: List<NodeInfo>,
    usage: ResourceUsageSummary?,
    cpuHistory: List<Float>,
    memHistory: List<Float>,
    kubeClient: KubeClient,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Node Usage Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = KdTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Pods count indicator
                    NodePodsSummary(nodes, kubeClient)

                    if (usage != null && usage.metricsAvailable) {
                        val memFraction = if (usage.memoryCapacityBytes > 0) {
                            usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes.toFloat()
                        } else {
                            0f
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularUsageIndicator(
                                fraction = memFraction,
                                label = "Memory",
                                usedText = formatMemorySize(usage.memoryUsedBytes),
                                totalText = formatMemorySize(usage.memoryCapacityBytes),
                            )
                            if (memHistory.size > 1) {
                                Spacer(Modifier.height(6.dp))
                                UsageHistoryBar(
                                    history = memHistory,
                                    modifier = Modifier.width(100.dp).height(36.dp),
                                )
                            }
                        }

                        val cpuFraction = if (usage.cpuCapacityMillis > 0) {
                            usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis.toFloat()
                        } else {
                            0f
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularUsageIndicator(
                                fraction = cpuFraction,
                                label = "CPU",
                                usedText = formatCpuCores(usage.cpuUsedMillis),
                                totalText = formatCpuCores(usage.cpuCapacityMillis),
                            )
                            if (cpuHistory.size > 1) {
                                Spacer(Modifier.height(6.dp))
                                UsageHistoryBar(
                                    history = cpuHistory,
                                    modifier = Modifier.width(100.dp).height(36.dp),
                                )
                            }
                        }
                    } else if (usage == null) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = KdPrimary,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Metrics server unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodePodsSummary(
    nodes: List<NodeInfo>,
    kubeClient: KubeClient,
) {
    var podsCount by remember { mutableStateOf(0) }
    var podsCapacity by remember { mutableStateOf(0) }
    var initialLoaded by remember { mutableStateOf(false) }
    var podsHistory by remember { mutableStateOf<List<Float>>(emptyList()) }
    val currentNodes by rememberUpdatedState(nodes)

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    var totalPods = 0
                    var totalCapacity = 0
                    currentNodes.forEach { node ->
                        try {
                            totalPods += kubeClient.getPodsByNode(node.name).size
                        } catch (_: Exception) {
                        }
                        totalCapacity += node.pods.toIntOrNull() ?: 0
                    }
                    podsCount = totalPods
                    podsCapacity = totalCapacity
                } catch (_: Exception) {
                } finally {
                    initialLoaded = true
                }
            }
            val frac = if (podsCapacity > 0) podsCount.toFloat() / podsCapacity.toFloat() else 0f
            podsHistory = (podsHistory + frac).takeLast(MAX_HISTORY_SIZE)
            delay(10_000)
        }
    }

    val podsFraction = if (podsCapacity > 0) {
        podsCount.toFloat() / podsCapacity.toFloat()
    } else {
        0f
    }

    if (!initialLoaded) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = KdPrimary,
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            CircularUsageIndicator(
                fraction = podsFraction,
                label = "Pods",
                usedText = "$podsCount",
                totalText = "$podsCapacity",
            )
            if (podsHistory.size > 1) {
                Spacer(Modifier.height(6.dp))
                UsageHistoryBar(
                    history = podsHistory,
                    modifier = Modifier.width(100.dp).height(36.dp),
                )
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
