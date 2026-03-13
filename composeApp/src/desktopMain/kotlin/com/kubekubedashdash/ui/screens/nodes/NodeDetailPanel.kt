package com.kubekubedashdash.ui.screens.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.KubeClientService
import com.kubekubedashdash.ui.components.LabelChip
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.GenericYamlTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class NodeDetailTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Info),
    Pods("Pods", Icons.AutoMirrored.Filled.ViewList),
    Events("Events", Icons.AutoMirrored.Filled.EventNote),
    Yaml("YAML", Icons.Default.Code),
}

private val nodeTallTabs = listOf(NodeDetailTab.Overview, NodeDetailTab.Yaml)
private val nodeCompactTabs = NodeDetailTab.entries.toList()

@Composable
internal fun NodeDetailPanel(
    node: NodeInfo,
    onClose: () -> Unit,
    onPodClick: (PodInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kubeClient = KubeClientService.reactiveClient
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

                    NodeDetailTab.Yaml -> GenericYamlTab("Node", node.name, null)
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
