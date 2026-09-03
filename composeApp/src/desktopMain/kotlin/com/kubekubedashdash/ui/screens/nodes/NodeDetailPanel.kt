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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.clear_all_filled
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.event_note_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.view_list_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.KeyValueChipFlow
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.TooltipIconButton
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.components.restartCountColor
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.ui.feedback.UndoAction
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.DetailFieldsCard
import com.kubekubedashdash.ui.screens.DetailPanelHeader
import com.kubekubedashdash.ui.screens.GenericYamlTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private enum class NodeDetailTab(val label: String, val icon: DrawableResource) {
    Overview("Overview", Res.drawable.info_filled),
    Pods("Pods", Res.drawable.view_list_filled),
    Events("Events", Res.drawable.event_note_filled),
    Yaml("YAML", Res.drawable.code_filled),
}

private val nodeTallTabs = listOf(NodeDetailTab.Overview, NodeDetailTab.Yaml)
private val nodeCompactTabs = NodeDetailTab.entries.toList()

@Composable
internal fun NodeDetailPanel(
    node: NodeInfo,
    onClose: () -> Unit,
    onPodClick: (PodInfo) -> Unit,
    modifier: Modifier = Modifier,
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
) {
    val kubeClient = LocalReactiveKubeClient.current
    val feedback = LocalActionFeedback.current
    var activeTab by remember { mutableStateOf(NodeDetailTab.Overview) }
    val scope = rememberCoroutineScope()

    var pods by remember(node.name) { mutableStateOf<List<PodInfo>>(emptyList()) }
    var podsLoading by remember(node.name) { mutableStateOf(true) }
    var events by remember(node.name) { mutableStateOf<List<EventInfo>>(emptyList()) }
    var eventsLoading by remember(node.name) { mutableStateOf(true) }

    // ── Cordon / Uncordon dialog state ─────────────────────────────────────────
    var showCordonDialog by remember(node.uid) { mutableStateOf(false) }
    val cordon = rememberConfirmableAction()

    // ── Drain dialog state ─────────────────────────────────────────────────────
    var showDrainDialog by remember(node.uid) { mutableStateOf(false) }
    val drain = rememberConfirmableAction()
    var drainStatus by remember(node.uid) { mutableStateOf<String?>(null) }

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
            val pagerState = rememberPagerState(pageCount = { tabs.size })

            LaunchedEffect(node.uid) {
                activeTab = NodeDetailTab.Overview
                pagerState.scrollToPage(0)
            }

            LaunchedEffect(isTall) {
                if (isTall && activeTab != NodeDetailTab.Overview && activeTab != NodeDetailTab.Yaml) {
                    activeTab = NodeDetailTab.Overview
                    pagerState.scrollToPage(0)
                }
            }

            LaunchedEffect(pagerState, tabs) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    tabs.getOrNull(page)?.let { activeTab = it }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                val cordonAction = DetailAction(
                    icon = if (node.unschedulable) Res.drawable.check_circle_filled else Res.drawable.lock_filled,
                    label = if (node.unschedulable) "Uncordon" else "Cordon",
                    tint = if (node.unschedulable) KdWarning else null,
                    description = if (node.unschedulable) {
                        "Allow pods to be scheduled here again — reverses a cordon once the node is healthy."
                    } else {
                        "Stop new pods from scheduling on this node (existing ones keep running) — to quarantine a flaky node."
                    },
                    enabled = !cordon.inFlight && !drain.inFlight,
                    onClick = {
                        cordon.clearError()
                        showCordonDialog = true
                    },
                )
                val drainAction = DetailAction(
                    icon = Res.drawable.clear_all_filled,
                    label = "Drain",
                    description = "Cordon the node and move its pods elsewhere — to safely empty it before maintenance or shutdown.",
                    enabled = !cordon.inFlight && !drain.inFlight,
                    onClick = {
                        drain.clearError()
                        drainStatus = null
                        showDrainDialog = true
                    },
                )
                DetailPanelHeader(
                    name = node.name,
                    subtitle = "Node",
                    status = node.status,
                    actions = listOf(cordonAction, drainAction),
                    onClose = onClose,
                )

                SecondaryTabRow(
                    selectedTabIndex = tabs.indexOf(activeTab).coerceAtLeast(0),
                    containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
                    contentColor = KdPrimary,
                    divider = { HorizontalDivider(color = KdBorder) },
                ) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab == activeTab,
                            onClick = {
                                activeTab = tab
                                scope.launch {
                                    pagerState.animateScrollToPage(tabs.indexOf(tab).coerceAtLeast(0))
                                }
                            },
                            selectedContentColor = KdPrimary,
                            unselectedContentColor = KdTextSecondary,
                        ) {
                            Row(modifier = Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(tab.icon), null, Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(tab.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (tabs[page]) {
                        NodeDetailTab.Overview -> {
                            if (isTall) {
                                NodeOverviewCombinedTab(
                                    node = node,
                                    pods = pods,
                                    podsLoading = podsLoading,
                                    events = events,
                                    eventsLoading = eventsLoading,
                                    onPodClick = onPodClick,
                                    labelQuery = labelQuery,
                                    onToggleLabel = onToggleLabel,
                                    annotationQuery = annotationQuery,
                                    onToggleAnnotation = onToggleAnnotation,
                                )
                            } else {
                                NodeDetailsOnlyTab(
                                    node = node,
                                    labelQuery = labelQuery,
                                    onToggleLabel = onToggleLabel,
                                    annotationQuery = annotationQuery,
                                    onToggleAnnotation = onToggleAnnotation,
                                )
                            }
                        }

                        NodeDetailTab.Pods -> NodePodsTab(pods, podsLoading, onPodClick)

                        NodeDetailTab.Events -> NodeEventsTab(events, eventsLoading)

                        NodeDetailTab.Yaml -> GenericYamlTab("Node", node.name, null)
                    }
                }
            }
        }

        // ── Cordon / Uncordon dialog ───────────────────────────────────────────
        if (showCordonDialog) {
            val targetUnschedulable = !node.unschedulable
            ConfirmActionDialog(
                title = if (targetUnschedulable) "Cordon Node" else "Uncordon Node",
                body = if (targetUnschedulable) {
                    "Mark \"${node.name}\" as unschedulable? No new pods will be scheduled on this node."
                } else {
                    "Mark \"${node.name}\" as schedulable again? New pods may be scheduled on this node."
                },
                confirmLabel = if (targetUnschedulable) "Cordon" else "Uncordon",
                destructive = false,
                inFlight = cordon.inFlight,
                errorMessage = cordon.error,
                onConfirm = {
                    cordon.run(
                        failureMessage = "Operation failed",
                        block = { kubeClient.actions.cordonNode(node.name, targetUnschedulable) },
                        onSuccess = {
                            showCordonDialog = false
                            val verb = if (targetUnschedulable) "Cordoned" else "Uncordoned"
                            val inverse = if (targetUnschedulable) "Uncordoned" else "Cordoned"
                            val stillState = if (targetUnschedulable) "cordoned" else "schedulable"
                            feedback.success(
                                "$verb Node \"${node.name}\"",
                                undo = UndoAction(
                                    successTitle = "$inverse Node \"${node.name}\"",
                                    failureTitle = "Undo failed: Node \"${node.name}\" is still $stillState",
                                ) {
                                    kubeClient.actions.cordonNode(node.name, !targetUnschedulable)
                                },
                            )
                        },
                    )
                },
                onDismiss = {
                    if (!cordon.inFlight) {
                        showCordonDialog = false
                        cordon.clearError()
                    }
                },
            )
        }

        // ── Drain dialog ───────────────────────────────────────────────────────
        if (showDrainDialog) {
            val podCount = if (podsLoading) "?" else "${pods.size}"
            ConfirmActionDialog(
                title = "Drain Node",
                body = "Drain \"${node.name}\"? This cordons the node and evicts pods (~$podCount pods). " +
                    "DaemonSet and mirror/static pods are skipped. " +
                    (drainStatus ?: ""),
                confirmLabel = "Drain",
                destructive = true,
                inFlight = drain.inFlight,
                errorMessage = drain.error,
                onConfirm = {
                    drainStatus = null
                    drain.run(
                        failureMessage = "Drain failed",
                        block = { kubeClient.actions.drainNode(node.name) },
                        onSuccess = { dr ->
                            drainStatus = "Evicted ${dr.evicted}, skipped ${dr.skipped}, failed ${dr.failed}."
                            showDrainDialog = false
                            val counts = "Evicted ${dr.evicted}, skipped ${dr.skipped}, failed ${dr.failed}"
                            if (dr.failed == 0) {
                                feedback.success("Drained Node \"${node.name}\"", detail = counts)
                            } else {
                                val pods = if (dr.failed == 1) "1 pod" else "${dr.failed} pods"
                                feedback.warning("Node \"${node.name}\" not fully drained — $pods could not be evicted", detail = counts)
                            }
                        },
                    )
                },
                onDismiss = {
                    if (!drain.inFlight) {
                        showDrainDialog = false
                        drain.clearError()
                    }
                },
            )
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
    labelQuery: String,
    onToggleLabel: (String, String) -> Unit,
    annotationQuery: String,
    onToggleAnnotation: (String, String) -> Unit,
) {
    val activeLabels = remember(labelQuery) { parseMapSelector(labelQuery) }
    val activeAnnotations = remember(annotationQuery) { parseMapSelector(annotationQuery) }
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
            DetailFieldsCard(fields = fields)
        }

        // ── Labels ──────────────────────────────────────────────────────────
        if (node.labels.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Labels", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                KeyValueChipFlow(
                    entries = node.labels,
                    activeFilter = activeLabels,
                    onToggle = onToggleLabel,
                )
            }
        }

        // ── Annotations ─────────────────────────────────────────────────────
        if (node.annotations.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Annotations", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                KeyValueChipFlow(
                    entries = node.annotations,
                    activeFilter = activeAnnotations,
                    onToggle = onToggleAnnotation,
                )
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
private fun NodeDetailsOnlyTab(
    node: NodeInfo,
    labelQuery: String,
    onToggleLabel: (String, String) -> Unit,
    annotationQuery: String,
    onToggleAnnotation: (String, String) -> Unit,
) {
    val activeLabels = remember(labelQuery) { parseMapSelector(labelQuery) }
    val activeAnnotations = remember(annotationQuery) { parseMapSelector(annotationQuery) }
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
            DetailFieldsCard(fields = fields)
        }

        if (node.labels.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Labels", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                KeyValueChipFlow(
                    entries = node.labels,
                    activeFilter = activeLabels,
                    onToggle = onToggleLabel,
                )
            }
        }

        if (node.annotations.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Annotations", style = MaterialTheme.typography.labelLarge, color = KdTextPrimary, fontWeight = FontWeight.SemiBold)
            }
            item {
                KeyValueChipFlow(
                    entries = node.annotations,
                    activeFilter = activeAnnotations,
                    onToggle = onToggleAnnotation,
                )
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
                    StatusBadge(pod.status)
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
                        color = restartCountColor(pod.restarts) ?: KdTextSecondary,
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
