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
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesLabelSelector
import com.kubekubedashdash.ui.components.parseLabelSelector
import com.kubekubedashdash.ui.screens.pods.viewmodel.PodsScreenViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PodsScreen(
    searchQuery: String,
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
    var labelQuery by rememberSaveable { mutableStateOf("") }
    val pinnedIds by PreferenceRepository.pinnedResources.collectAsState()
    val scope = rememberCoroutineScope()

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
                val labelSelector = remember(labelQuery) { parseLabelSelector(labelQuery) }
                val filtered = remember(allPods, searchQuery, activeStatusFilter, labelSelector, pinnedIds) {
                    allPods
                        .filter { pod ->
                            val passesSearch = searchQuery.isBlank() ||
                                pod.name.contains(searchQuery, ignoreCase = true) ||
                                pod.namespace.contains(searchQuery, ignoreCase = true) ||
                                pod.status.contains(searchQuery, ignoreCase = true) ||
                                pod.node.contains(searchQuery, ignoreCase = true)
                            val passesStatus = activeStatusFilter == null || pod.status in activeStatusFilter
                            val passesLabels = labelSelector.isEmpty() || matchesLabelSelector(pod.labels, labelSelector)
                            passesSearch && passesStatus && passesLabels
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
                                    onQueryChange = { labelQuery = it },
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
                            pinnedIds = pinnedIds,
                            onTogglePin = { id -> scope.launch { PreferenceRepository.togglePinned(id) } },
                        )
                    }
                }
            }
        }
    }
}
