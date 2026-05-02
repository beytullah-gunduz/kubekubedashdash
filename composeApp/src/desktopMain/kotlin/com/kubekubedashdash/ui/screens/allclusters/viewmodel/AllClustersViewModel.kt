package com.kubekubedashdash.ui.screens.allclusters.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.services.WorkspaceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * App-wide singleton ViewModel for the AllClusters tab. Aggregates per-cluster
 * events and summary stats from every open [WorkspaceTab.Cluster] session.
 *
 * Uses a two-level [flatMapLatest] chain so it reacts to both:
 *  - workspace list changes (cluster added in a new window / window closed), and
 *  - per-workspace tab list changes (cluster added via NEW_TAB to an existing window).
 *
 * One instance per app via [instance]. No [androidx.lifecycle.ViewModelStoreOwner]
 * needed — same singleton pattern as AppViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AllClustersViewModel private constructor() : ViewModel() {

    companion object {
        val instance: AllClustersViewModel by lazy { AllClustersViewModel() }
    }

    data class ClusterSummary(
        val sessionId: SessionId,
        val contextName: String,
        val isConnected: Boolean,
        val isConnecting: Boolean,
        val nodeCount: Int,
        val namespaceCount: Int,
        val recentErrorCount: Int,
    )

    /**
     * Emits the live list of [WorkspaceTab.Cluster] tabs whenever any workspace's
     * tab list changes (new cluster added, cluster closed, window closed, etc.).
     */
    private fun clusterTabsFlow(): Flow<List<WorkspaceTab.Cluster>> = WorkspaceManager.workspaces.flatMapLatest { workspaceList ->
        if (workspaceList.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(workspaceList.map { ws -> ws.tabs }) { allTabs ->
            allTabs.flatMap { it.filterIsInstance<WorkspaceTab.Cluster>() }
        }
    }

    /** Merged events from all open sessions, each tagged with its source cluster name. */
    val aggregatedEvents: StateFlow<List<EventInfo>> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                clusterTabs.map { tab ->
                    combine(
                        tab.session.viewModel.selectedContext,
                        tab.session.reactiveClient.events,
                    ) { ctx, state ->
                        val events = (state as? ResourceState.Success)?.data ?: emptyList()
                        events.map { it.copy(cluster = ctx.ifBlank { "?" }) }
                    }
                },
            ) { arrays -> arrays.flatMap { it }.sortedByDescending { it.lastSeenTimestamp } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One summary card per open cluster session. */
    val clusterSummaries: StateFlow<List<ClusterSummary>> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                clusterTabs.map { tab ->
                    combine(
                        tab.session.viewModel.selectedContext,
                        tab.session.viewModel.isConnected,
                        tab.session.viewModel.isConnecting,
                        tab.session.reactiveClient.nodes,
                        tab.session.reactiveClient.clusterInfo,
                    ) { ctx, connected, connecting, nodesState, clusterState ->
                        val info = (clusterState as? ResourceState.Success)?.data
                        val recentErrors = aggregatedEvents.value.count {
                            it.cluster == ctx && (it.type == "Warning" || it.type == "Error")
                        }
                        ClusterSummary(
                            sessionId = tab.session.id,
                            contextName = ctx.ifBlank { "Loading…" },
                            isConnected = connected,
                            isConnecting = connecting,
                            nodeCount = info?.nodesCount ?: 0,
                            namespaceCount = info?.namespacesCount ?: 0,
                            recentErrorCount = recentErrors,
                        )
                    }
                },
            ) { it.toList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Convenience: ordered list of context names for the sessions currently aggregated. */
    val clusterNames: StateFlow<List<String>> = clusterSummaries
        .map { summaries -> summaries.map { it.contextName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
