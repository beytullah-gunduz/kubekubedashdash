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
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.graph_3_24
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.screens.topology.viewmodel.ClusterTopologyViewModel

@Composable
fun ClusterTopologyScreen(onNavigate: (Screen) -> Unit) {
    val reactiveClient = LocalReactiveKubeClient.current
    val selectedNamespaceRaw by reactiveClient.selectedNamespace.collectAsState()
    val namespace = selectedNamespaceRaw ?: "All Namespaces"
    val viewModel = remember(reactiveClient) { ClusterTopologyViewModel(reactiveClient) }
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val graph by viewModel.graph.collectAsState()

    LaunchedEffect(namespace) { viewModel.load(namespace) }

    when {
        loading -> ResourceLoadingIndicator()

        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = KdError, style = MaterialTheme.typography.bodySmall)
        }

        graph != null && graph!!.nodes.isNotEmpty() && graph!!.edges.isNotEmpty() ->
            ClusterTopologyGraph(graph!!, viewModel, namespace)

        else -> EmptyState(icon = Res.drawable.graph_3_24, kind = "Topology")
    }
}
