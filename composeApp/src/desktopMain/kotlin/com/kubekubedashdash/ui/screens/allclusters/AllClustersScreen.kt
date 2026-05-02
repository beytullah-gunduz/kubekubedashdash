package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel
import com.kubekubedashdash.ui.screens.cluster.ClusterUsageStatistics

@Composable
fun AllClustersScreen() {
    val viewModel = AllClustersViewModel.instance
    val clusterInfo by viewModel.aggregatedClusterInfo.collectAsState()
    val usage by viewModel.aggregatedUsage.collectAsState()
    val cpuHistory by viewModel.cpuHistory.collectAsState()
    val memHistory by viewModel.memHistory.collectAsState()
    val podsCapacity by viewModel.aggregatedPodsCapacity.collectAsState()
    val podsHistory by viewModel.podsHistory.collectAsState()
    val topNodes by viewModel.topNodesAcrossAllClusters.collectAsState()
    val summariesRaw by viewModel.clusterSummaries.collectAsState()
    val summaries = summariesRaw.sortedByDescending { it.recentErrorCount }
    val events by viewModel.aggregatedEvents.collectAsState()

    var statsExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        val info = clusterInfo
        if (info != null) {
            ClusterUsageStatistics(
                clusterInfo = info,
                usage = usage,
                cpuHistory = cpuHistory,
                memHistory = memHistory,
                podsCount = info.podsCount,
                podsCapacity = podsCapacity,
                podsLoaded = true,
                podsHistory = podsHistory,
                topNodes = topNodes,
                expanded = statsExpanded,
                onToggle = { statsExpanded = !statsExpanded },
                onNodeClick = {},
            )
            Spacer(Modifier.height(16.dp))
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(summaries, key = { it.sessionId.value }) { summary ->
                ClusterSummaryCard(
                    summary = summary,
                    modifier = Modifier.animateItem(),
                    onClick = { WorkspaceManager.activateClusterTab(summary.sessionId) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AllClustersEventsTable(events = events)
    }
}
