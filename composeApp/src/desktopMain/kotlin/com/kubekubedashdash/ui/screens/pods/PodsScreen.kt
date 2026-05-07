package com.kubekubedashdash.ui.screens.pods

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.screens.pods.viewmodel.PodsScreenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PodsScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    onOpenLogs: (String, String, String?) -> Unit = { _, _, _ -> },
    selectPodUid: String? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: PodsScreenViewModel = viewModel { PodsScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val stalePods by viewModel.stalePods.collectAsState()
    var statsExpanded by remember { mutableStateOf(true) }
    var selectedPodUid by rememberSaveable { mutableStateOf<String?>(null) }
    // null sentinel = "no filter applied" (show every status that appears).
    // A non-null Set is the explicit allowlist after the user touched the menu.
    var statusFilter by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    val pinnedIds by PreferenceRepository.pinnedResources.collectAsState()
    val scope = rememberCoroutineScope()

    var pendingDelete by remember { mutableStateOf<PodInfo?>(null) }
    var deleteInFlight by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

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
                            actions = {
                                LabelSelectorChip(
                                    query = labelQuery,
                                    onQueryChange = onLabelQueryChange,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                AnnotationSelectorChip(
                                    query = annotationQuery,
                                    onQueryChange = onAnnotationQueryChange,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                StatusFilterMenu(
                                    available = availableStatuses,
                                    selected = activeStatusFilter ?: availableStatuses,
                                    onToggle = { value ->
                                        val current = activeStatusFilter ?: availableStatuses
                                        statusFilter = if (value in current) current - value else current + value
                                    },
                                    onSelectAll = { statusFilter = null },
                                    onSelectNone = { statusFilter = emptySet() },
                                )
                                AnimatedVisibility(
                                    visible = labelQuery.isNotBlank() || annotationQuery.isNotBlank(),
                                    enter = expandHorizontally() + fadeIn(),
                                    exit = shrinkHorizontally() + fadeOut(),
                                ) {
                                    ClearFiltersChip(
                                        onClick = {
                                            onLabelQueryChange("")
                                            onAnnotationQueryChange("")
                                        },
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
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
                            onDelete = { pod ->
                                pendingDelete = pod
                                deleteError = null
                            },
                            pinnedIds = pinnedIds,
                            onTogglePin = { id -> scope.launch { PreferenceRepository.togglePinned(id) } },
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
            inFlight = deleteInFlight,
            errorMessage = deleteError,
            onConfirm = {
                deleteInFlight = true
                deleteError = null
                scope.launch(Dispatchers.IO) {
                    val result = reactiveClient.deleteResource("pod", pod.name, pod.namespace)
                    deleteInFlight = false
                    result.fold(
                        onSuccess = { pendingDelete = null },
                        onFailure = { deleteError = it.message ?: "Delete failed" },
                    )
                }
            },
            onDismiss = {
                pendingDelete = null
                deleteError = null
            },
        )
    }
}
