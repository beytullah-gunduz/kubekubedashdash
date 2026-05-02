package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel

/**
 * Content pane for the AllClusters tab. Shows a scrollable row of
 * [ClusterSummaryCard]s followed by an aggregated [AllClustersEventsTable]
 * merging events from every open cluster session.
 */
@Composable
fun AllClustersScreen() {
    val viewModel = AllClustersViewModel.instance
    val summaries by viewModel.clusterSummaries.collectAsState()
    val events by viewModel.aggregatedEvents.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(summaries, key = { it.sessionId.value }) { summary ->
                ClusterSummaryCard(summary)
            }
        }
        Spacer(Modifier.height(16.dp))
        AllClustersEventsTable(events = events)
    }
}
