package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.ContainerTermination
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.article_filled
import com.kubekubedashdash.resources.clear_all_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.event_note_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.resources.terminal_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.EMPTY_DASH
import com.kubekubedashdash.ui.components.KeyValueChipFlow
import com.kubekubedashdash.ui.components.MetricsLineChart
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.StatusBadge
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.components.restartCountColor
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.ui.feedback.resourceRef
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.ui.screens.DetailActionMenuItem
import com.kubekubedashdash.ui.screens.DetailPanelHeader
import com.kubekubedashdash.ui.screens.GenericYamlTab
import com.kubekubedashdash.ui.screens.RelatedSection
import com.kubekubedashdash.ui.screens.events.EventListItem
import com.kubekubedashdash.ui.screens.events.eventsForObject
import com.kubekubedashdash.ui.screens.events.warningEventCount
import com.kubekubedashdash.ui.screens.relatedScreen
import com.kubekubedashdash.ui.screens.rememberRelated
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.RelatedResources
import com.kubekubedashdash.util.formatAge
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import com.kubekubedashdash.util.restartListFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import java.time.Instant

// How often the panel's relative times ("Last seen 30s", "Finished 5m ago") re-render.
private const val AGE_TICK_MS = 10_000L

private enum class DetailTab(val label: String, val icon: DrawableResource) {
    Overview("Overview", Res.drawable.info_filled),
    Events("Events", Res.drawable.event_note_filled),
    Yaml("YAML", Res.drawable.code_filled),
}

private val detailTabs = DetailTab.entries.toList()

@Composable
fun PodDetailPanel(
    pod: PodInfo,
    onClose: () -> Unit,
    onNavigateToNode: ((nodeName: String) -> Unit)? = null,
    onNavigate: (Screen) -> Unit = {},
    onOpenLogs: (podName: String, namespace: String, container: String?) -> Unit = { _, _, _ -> },
    onOpenTerminal: (podName: String, namespace: String, container: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
) {
    val kubeClient = LocalReactiveKubeClient.current
    val feedback = LocalActionFeedback.current
    var activeTab by remember { mutableStateOf(DetailTab.Overview) }
    var metricsHistory by remember(pod.uid) { mutableStateOf(listOf<PodMetricsSnapshot>()) }
    val scope = rememberCoroutineScope()

    // D4: the one kind that needs every flow to walk past its immediate
    // owner — see rememberRelated's "Pod" branch.
    val related = rememberRelated(
        client = kubeClient,
        kind = "Pod",
        uid = pod.uid,
        namespace = pod.namespace,
        labels = pod.labels,
        owners = pod.owners,
    )

    // Events come from the cached namespace informer that the cluster-health
    // flow already keeps running — no API call. Deriving on the flow and
    // de-duplicating BEFORE collecting means the panel recomposes only when
    // this pod's own events change, not on every cluster-wide emission.
    // The informer follows the namespace filter, so a pod outside that scope
    // (filter switched with the panel open) has no events in it; the tab
    // says so instead of claiming "No events".
    val podEventsState by remember(kubeClient, pod.uid, pod.name, pod.namespace) {
        kubeClient.events
            .map { state ->
                when (state) {
                    ResourceState.Loading -> ResourceState.Loading

                    is ResourceState.Error -> state

                    is ResourceState.Success -> ResourceState.Success(
                        eventsForObject(state.data, kind = "Pod", name = pod.name, namespace = pod.namespace, uid = pod.uid),
                    )
                }
            }
            .distinctUntilChanged()
    }.collectAsState(ResourceState.Loading)
    val selectedNamespace by kubeClient.selectedNamespace.collectAsState()
    val eventsInScope = selectedNamespace == null || selectedNamespace == pod.namespace
    val warningCount = if (eventsInScope) {
        warningEventCount((podEventsState as? ResourceState.Success)?.data ?: emptyList())
    } else {
        0
    }

    // One clock for every relative time in the panel. The informer only
    // re-emits on change, so "Last seen 30s" or "Finished 5m ago" would
    // otherwise freeze while a crash-looping pod's panel stays open.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = Instant.now()
        }
    }

    // ── Evict dialog state ─────────────────────────────────────────────────────
    var showEvictDialog by remember(pod.uid) { mutableStateOf(false) }
    val evict = rememberConfirmableAction()

    // ── Force-Delete dialog state ──────────────────────────────────────────────
    var showForceDeleteDialog by remember(pod.uid) { mutableStateOf(false) }
    val forceDelete = rememberConfirmableAction()

    LaunchedEffect(pod.uid) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                kubeClient.getPodMetrics(pod.name, pod.namespace)
            }
            if (snapshot != null) {
                metricsHistory = (metricsHistory + snapshot).takeLast(60)
            }
            delay(5_000)
        }
    }

    Surface(modifier = modifier, color = KdSurface) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val tabs = detailTabs
            val pagerState = rememberPagerState(pageCount = { tabs.size })

            LaunchedEffect(pod.uid) {
                activeTab = DetailTab.Overview
                pagerState.scrollToPage(0)
            }

            LaunchedEffect(pagerState, tabs) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    tabs.getOrNull(page)?.let { activeTab = it }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                PanelHeader(
                    pod = pod,
                    onClose = onClose,
                    actionsEnabled = !evict.inFlight && !forceDelete.inFlight,
                    onEvictClick = {
                        evict.clearError()
                        showEvictDialog = true
                    },
                    onForceDeleteClick = {
                        forceDelete.clearError()
                        showForceDeleteDialog = true
                    },
                    onOpenLogs = onOpenLogs,
                    onOpenTerminal = onOpenTerminal,
                    ownerChain = related.owners,
                    onOwnerClick = { ref -> relatedScreen(ref)?.let(onNavigate) },
                )
                PanelTabs(activeTab, tabs, warningCount) { newTab ->
                    activeTab = newTab
                    scope.launch {
                        pagerState.animateScrollToPage(tabs.indexOf(newTab).coerceAtLeast(0))
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (tabs[page]) {
                        DetailTab.Overview -> OverviewTab(
                            pod = pod,
                            metricsHistory = metricsHistory,
                            onNavigateToNode = onNavigateToNode,
                            related = related,
                            onNavigate = onNavigate,
                            labelQuery = labelQuery,
                            onToggleLabel = onToggleLabel,
                            annotationQuery = annotationQuery,
                            onToggleAnnotation = onToggleAnnotation,
                            now = now,
                        )

                        DetailTab.Events -> PodEventsTab(
                            state = podEventsState,
                            inScope = eventsInScope,
                            podNamespace = pod.namespace,
                            onRetry = { restartListFlow(kubeClient.events) },
                            // Like every other cross-kind link: land on the
                            // Events screen with the row selected, which opens
                            // the detail itself (ClusterOverview does the same).
                            onEventClick = { ev -> onNavigate(Screen.Main.Events(selectEventUid = ev.uid)) },
                            now = now,
                        )

                        DetailTab.Yaml -> GenericYamlTab("Pod", pod.name, pod.namespace)
                    }
                }
            }
        }

        // ── Evict dialog ───────────────────────────────────────────────────────
        if (showEvictDialog) {
            ConfirmActionDialog(
                title = "Evict Pod",
                body = "Evict \"${pod.name}\" from namespace \"${pod.namespace}\"? " +
                    "The pod will be gracefully removed — its controller will reschedule it on another node. " +
                    "This respects PodDisruptionBudgets and may be rejected if disruption is not allowed.",
                confirmLabel = "Evict",
                destructive = false,
                inFlight = evict.inFlight,
                errorMessage = evict.error,
                onConfirm = {
                    evict.run(
                        failureMessage = "Eviction failed",
                        block = { kubeClient.actions.evictPod(pod.name, pod.namespace) },
                        onSuccess = {
                            showEvictDialog = false
                            feedback.success("Evicted Pod ${resourceRef(pod.name, pod.namespace)}")
                        },
                    )
                },
                onDismiss = {
                    if (!evict.inFlight) {
                        showEvictDialog = false
                        evict.clearError()
                    }
                },
            )
        }

        // ── Force-Delete dialog ────────────────────────────────────────────────
        if (showForceDeleteDialog) {
            ConfirmActionDialog(
                title = "Force Delete Pod",
                body = "Force-delete \"${pod.name}\" from namespace \"${pod.namespace}\"? " +
                    "This immediately removes the pod with grace period 0, bypassing graceful shutdown. " +
                    "Only use this for a stuck or unresponsive pod — it can orphan volumes and open connections.",
                confirmLabel = "Force Delete",
                destructive = true,
                inFlight = forceDelete.inFlight,
                errorMessage = forceDelete.error,
                onConfirm = {
                    forceDelete.run(
                        failureMessage = "Force delete failed",
                        block = { kubeClient.actions.forceDeletePod(pod.name, pod.namespace) },
                        onSuccess = {
                            showForceDeleteDialog = false
                            feedback.success("Force-deleted Pod ${resourceRef(pod.name, pod.namespace)}")
                        },
                    )
                },
                onDismiss = {
                    if (!forceDelete.inFlight) {
                        showForceDeleteDialog = false
                        forceDelete.clearError()
                    }
                },
            )
        }
    }
}

// ── Header ──────────────────────────────────────────────────────────────────────

@Composable
private fun PanelHeader(
    pod: PodInfo,
    onClose: () -> Unit,
    actionsEnabled: Boolean,
    onEvictClick: () -> Unit,
    onForceDeleteClick: () -> Unit,
    onOpenLogs: (podName: String, namespace: String, container: String?) -> Unit,
    onOpenTerminal: (podName: String, namespace: String, container: String) -> Unit,
    ownerChain: List<RelatedRef>,
    onOwnerClick: (RelatedRef) -> Unit,
) {
    val containers = pod.containers
    val terminalAction = DetailAction(
        icon = Res.drawable.terminal_filled,
        label = "Terminal",
        description = "Open an interactive shell in a container of this pod, docked at the bottom of the window.",
        enabled = containers.isNotEmpty(),
        onClick = { containers.firstOrNull()?.let { onOpenTerminal(pod.name, pod.namespace, it.name) } },
        menuItems = if (containers.size > 1) {
            containers.map { c ->
                DetailActionMenuItem(label = c.name, onClick = { onOpenTerminal(pod.name, pod.namespace, c.name) })
            }
        } else {
            emptyList()
        },
    )
    val logsAction = DetailAction(
        icon = Res.drawable.article_filled,
        label = "Logs",
        description = "Stream this pod's logs in the log viewer, docked at the bottom of the window.",
        onClick = { onOpenLogs(pod.name, pod.namespace, containers.firstOrNull()?.name) },
        menuItems = if (containers.size > 1) {
            containers.map { c ->
                DetailActionMenuItem(label = c.name, onClick = { onOpenLogs(pod.name, pod.namespace, c.name) })
            }
        } else {
            emptyList()
        },
    )
    val evictAction = DetailAction(
        icon = Res.drawable.clear_all_filled,
        label = "Evict",
        description = "Gracefully remove this pod — respects PodDisruptionBudgets and lets its controller reschedule it (use to move a pod off its node).",
        enabled = actionsEnabled,
        onClick = onEvictClick,
    )
    val forceDeleteAction = DetailAction(
        icon = Res.drawable.delete_filled,
        label = "Force delete",
        tint = KdError,
        destructive = true,
        description = "Immediately delete this pod (grace period 0) — only for a stuck/unresponsive pod; skips graceful shutdown and can orphan resources.",
        enabled = actionsEnabled,
        onClick = onForceDeleteClick,
    )
    DetailPanelHeader(
        name = pod.name,
        subtitle = pod.namespace,
        status = pod.status,
        actions = listOf(terminalAction, logsAction, evictAction, forceDeleteAction),
        onClose = onClose,
        ownerChain = ownerChain,
        onOwnerClick = onOwnerClick,
    )
}

// ── Tab Bar ─────────────────────────────────────────────────────────────────────

@Composable
private fun PanelTabs(activeTab: DetailTab, tabs: List<DetailTab>, warningCount: Int, onTabChange: (DetailTab) -> Unit) {
    SecondaryTabRow(
        selectedTabIndex = tabs.indexOf(activeTab).coerceAtLeast(0),
        containerColor = KdSurfaceVariant.copy(alpha = 0.5f),
        contentColor = KdPrimary,
        divider = { HorizontalDivider(color = KdBorder) },
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == activeTab,
                onClick = { onTabChange(tab) },
                selectedContentColor = KdPrimary,
                unselectedContentColor = KdTextSecondary,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(tab.icon), null, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelMedium)
                    if (tab == DetailTab.Events && warningCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = KdWarning.copy(alpha = 0.18f)) {
                            Text(
                                "$warningCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = KdWarning,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Overview Tab ────────────────────────────────────────────────────────────────

@Composable
private fun OverviewTab(
    pod: PodInfo,
    metricsHistory: List<PodMetricsSnapshot>,
    onNavigateToNode: ((String) -> Unit)? = null,
    related: RelatedResources,
    onNavigate: (Screen) -> Unit,
    labelQuery: String,
    onToggleLabel: (String, String) -> Unit,
    annotationQuery: String,
    onToggleAnnotation: (String, String) -> Unit,
    now: Instant,
) {
    val activeLabels = remember(labelQuery) { parseMapSelector(labelQuery) }
    val activeAnnotations = remember(annotationQuery) { parseMapSelector(annotationQuery) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (metricsHistory.isNotEmpty()) {
            PodMetricsSection(metricsHistory)
        }

        SectionCard("Pod Info") {
            InfoRow("Status", pod.status, statusColor(pod.status))
            if (pod.statusReason.isNotBlank()) InfoRow("Reason", pod.statusReason, KdError)
            if (pod.statusMessage.isNotBlank()) DetailMessage(pod.statusMessage)
            if (pod.schedulingMessage.isNotBlank()) {
                InfoRow("Scheduled", "No", KdWarning)
                DetailMessage(pod.schedulingMessage)
            }
            InfoRow("Namespace", pod.namespace)
            if (pod.node == NONE_PLACEHOLDER) {
                InfoRow("Node", NONE_PLACEHOLDER)
            } else {
                ClickableInfoRow("Node", pod.node) { onNavigateToNode?.invoke(pod.node) }
            }
            InfoRow("IP", pod.ip)
            InfoRow("Ready", pod.ready)
            InfoRow("Restarts", "${pod.restarts}", restartCountColor(pod.restarts))
            InfoRow("Age", pod.age)
        }

        SectionLabel("Containers (${pod.containers.size})")
        pod.containers.forEach { container -> ContainerCard(container, now) }

        if (pod.labels.isNotEmpty()) {
            SectionLabel("Labels")
            KeyValueChipFlow(
                entries = pod.labels,
                activeFilter = activeLabels,
                onToggle = onToggleLabel,
            )
        }

        if (pod.annotations.isNotEmpty()) {
            SectionLabel("Annotations")
            KeyValueChipFlow(
                entries = pod.annotations,
                activeFilter = activeAnnotations,
                onToggle = onToggleAnnotation,
            )
        }

        RelatedSection(related, onNavigate)
    }
}

// ── Events Tab ──────────────────────────────────────────────────────────────────

@Composable
private fun PodEventsTab(
    state: ResourceState<List<EventInfo>>,
    inScope: Boolean,
    podNamespace: String,
    onRetry: () -> Unit,
    onEventClick: (EventInfo) -> Unit,
    now: Instant,
) {
    when {
        !inScope -> CenteredNote(
            title = "Not watching namespace “$podNamespace”",
            body = "Switch the namespace filter to “$podNamespace” or All Namespaces to see this pod's events.",
        )

        state is ResourceState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = KdPrimary)
        }

        state is ResourceState.Error -> ResourceErrorMessage(state.message, onRetry = onRetry)

        state is ResourceState.Success && state.data.isEmpty() -> CenteredNote(
            title = "No events for this pod",
            body = "Kubernetes discards events after about an hour by default.",
        )

        state is ResourceState.Success -> {
            val events = state.data
            val rows = remember(events, now) { events.map { it.copy(lastSeen = formatAge(it.lastSeenTimestamp, now)) } }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { SectionLabel("${rows.size} Events") }
                items(rows.size) { i -> EventListItem(rows[i], onClick = { onEventClick(rows[i]) }) }
            }
        }
    }
}

@Composable
private fun CenteredNote(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
    }
}

private val KdMemoryColor = Color(0xFF8B5CF6)

@Composable
private fun PodMetricsSection(metricsHistory: List<PodMetricsSnapshot>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Resource Usage")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cpuValues = metricsHistory.map { it.cpuMillis }
            MetricsLineChart(
                values = cpuValues,
                label = "CPU",
                currentText = if (cpuValues.isNotEmpty()) formatCpuCores(cpuValues.last()) else "\u2014",
                formatValue = ::formatCpuCores,
                lineColor = KdPrimary,
                modifier = Modifier.weight(1f),
            )

            val memValues = metricsHistory.map { it.memoryBytes }
            MetricsLineChart(
                values = memValues,
                label = "Memory",
                currentText = if (memValues.isNotEmpty()) formatMemorySize(memValues.last()) else "\u2014",
                formatValue = ::formatMemorySize,
                lineColor = KdMemoryColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel(title)
        Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = KdTextPrimary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        if (valueColor != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(valueColor))
                Spacer(Modifier.width(5.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor, fontWeight = FontWeight.Medium)
            }
        } else {
            val isNone = value == NONE_PLACEHOLDER
            Text(
                if (isNone) EMPTY_DASH else value,
                style = MaterialTheme.typography.bodySmall,
                color = if (isNone) KdTextSecondary else KdTextPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ClickableInfoRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
            color = KdPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ContainerCard(container: ContainerInfo, now: Instant) {
    Surface(shape = RoundedCornerShape(8.dp), color = KdSurfaceVariant) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(container.state)
                Spacer(Modifier.width(8.dp))
                Text(
                    container.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Image", container.image)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("State", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                StatusBadge(container.state)
            }
            if (container.stateMessage.isNotBlank()) DetailMessage(container.stateMessage)
            container.exitCode?.let { code -> InfoRow("Exit code", "$code", if (code == 0) null else KdError) }
            InfoRow(
                "Ready",
                if (container.ready) "Yes" else "No",
                if (container.ready) KdSuccess else KdError,
            )
            InfoRow("Restarts", "${container.restartCount}")
            container.lastTermination?.let { t -> LastTerminationRows(t, now) }
        }
    }
}

/** The previous run's end: the "why" behind a crash-looping container's restarts. */
@Composable
private fun LastTerminationRows(t: ContainerTermination, now: Instant) {
    val summary = listOfNotNull(t.reason.ifBlank { null }, "exit ${t.exitCode}").joinToString(" · ")
    InfoRow("Last termination", summary, if (t.exitCode == 0) null else KdError)
    if (t.finishedAt.isNotBlank()) InfoRow("Finished", "${formatAge(t.finishedAt, now)} ago")
    if (t.message.isNotBlank()) DetailMessage(t.message)
}

/** A Kubernetes status or termination message, under the row it explains. Long, so wrapped and capped. */
@Composable
private fun DetailMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = KdTextSecondary,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
    )
}
