package com.kubekubedashdash.ui.screens.nodes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.screens.nodes.viewmodel.NodesScreenViewModel
import kotlinx.coroutines.flow.first

internal const val MAX_HISTORY_SIZE = 20

@Composable
fun NodesScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    selectNodeName: String? = null,
    viewModel: NodesScreenViewModel = androidx.lifecycle.viewmodel.compose.viewModel { NodesScreenViewModel() },
) {
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
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val allNodes = s.data + staleNodes.values.toList()
            val filtered = allNodes.filter { node ->
                searchQuery.isBlank() ||
                    node.name.contains(searchQuery, ignoreCase = true) ||
                    node.roles.contains(searchQuery, ignoreCase = true) ||
                    node.status.contains(searchQuery, ignoreCase = true)
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
                    ResourceCountHeader(filtered.size, "Nodes")
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
