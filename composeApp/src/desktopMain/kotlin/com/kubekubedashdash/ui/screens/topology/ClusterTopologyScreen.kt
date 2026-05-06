package com.kubekubedashdash.ui.screens.topology

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kubekubedashdash.KdError
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.graph_3_24
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.screens.topology.viewmodel.ClusterTopologyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ClusterTopologyScreen(onNavigate: (Screen) -> Unit) {
    val reactiveClient = LocalReactiveKubeClient.current
    val selectedNamespaceRaw by reactiveClient.selectedNamespace.collectAsState()
    val namespace = selectedNamespaceRaw ?: "All Namespaces"
    val viewModel = remember(reactiveClient) { ClusterTopologyViewModel(reactiveClient) }
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val graph by viewModel.graph.collectAsState()
    val refreshIntervalSec by PreferenceRepository.topologyRefreshIntervalSec.collectAsState()

    LaunchedEffect(namespace) { viewModel.load(namespace) }

    // Auto-refresh poll. Restarts whenever namespace or interval changes; cancels
    // when the screen leaves composition (so it pauses while the user is on a
    // different workspace tab). 0 = disabled.
    LaunchedEffect(namespace, refreshIntervalSec) {
        if (refreshIntervalSec <= 0) return@LaunchedEffect
        while (isActive) {
            delay(refreshIntervalSec * 1000L)
            viewModel.load(namespace)
        }
    }

    when {
        // Show the existing graph during refreshes (loading is true mid-fetch); only
        // fall back to the spinner when there's nothing to show yet.
        graph != null && graph!!.nodes.isNotEmpty() && graph!!.edges.isNotEmpty() ->
            ClusterTopologyGraph(graph!!, viewModel, namespace)

        loading -> ResourceLoadingIndicator()

        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = KdError, style = MaterialTheme.typography.bodySmall)
        }

        else -> EmptyState(icon = Res.drawable.graph_3_24, kind = "Topology")
    }
}
