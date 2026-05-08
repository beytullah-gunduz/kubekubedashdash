package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.folder_special_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.SummaryCard
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterOverviewViewModel
import com.kubekubedashdash.ui.screens.cluster.viewmodel.errorPodStatuses

@Composable
fun ClusterOverviewScreen(
    onNavigate: (Screen) -> Unit,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: ClusterOverviewViewModel = viewModel { ClusterOverviewViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()

    val resourceUsage by viewModel.resourceUsage.collectAsState()
    val cpuHistory by viewModel.cpuHistory.collectAsState()
    val memHistory by viewModel.memHistory.collectAsState()
    val podsHistory by viewModel.podsHistory.collectAsState()
    val podsCount by viewModel.podsCount.collectAsState()
    val podsCapacity by viewModel.podsCapacity.collectAsState()
    val podsLoaded by viewModel.podsLoaded.collectAsState()
    val topNodes by viewModel.topNodesByPressure.collectAsState()
    val recentNodes by viewModel.recentNodes.collectAsState()
    val recentPods by viewModel.recentPods.collectAsState()
    val recentEvents by viewModel.recentEvents.collectAsState()

    val nodesCount by viewModel.nodesCount.collectAsState()
    val namespacesCount by viewModel.namespacesCount.collectAsState()
    val deploymentsCount by viewModel.deploymentsCount.collectAsState()
    val servicesCount by viewModel.servicesCount.collectAsState()
    val phaseCounts by viewModel.podPhaseCounts.collectAsState()
    val health by viewModel.health.collectAsState()

    var statsExpanded by remember { mutableStateOf(true) }

    // Connection error gets a centered error screen — same as before.
    // Loading no longer hides the scaffold; per-card flows render
    // skeletons until each informer syncs.
    val errorState = state as? ResourceState.Error
    if (errorState != null) {
        ResourceErrorMessage(errorState.message)
        return
    }

    // Cluster identity comes from sync getters — they're stable for the
    // lifetime of the connection, so the header paints immediately
    // instead of waiting for any flow to emit.
    val clusterName = remember(reactiveClient) { reactiveClient.getCurrentContext() }
    val clusterServer = remember(reactiveClient) { reactiveClient.getClusterServer() }
    val clusterVersion = (state as? ResourceState.Success)?.data?.version.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        ClusterHeader(name = clusterName, server = clusterServer, version = clusterVersion)

        Spacer(Modifier.height(16.dp))

        ClusterHealthBanner(
            health = health,
            onSegmentClick = { segment ->
                when (segment) {
                    HealthSegment.PODS_IN_ERROR -> onNavigate(
                        Screen.Main.Pods(statusFilter = errorPodStatuses()),
                    )

                    HealthSegment.NODES_NOT_READY -> onNavigate(Screen.Main.Nodes())

                    HealthSegment.DEPLOYMENTS_DEGRADED -> onNavigate(Screen.Main.Deployments)

                    HealthSegment.NODES_UNDER_PRESSURE -> onNavigate(Screen.Main.Nodes())

                    HealthSegment.RECENT_WARNINGS -> onNavigate(
                        Screen.Main.Events(typeFilter = "Warning"),
                    )
                }
            },
        )

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard("Nodes", nodesCount.asCardValue(), Res.drawable.dns_filled, KdPrimary, Modifier.weight(1f)) { onNavigate(Screen.Main.Nodes()) }
            SummaryCard("Namespaces", namespacesCount.asCardValue(), Res.drawable.folder_special_filled, KdInfo, Modifier.weight(1f)) { onNavigate(Screen.Main.Namespaces) }
            SummaryCard("Pods", podsCount.asCardValue(), Res.drawable.view_in_ar_filled, KdSuccess, Modifier.weight(1f)) { onNavigate(Screen.Main.Pods()) }
            SummaryCard("Deployments", deploymentsCount.asCardValue(), Res.drawable.layers_filled, KdWarning, Modifier.weight(1f)) { onNavigate(Screen.Main.Deployments) }
            SummaryCard("Services", servicesCount.asCardValue(), Res.drawable.cloud_filled, Color(0xFF9C27B0), Modifier.weight(1f)) { onNavigate(Screen.Main.Services) }
        }

        Spacer(Modifier.height(24.dp))

        ClusterUsageStatistics(
            phaseCounts = phaseCounts,
            usage = resourceUsage,
            cpuHistory = cpuHistory,
            memHistory = memHistory,
            podsCount = podsCount,
            podsCapacity = podsCapacity,
            podsLoaded = podsLoaded,
            podsHistory = podsHistory,
            topNodes = topNodes,
            expanded = statsExpanded,
            onToggle = { statsExpanded = !statsExpanded },
            onNodeClick = { name -> onNavigate(Screen.Main.Nodes(selectNodeName = name)) },
        )

        Spacer(Modifier.height(24.dp))

        RecentClusterActivity(
            nodes = recentNodes,
            pods = recentPods,
            events = recentEvents,
            onNodeClick = { node -> onNavigate(Screen.Main.Nodes(selectNodeName = node.name)) },
            onPodClick = { pod -> onNavigate(Screen.Main.Pods(selectPodUid = pod.uid)) },
            onEventClick = { event -> onNavigate(Screen.Main.Events(selectEventUid = event.uid)) },
            onViewAllNodes = { onNavigate(Screen.Main.Nodes()) },
            onViewAllPods = { onNavigate(Screen.Main.Pods()) },
            onViewAllEvents = { onNavigate(Screen.Main.Events()) },
        )
    }
}

private fun Int?.asCardValue(): String = this?.toString() ?: "—"

@Composable
private fun ClusterHeader(name: String, server: String, version: String) {
    Text(
        "Cluster Overview",
        style = MaterialTheme.typography.headlineMedium,
        color = KdTextPrimary,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = KdPrimary)
        if (version.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text("v$version", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
        }
        if (server.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(server, style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
        }
    }
}
