package com.kubekubedashdash.ui.screens.events

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.KubeClientService
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.GenericYamlTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class EventDetailTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Info),
    Yaml("YAML", Icons.Default.Code),
}

@Composable
fun EventDetailScreen(
    event: EventInfo,
    onNavigate: (Screen) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    val kubeClient = KubeClientService.reactiveClient
    var activeTab by remember { mutableIntStateOf(0) }
    LaunchedEffect(event.uid) { activeTab = 0 }

    // Node events
    var nodeEvents by remember(event.uid) { mutableStateOf<List<EventInfo>>(emptyList()) }
    var nodeEventsLoading by remember(event.uid) { mutableStateOf(false) }

    // Pod info (if event involves a Pod)
    var podInfo by remember(event.uid) { mutableStateOf<PodInfo?>(null) }
    var podInfoLoading by remember(event.uid) { mutableStateOf(false) }

    // Pod logs
    var podLogs by remember(event.uid) { mutableStateOf<String?>(null) }
    var podLogsLoading by remember(event.uid) { mutableStateOf(false) }

    // Fetch node events
    LaunchedEffect(event.uid, event.node) {
        if (event.node.isNotEmpty()) {
            nodeEventsLoading = true
            nodeEvents = withContext(Dispatchers.IO) {
                try {
                    kubeClient.getEventsOnNode(event.node).take(20)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            nodeEventsLoading = false
        }
    }

    // Fetch pod info and logs if the event involves a Pod
    LaunchedEffect(event.uid, event.objectKind, event.objectName) {
        if (event.objectKind == "Pod" && event.objectName.isNotEmpty() && event.namespace.isNotEmpty()) {
            podInfoLoading = true
            podInfo = withContext(Dispatchers.IO) {
                try {
                    kubeClient.getPodByName(event.objectName, event.namespace)
                } catch (_: Exception) {
                    null
                }
            }
            podInfoLoading = false

            // Fetch logs for the pod
            podLogsLoading = true
            podLogs = withContext(Dispatchers.IO) {
                try {
                    kubeClient.getPodLogs(event.objectName, event.namespace, null, 50)
                } catch (_: Exception) {
                    null
                }
            }
            podLogsLoading = false
        }
    }

    val tabs = EventDetailTab.entries.toList()
    val typeColor = when (event.type) {
        "Warning" -> KdWarning
        "Error" -> KdError
        "Normal" -> KdSuccess
        else -> KdTextSecondary
    }

    Surface(color = KdSurface, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KdSurfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        event.reason,
                        style = MaterialTheme.typography.titleMedium,
                        color = KdTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusBadge(event.type)
                        Text(
                            "Event · ${event.namespace}",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }
                if (onClose != null) {
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Close", Modifier.size(16.dp), tint = KdTextSecondary)
                    }
                }
            }

            // Tabs
            SecondaryTabRow(
                selectedTabIndex = activeTab,
                containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
                contentColor = KdPrimary,
                divider = { HorizontalDivider(color = KdBorder) },
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == activeTab,
                        onClick = { activeTab = index },
                        selectedContentColor = KdPrimary,
                        unselectedContentColor = KdTextSecondary,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(tab.icon, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(tab.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            when (activeTab) {
                0 -> EventOverviewTab(
                    event = event,
                    typeColor = typeColor,
                    nodeEvents = nodeEvents,
                    nodeEventsLoading = nodeEventsLoading,
                    podInfo = podInfo,
                    podInfoLoading = podInfoLoading,
                    podLogs = podLogs,
                    podLogsLoading = podLogsLoading,
                    onNavigate = onNavigate,
                )

                1 -> GenericYamlTab("Event", event.objectRef, event.namespace)
            }
        }
    }
}

@Composable
private fun EventOverviewTab(
    event: EventInfo,
    typeColor: androidx.compose.ui.graphics.Color,
    nodeEvents: List<EventInfo>,
    nodeEventsLoading: Boolean,
    podInfo: PodInfo?,
    podInfoLoading: Boolean,
    podLogs: String?,
    podLogsLoading: Boolean,
    onNavigate: (Screen) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Event Details ────────────────────────────────────────────────
        item {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
        }
        item {
            Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    DetailRow("Type", event.type, typeColor)
                    DetailRow("Reason", event.reason)
                    DetailRow("Message", event.message)
                    DetailRow("Object", event.objectRef)
                    DetailRow("Count", "${event.count}")
                    DetailRow("First Seen", event.firstSeen)
                    DetailRow("Last Seen", event.lastSeen)
                    DetailRow("Namespace", event.namespace)
                }
            }
        }

        // ── Node (clickable) + Node Events (collapsible) ─────────────────
        if (event.node.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Node", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = KdSurfaceVariant,
                    onClick = { onNavigate(Screen.Main.Nodes(selectNodeName = event.node)) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(KdPrimary))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            event.node,
                            style = MaterialTheme.typography.bodySmall,
                            color = KdPrimary,
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                CollapsibleSection(
                    title = "Node Events",
                    count = if (!nodeEventsLoading) nodeEvents.size else null,
                ) {
                    if (nodeEventsLoading) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KdPrimary)
                        }
                    } else if (nodeEvents.isEmpty()) {
                        Text("No events for this node", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            nodeEvents.forEach { ev -> NodeEventItem(ev) }
                        }
                    }
                }
            }
        }

        // ── Pod (clickable, if event involves a Pod) ─────────────────────
        if (event.objectKind == "Pod" && event.objectName.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Affected Pod", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            if (podInfoLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KdPrimary)
                    }
                }
            } else if (podInfo != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = KdSurfaceVariant,
                        onClick = { onNavigate(Screen.Main.Pods(selectPodUid = podInfo.uid)) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(podInfo.status)))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    podInfo.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KdPrimary,
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = TextDecoration.Underline,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(podInfo.namespace, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                                    Text("·", color = KdTextSecondary)
                                    Text(podInfo.status, style = MaterialTheme.typography.labelSmall, color = statusColor(podInfo.status))
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Ready ${podInfo.ready}", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                                Text(
                                    "Restarts ${podInfo.restarts}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (podInfo.restarts > 10) KdWarning else KdTextSecondary,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                event.objectName,
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "(not found)",
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                }
            }
        }

        // ── Pod Logs (collapsible) ───────────────────────────────────────
        if (event.objectKind == "Pod" && event.objectName.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                CollapsibleSection(
                    title = "Pod Logs",
                    subtitle = "(last 50 lines)",
                ) {
                    if (podLogsLoading) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = KdPrimary)
                        }
                    } else if (podLogs != null && podLogs.isNotBlank()) {
                        Column {
                            Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
                                Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                                    podLogs.lines().take(50).forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp,
                                            ),
                                            color = KdTextPrimary,
                                        )
                                    }
                                }
                            }
                            if (podInfo != null) {
                                Text(
                                    "View full logs →",
                                    modifier = Modifier.clickable { onNavigate(Screen.Detail.PodLogs(event.objectName, event.namespace)) }.padding(vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = KdPrimary,
                                    textDecoration = TextDecoration.Underline,
                                )
                            }
                        }
                    } else {
                        Text("No logs available", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextSecondary,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: KdTextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    count: Int? = null,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = KdSurfaceVariant.copy(alpha = 0.5f),
            onClick = { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .then(if (expanded) Modifier else Modifier.clickable { expanded = true }),
                    tint = KdTextSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
                if (count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("$count", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                }
                if (subtitle != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun NodeEventItem(event: EventInfo) {
    val evTypeColor = when (event.type.lowercase()) {
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
                    .background(evTypeColor),
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
                        color = evTypeColor,
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
