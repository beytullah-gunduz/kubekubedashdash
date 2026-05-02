package com.kubekubedashdash.ui.screens.allclusters.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.services.WorkspaceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    // ── Aggregated stats card data ────────────────────────────────────────────────

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _podsHistory = MutableStateFlow<List<Float>>(emptyList())
    val podsHistory: StateFlow<List<Float>> = _podsHistory.asStateFlow()

    /** Summed cluster-level counts across all open sessions. */
    val aggregatedClusterInfo: StateFlow<ClusterInfo?> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(null)
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.clusterInfo }) { states ->
                val infos = states.mapNotNull { (it as? ResourceState.Success)?.data }
                if (infos.isEmpty()) {
                    null
                } else {
                    ClusterInfo(
                        name = "All Clusters",
                        server = "",
                        version = "",
                        nodesCount = infos.sumOf { it.nodesCount },
                        namespacesCount = infos.sumOf { it.namespacesCount },
                        podsCount = infos.sumOf { it.podsCount },
                        deploymentsCount = infos.sumOf { it.deploymentsCount },
                        servicesCount = infos.sumOf { it.servicesCount },
                        runningPods = infos.sumOf { it.runningPods },
                        pendingPods = infos.sumOf { it.pendingPods },
                        failedPods = infos.sumOf { it.failedPods },
                        succeededPods = infos.sumOf { it.succeededPods },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Summed CPU and memory totals across all open sessions. Updates CPU/mem history as a side-effect. */
    val aggregatedUsage: StateFlow<ResourceUsageSummary?> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(null)
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.resourceUsage }) { states ->
                val summaries = states.mapNotNull { (it as? ResourceState.Success)?.data }
                if (summaries.isEmpty()) {
                    null
                } else {
                    ResourceUsageSummary(
                        cpuUsedMillis = summaries.sumOf { it.cpuUsedMillis },
                        cpuCapacityMillis = summaries.sumOf { it.cpuCapacityMillis },
                        memoryUsedBytes = summaries.sumOf { it.memoryUsedBytes },
                        memoryCapacityBytes = summaries.sumOf { it.memoryCapacityBytes },
                        metricsAvailable = summaries.any { it.metricsAvailable },
                    )
                }
            }
        }
        .onEach { usage ->
            if (usage != null && usage.metricsAvailable) {
                val cpuF = if (usage.cpuCapacityMillis > 0) usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis else 0f
                val memF = if (usage.memoryCapacityBytes > 0) usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes else 0f
                _cpuHistory.value = (_cpuHistory.value + cpuF).takeLast(20)
                _memHistory.value = (_memHistory.value + memF).takeLast(20)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sum of allocatable pod slots across all nodes in all open sessions. */
    val aggregatedPodsCapacity: StateFlow<Int> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(0)
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.nodes }) { states ->
                states.sumOf { state ->
                    (state as? ResourceState.Success)?.data
                        ?.sumOf { it.pods.toIntOrNull() ?: 0 } ?: 0
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Top 3 nodes by pressure fraction across all open sessions. */
    val topNodesAcrossAllClusters: StateFlow<List<NodeResourceUsage>> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.nodeUsages }) { usageMaps ->
                usageMaps.flatMap { it.values }
                    .sortedByDescending { it.pressureFraction }
                    .take(3)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(aggregatedClusterInfo, aggregatedPodsCapacity) { info, cap ->
                val count = info?.podsCount ?: 0
                if (cap > 0) count.toFloat() / cap else 0f
            }.collect { frac ->
                _podsHistory.value = (_podsHistory.value + frac).takeLast(20)
            }
        }
    }
}
