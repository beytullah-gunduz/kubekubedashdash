package com.kubekubedashdash.ui.screens.pods

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.screens.pods.viewmodel.PodsScreenViewModel
import kotlinx.coroutines.flow.first

@Composable
fun PodsScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    selectPodUid: String? = null,
    viewModel: PodsScreenViewModel = viewModel { PodsScreenViewModel() },
) {
    val state by viewModel.state.collectAsState()
    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val stalePods by viewModel.stalePods.collectAsState()
    var statsExpanded by remember { mutableStateOf(true) }
    var selectedPodUid by rememberSaveable { mutableStateOf<String?>(null) }

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
        ResourceLoadingIndicator()
    }

    AnimatedVisibility(state is ResourceState.Error, enter = enter, exit = exit) {
        ResourceErrorMessage((state as ResourceState.Error).message)
    }

    AnimatedVisibility(state is ResourceState.Success, enter = enter, exit = exit) {
        with(state) {
            if (this is ResourceState.Success) {
                val s = this as ResourceState.Success
                val allPods = s.data + stalePods.values
                val filtered = allPods.filter { pod ->
                    searchQuery.isBlank() ||
                        pod.name.contains(searchQuery, ignoreCase = true) ||
                        pod.namespace.contains(searchQuery, ignoreCase = true) ||
                        pod.status.contains(searchQuery, ignoreCase = true) ||
                        pod.node.contains(searchQuery, ignoreCase = true)
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
                        ResourceCountHeader(filtered.size, "Pods")
                        PodTable(
                            pods = filtered,
                            selectedUid = selectedPodUid,
                            onPodClick = { pod ->
                                selectedPodUid = pod.uid
                                onNavigate(Screen.Detail.PodDetail(pod))
                            },
                        )
                    }
                }
            }
        }
    }
}
