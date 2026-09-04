package com.kubekubedashdash.ui.screens.pods

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.clear_all_filled
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.BulkActionDialog
import com.kubekubedashdash.ui.components.BulkRunState
import com.kubekubedashdash.ui.components.BulkSelectionBar
import com.kubekubedashdash.ui.components.BulkVerb
import com.kubekubedashdash.ui.components.BulkVerbButton
import com.kubekubedashdash.ui.components.BulkVerbs
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.KpiStrip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.activeKpiId
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.podKpiStatuses
import com.kubekubedashdash.ui.components.podKpis
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.feedback.LocalActionFeedback
import com.kubekubedashdash.ui.feedback.resourceRef
import com.kubekubedashdash.ui.screens.pods.viewmodel.PodsScreenViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PodsScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    onNavigate: (Screen) -> Unit,
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
    onOpenTerminal: (String, String, String) -> Unit = { _, _, _ -> },
    selectPodUid: String? = null,
    // When a navigation target supplies a status allowlist (e.g. the
    // cluster-health banner clicking "3 pods in error"), seed the filter
    // chip with it. The user can clear or expand it via the existing menu.
    initialStatusFilter: Set<String>? = null,
    // Seeds the row highlight when the screen is (re)created while its
    // detail pane is already open (Back/Forward restore, tab switch).
    initialSelectedUid: String? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val feedback = LocalActionFeedback.current
    val viewModel: PodsScreenViewModel = viewModel { PodsScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val stalePods by viewModel.stalePods.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selectedUids by viewModel.selection.selected.collectAsState()
    val bulkState by viewModel.bulkRunner.state.collectAsState()
    var selectedPodUid by rememberSaveable { mutableStateOf(initialSelectedUid) }
    LaunchedEffect(initialStatusFilter) {
        // Nav-driven seed: a banner click landing on Pods with a typed status
        // allowlist writes through to VM state. The persistent value is the
        // source of truth from there on.
        if (initialStatusFilter != null) viewModel.setStatusFilter(initialStatusFilter)
    }
    val pinnedIds by PreferenceRepository.pinnedResources.collectAsState()
    val scope = rememberCoroutineScope()

    var pendingDelete by remember { mutableStateOf<PodInfo?>(null) }
    val delete = rememberConfirmableAction()
    var terminalPickerPod by remember { mutableStateOf<PodInfo?>(null) }
    var logsPickerPod by remember { mutableStateOf<PodInfo?>(null) }
    var bulkVerb by remember { mutableStateOf<BulkVerb?>(null) }
    var bulkPods by remember { mutableStateOf<List<PodInfo>>(emptyList()) }
    var pendingEvict by remember { mutableStateOf<PodInfo?>(null) }
    val evict = rememberConfirmableAction()

    LaunchedEffect(selectPodUid) {
        viewModel.setParams(selectPodUid)
        if (selectPodUid != null) {
            viewModel.selectedPod.first { it != null }?.let {
                selectedPodUid = it.uid
                onNavigate(Screen.Detail.PodDetail(it))
            }
        }
    }

    val enter = expandVertically(expandFrom = Alignment.Top) + fadeIn()
    val exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()

    AnimatedVisibility(state is ResourceState.Loading, enter = enter, exit = exit) {
        SkeletonRows()
    }

    AnimatedVisibility(state is ResourceState.Error, enter = enter, exit = exit) {
        ResourceErrorMessage((state as ResourceState.Error).message)
    }

    AnimatedVisibility(state is ResourceState.Success, enter = enter, exit = exit) {
        with(state) {
            if (this is ResourceState.Success) {
                val s = this
                val allPods = s.data + stalePods.values
                val availableStatuses = remember(allPods) {
                    allPods.map { it.status }.filter { it.isNotBlank() }.toSortedSet()
                }
                val activeStatusFilter = statusFilter
                val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
                val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
                val filtered = remember(allPods, searchQuery, activeStatusFilter, labelSelector, annotationSelector) {
                    allPods
                        .filter { pod ->
                            val passesSearch = searchQuery.isBlank() ||
                                pod.name.contains(searchQuery, ignoreCase = true) ||
                                pod.namespace.contains(searchQuery, ignoreCase = true) ||
                                pod.status.contains(searchQuery, ignoreCase = true) ||
                                pod.node.contains(searchQuery, ignoreCase = true)
                            val passesStatus = activeStatusFilter == null || pod.status in activeStatusFilter
                            val passesLabels = labelSelector.isEmpty() || matchesMapSelector(pod.labels, labelSelector)
                            val passesAnnotations = annotationSelector.isEmpty() ||
                                matchesMapSelector(pod.annotations, annotationSelector)
                            passesSearch && passesStatus && passesLabels && passesAnnotations
                        }
                }

                val visibleSelectableUids = remember(filtered, stalePods) {
                    filtered.asSequence().map { it.uid }.filter { it !in stalePods.keys }.toSet()
                }
                LaunchedEffect(visibleSelectableUids) { viewModel.selection.setVisible(visibleSelectableUids) }

                Column(modifier = Modifier.fillMaxSize()) {
                    val kpiIds = remember { listOf("total", "failing", "pending", "cpu", "mem") }
                    val activeId = remember(activeStatusFilter) {
                        activeKpiId(activeStatusFilter, ::podKpiStatuses, kpiIds)
                    }
                    val kpis = remember(allPods, resourceUsage, activeId) { podKpis(allPods, resourceUsage, activeId) }
                    val kpiClickableIds = remember(activeStatusFilter) {
                        if (activeStatusFilter != null) setOf("failing", "pending", "total") else setOf("failing", "pending")
                    }
                    KpiStrip(
                        kpis = kpis,
                        activeId = activeId,
                        clickableIds = kpiClickableIds,
                        onClick = { kpi ->
                            if (kpi.id == "total" || kpi.id == activeId) {
                                viewModel.setStatusFilter(null)
                            } else {
                                viewModel.setStatusFilter(podKpiStatuses(kpi.id))
                            }
                        },
                    )
                    ResourceCountHeader(
                        count = filtered.size,
                        kind = "Pods",
                        liveDot = {
                            LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                        },
                        actions = { compact ->
                            ResourceFilterChips(
                                labelQuery = labelQuery,
                                onLabelQueryChange = onLabelQueryChange,
                                annotationQuery = annotationQuery,
                                onAnnotationQueryChange = onAnnotationQueryChange,
                                compact = compact,
                                pulseLabelsOnEntry = pulseLabelsOnEntry,
                                pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                                statusChip = {
                                    StatusFilterMenu(
                                        available = availableStatuses,
                                        selected = activeStatusFilter ?: availableStatuses,
                                        onToggle = { value ->
                                            val current = activeStatusFilter ?: availableStatuses
                                            viewModel.setStatusFilter(if (value in current) current - value else current + value)
                                        },
                                        onSelectAll = { viewModel.setStatusFilter(null) },
                                        onSelectNone = { viewModel.setStatusFilter(emptySet()) },
                                        pulseOnEntry = statusFilter != null,
                                        compact = compact,
                                        icon = Res.drawable.monitor_heart_filled,
                                    )
                                },
                                clearVisible = labelQuery.isNotBlank() || annotationQuery.isNotBlank() || statusFilter != null,
                                onClear = {
                                    onLabelQueryChange("")
                                    onAnnotationQueryChange("")
                                    viewModel.setStatusFilter(null)
                                },
                            )
                        },
                    )
                    // Exit-animation latch: the bar stays composed while it
                    // shrinks away, so without holding the last non-zero
                    // count it would flash "0 pods selected" on every Clear.
                    var lastSelectedCount by remember { mutableStateOf(0) }
                    if (selectedUids.isNotEmpty()) lastSelectedCount = selectedUids.size
                    AnimatedVisibility(selectedUids.isNotEmpty(), enter = enter, exit = exit) {
                        BulkSelectionBar(
                            selectedCount = lastSelectedCount,
                            kind = "pods",
                            onClear = { viewModel.selection.set(emptySet()) },
                        ) {
                            BulkVerbButton(
                                icon = Res.drawable.clear_all_filled,
                                label = "Evict",
                                description = "Gracefully remove the selected pods, letting their controllers " +
                                    "reschedule them — use to move pods off a node. Respects " +
                                    "PodDisruptionBudgets; requests can be rejected.",
                                tint = KdTextPrimary,
                            ) {
                                val snapshot = filtered.filter { it.uid in selectedUids && it.uid !in stalePods.keys }
                                viewModel.bulkRunner.armOrReattach(BulkVerbs.Evict, snapshot) { v, items ->
                                    bulkPods = items
                                    bulkVerb = v
                                }
                            }
                            BulkVerbButton(
                                icon = Res.drawable.delete_filled,
                                label = "Delete",
                                description = "Delete the selected pods with graceful termination. " +
                                    "Controller-managed pods are recreated; standalone pods are gone for good.",
                                tint = KdError,
                            ) {
                                val snapshot = filtered.filter { it.uid in selectedUids && it.uid !in stalePods.keys }
                                viewModel.bulkRunner.armOrReattach(BulkVerbs.Delete, snapshot) { v, items ->
                                    bulkPods = items
                                    bulkVerb = v
                                }
                            }
                        }
                    }
                    PodTable(
                        pods = filtered,
                        selectedUid = selectedPodUid,
                        onPodClick = { pod ->
                            selectedPodUid = pod.uid
                            onNavigate(Screen.Detail.PodDetail(pod))
                        },
                        onViewLogs = { pod ->
                            when {
                                pod.containers.size == 1 ->
                                    onOpenLogs(pod.name, pod.namespace, pod.containers.first().name)

                                pod.containers.size > 1 -> logsPickerPod = pod

                                else -> onOpenLogs(pod.name, pod.namespace, null)
                            }
                        },
                        onOpenTerminal = { pod ->
                            when {
                                pod.containers.size == 1 ->
                                    onOpenTerminal(pod.name, pod.namespace, pod.containers.first().name)

                                pod.containers.size > 1 -> terminalPickerPod = pod
                                // pod.containers.isEmpty() is unexpected for a running pod
                            }
                        },
                        onDelete = { pod ->
                            pendingDelete = pod
                            delete.clearError()
                        },
                        pinnedIds = pinnedIds,
                        onTogglePin = { id -> scope.launch { PreferenceRepository.togglePinned(id) } },
                        staleUids = stalePods.keys,
                        selectedUids = selectedUids,
                        onSelectionChange = viewModel.selection::set,
                        onEvict = { pod ->
                            evict.clearError()
                            pendingEvict = pod
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { pod ->
        DeleteConfirmDialog(
            kind = "Pod",
            name = pod.name,
            namespace = pod.namespace,
            inFlight = delete.inFlight,
            errorMessage = delete.error,
            onConfirm = {
                delete.run(
                    failureMessage = "Delete failed",
                    block = { reactiveClient.actions.deleteResource("pod", pod.name, pod.namespace) },
                    onSuccess = {
                        pendingDelete = null
                        feedback.success("Deleted Pod ${resourceRef(pod.name, pod.namespace)}")
                    },
                )
            },
            onDismiss = {
                pendingDelete = null
                delete.clearError()
            },
        )
    }

    bulkVerb?.let { verb ->
        val podLabel: (PodInfo) -> String = { "${it.namespace}/${it.name}" }
        BulkActionDialog(
            verb = verb,
            items = bulkPods,
            itemLabel = podLabel,
            kindSingular = "Pod",
            kindPlural = "Pods",
            confirmBody = if (verb == BulkVerbs.Delete) {
                "Delete ${bulkPods.size} pods? This cannot be undone."
            } else {
                "Evict ${bulkPods.size} pods? Each pod is gracefully removed and rescheduled by its " +
                    "controller. Evictions respect PodDisruptionBudgets and may be rejected."
            },
            runState = bulkState,
            onConfirm = {
                // A false return means a run is already in flight; the dialog
                // is already rendering that run's live state via bulkState.
                viewModel.bulkRunner.start(verb, bulkPods, podLabel) { pod ->
                    when (verb) {
                        BulkVerbs.Evict -> reactiveClient.actions.evictPod(pod.name, pod.namespace)

                        BulkVerbs.Delete -> reactiveClient.actions.deleteResource("pod", pod.name, pod.namespace)

                        // Destructive verbs are named explicitly — a future verb
                        // must fail loudly here, never fall through to delete.
                        else -> Result.failure(IllegalStateException("Unsupported bulk verb: ${verb.actionLabel}"))
                    }
                }
            },
            onCancelRun = { viewModel.bulkRunner.cancel() },
            onDismiss = {
                val finished = bulkState as? BulkRunState.Finished
                if (finished != null) {
                    // Retry affordance: keep exactly the failed pods selected.
                    // Safe: SelectionFunnel.set intersects with the visible set.
                    viewModel.selection.set(finished.failures.map { it.item.uid }.toSet())
                    viewModel.bulkRunner.clear()
                }
                bulkVerb = null
                bulkPods = emptyList()
            },
        )
    }

    pendingEvict?.let { pod ->
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
                    block = { reactiveClient.actions.evictPod(pod.name, pod.namespace) },
                    onSuccess = {
                        pendingEvict = null
                        feedback.success("Evicted Pod ${resourceRef(pod.name, pod.namespace)}")
                    },
                )
            },
            onDismiss = {
                if (!evict.inFlight) {
                    pendingEvict = null
                    evict.clearError()
                }
            },
        )
    }

    terminalPickerPod?.let { pod ->
        TerminalContainerPickerDialog(
            pod = pod,
            onPick = { container ->
                terminalPickerPod = null
                onOpenTerminal(pod.name, pod.namespace, container)
            },
            onDismiss = { terminalPickerPod = null },
        )
    }

    logsPickerPod?.let { pod ->
        TerminalContainerPickerDialog(
            pod = pod,
            title = "Stream logs from container",
            onPick = { container ->
                logsPickerPod = null
                onOpenLogs(pod.name, pod.namespace, container)
            },
            onDismiss = { logsPickerPod = null },
        )
    }
}
