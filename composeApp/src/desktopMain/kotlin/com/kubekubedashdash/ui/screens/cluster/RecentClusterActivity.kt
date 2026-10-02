package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.ui.screens.cluster.viewmodel.RecentSlice

@Composable
internal fun RecentClusterActivity(
    nodes: RecentSlice<NodeInfo>,
    pods: RecentSlice<PodInfo>,
    events: RecentSlice<EventInfo>,
    onNodeClick: (NodeInfo) -> Unit,
    onPodClick: (PodInfo) -> Unit,
    onEventClick: (EventInfo) -> Unit,
    onViewAllNodes: () -> Unit,
    onViewAllPods: () -> Unit,
    onViewAllEvents: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val threeColumns = maxWidth >= RECENT_THREE_COLUMN_THRESHOLD
        val showNamespace = recentCardShowsNamespace(recentCardWidth(maxWidth))
        if (threeColumns) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(RECENT_CARD_GAP),
            ) {
                NodesCard(nodes, onNodeClick, onViewAllNodes, Modifier.weight(1f).fillMaxHeight())
                PodsCard(pods, showNamespace, onPodClick, onViewAllPods, Modifier.weight(1f).fillMaxHeight())
                EventsCard(events, showNamespace, onEventClick, onViewAllEvents, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(RECENT_CARD_GAP)) {
                NodesCard(nodes, onNodeClick, onViewAllNodes, Modifier.fillMaxWidth())
                PodsCard(pods, showNamespace, onPodClick, onViewAllPods, Modifier.fillMaxWidth())
                EventsCard(events, showNamespace, onEventClick, onViewAllEvents, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NodesCard(
    slice: RecentSlice<NodeInfo>,
    onClick: (NodeInfo) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier,
) {
    RecentResourceCard(
        title = "Recent Nodes",
        slice = slice,
        emptyLabel = "No nodes",
        viewAllLabel = { total -> "Show 10 of ${"%,d".format(total)} — view all nodes →" },
        onViewAll = onViewAll,
        modifier = modifier,
        row = { node -> CompactNodeRow(node, onClick = { onClick(node) }) },
    )
}

@Composable
private fun PodsCard(
    slice: RecentSlice<PodInfo>,
    showNamespace: Boolean,
    onClick: (PodInfo) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier,
) {
    RecentResourceCard(
        title = "Recent Pods",
        slice = slice,
        emptyLabel = "No pods",
        viewAllLabel = { total -> "Show 10 of ${"%,d".format(total)} — view all pods →" },
        onViewAll = onViewAll,
        modifier = modifier,
        row = { pod -> CompactPodRow(pod, showNamespace, onClick = { onClick(pod) }) },
    )
}

@Composable
private fun EventsCard(
    slice: RecentSlice<EventInfo>,
    showNamespace: Boolean,
    onClick: (EventInfo) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier,
) {
    RecentResourceCard(
        title = "Recent Events",
        slice = slice,
        emptyLabel = "No events",
        viewAllLabel = { total -> "Show 10 of ${"%,d".format(total)} — view all events →" },
        onViewAll = onViewAll,
        modifier = modifier,
        row = { event -> CompactEventRow(event, showNamespace, onClick = { onClick(event) }) },
    )
}
