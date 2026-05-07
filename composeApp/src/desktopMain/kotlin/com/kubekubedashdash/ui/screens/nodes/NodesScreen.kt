package com.kubekubedashdash.ui.screens.nodes

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.screens.nodes.viewmodel.NodesScreenViewModel
import kotlinx.coroutines.flow.first

internal const val MAX_HISTORY_SIZE = 20

@Composable
fun NodesScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    selectNodeName: String? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: NodesScreenViewModel = viewModel { NodesScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val cpuHistory by viewModel.cpuHistory.collectAsState()
    val memHistory by viewModel.memHistory.collectAsState()
    val podsCount by viewModel.podsCount.collectAsState()
    val podsCapacity by viewModel.podsCapacity.collectAsState()
    val podsLoaded by viewModel.podsLoaded.collectAsState()
    val podsHistory by viewModel.podsHistory.collectAsState()
    val staleNodes by viewModel.staleNodes.collectAsState()
    var statsExpanded by remember { mutableStateOf(true) }
    var selectedNodeUid by rememberSaveable { mutableStateOf<String?>(null) }
    var statusFilter by rememberSaveable { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(selectNodeName) {
        viewModel.setParams(selectNodeName)
        if (selectNodeName != null) {
            viewModel.selected.first { it != null }?.let {
                selectedNodeUid = it.uid
                onNavigate(Screen.Detail.NodeDetail(it))
            }
        }
    }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val allNodes = s.data + staleNodes.values.toList()
            val availableStatuses = remember(allNodes) {
                allNodes.map { it.status }.filter { it.isNotBlank() }.toSortedSet()
            }
            val activeStatusFilter = statusFilter
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = allNodes.filter { node ->
                val passesSearch = searchQuery.isBlank() ||
                    node.name.contains(searchQuery, ignoreCase = true) ||
                    node.roles.contains(searchQuery, ignoreCase = true) ||
                    node.status.contains(searchQuery, ignoreCase = true)
                val passesStatus = activeStatusFilter == null || node.status in activeStatusFilter
                val passesLabels = labelSelector.isEmpty() || matchesMapSelector(node.labels, labelSelector)
                val passesAnnotations = annotationSelector.isEmpty() ||
                    matchesMapSelector(node.annotations, annotationSelector)
                passesSearch && passesStatus && passesLabels && passesAnnotations
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val showStats = maxWidth >= 900.dp
                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = showStats,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        NodeStatsPanel(
                            usage = resourceUsage,
                            cpuHistory = cpuHistory,
                            memHistory = memHistory,
                            podsCount = podsCount,
                            podsCapacity = podsCapacity,
                            podsLoaded = podsLoaded,
                            podsHistory = podsHistory,
                            expanded = statsExpanded,
                            onToggle = { statsExpanded = !statsExpanded },
                        )
                    }
                    ResourceCountHeader(
                        count = filtered.size,
                        kind = "Nodes",
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
                    NodeTable(
                        nodes = filtered,
                        selectedUid = selectedNodeUid,
                        onClick = { node ->
                            selectedNodeUid = node.uid
                            onNavigate(Screen.Detail.NodeDetail(node))
                        },
                    )
                }
            }
        }
    }
}
