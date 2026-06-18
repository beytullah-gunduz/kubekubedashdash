package com.kubekubedashdash.ui.screens.pods

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.monitor_heart_filled
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
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
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: PodsScreenViewModel = viewModel { PodsScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val stalePods by viewModel.stalePods.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    var statsExpanded by remember { mutableStateOf(true) }
    var selectedPodUid by rememberSaveable { mutableStateOf<String?>(null) }
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
                val filtered = remember(allPods, searchQuery, activeStatusFilter, labelSelector, annotationSelector, pinnedIds) {
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
                        .sortedByDescending { pod -> "pod:${pod.namespace}:${pod.name}" in pinnedIds }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val showStats = maxWidth >= 900.dp
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = showStats,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            PodStatsPanel(
                                pods = allPods,
                                usage = resourceUsage,
                                expanded = statsExpanded,
                                onToggle = { statsExpanded = !statsExpanded },
                            )
                        }
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
                        PodTable(
                            pods = filtered,
                            selectedUid = selectedPodUid,
                            onPodClick = { pod ->
                                selectedPodUid = pod.uid
                                onNavigate(Screen.Detail.PodDetail(pod))
                            },
                            onViewLogs = { pod -> onOpenLogs(pod.name, pod.namespace, null) },
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
                        )
                    }
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
                    onSuccess = { pendingDelete = null },
                )
            },
            onDismiss = {
                pendingDelete = null
                delete.clearError()
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
}
