package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

internal const val CLUSTER_OVERVIEW_RECENT_LIMIT = 10
internal const val CLUSTER_OVERVIEW_TOP_NODES = 3
internal const val CLUSTER_OVERVIEW_HISTORY_SIZE = 20

class ClusterOverviewViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    val state: StateFlow<ResourceState<ClusterInfo>> = reactiveClient.clusterInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    // ── Usage card data ─────────────────────────────────────────────────────────

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _podsHistory = MutableStateFlow<List<Float>>(emptyList())
    val podsHistory: StateFlow<List<Float>> = _podsHistory.asStateFlow()

    val resourceUsage: StateFlow<ResourceUsageSummary?> = reactiveClient.resourceUsage
        .onEach { s ->
            val u = if (s is ResourceState.Success) s.data else null
            if (u != null && u.metricsAvailable) {
                val cpuF = if (u.cpuCapacityMillis > 0) u.cpuUsedMillis.toFloat() / u.cpuCapacityMillis else 0f
                val memF = if (u.memoryCapacityBytes > 0) u.memoryUsedBytes.toFloat() / u.memoryCapacityBytes else 0f
                _cpuHistory.value = (_cpuHistory.value + cpuF).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE)
                _memHistory.value = (_memHistory.value + memF).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE)
            }
        }
        .map { s -> if (s is ResourceState.Success) s.data else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podsCapacity: StateFlow<Int> = reactiveClient.nodes
        .map { s ->
            if (s is ResourceState.Success) {
                s.data.sumOf { it.pods.toIntOrNull() ?: 0 }
            } else {
                0
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val podsCount: StateFlow<Int> = state
        .map { s -> if (s is ResourceState.Success) s.data.podsCount else 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val podsLoaded: StateFlow<Boolean> = combine(podsCount, podsCapacity) { c, cap -> cap > 0 || c > 0 }
        .onEach { _ ->
            val c = podsCount.value
            val cap = podsCapacity.value
            if (cap > 0) {
                val frac = c.toFloat() / cap.toFloat()
                _podsHistory.value = (_podsHistory.value + frac).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val topNodesByPressure: StateFlow<List<NodeResourceUsage>> = reactiveClient.nodeUsages
        .map { byName ->
            byName.values
                .sortedByDescending { it.pressureFraction }
                .take(CLUSTER_OVERVIEW_TOP_NODES)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Recent activity lists ───────────────────────────────────────────────────

    val recentNodes: StateFlow<RecentSlice<NodeInfo>> = reactiveClient.nodes
        .map { s -> sliceRecent(s) { sortedByDescending { it.creationTimestamp } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    val recentPods: StateFlow<RecentSlice<PodInfo>> = reactiveClient.pods
        .map { s -> sliceRecent(s) { sortedByDescending { it.creationTimestamp } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    val recentEvents: StateFlow<RecentSlice<EventInfo>> = reactiveClient.events
        .map { s -> sliceRecent(s) { sortedByDescending { it.lastSeenTimestamp } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    private fun <T> sliceRecent(
        s: ResourceState<List<T>>,
        sort: List<T>.() -> List<T>,
    ): RecentSlice<T> = when (s) {
        is ResourceState.Success -> {
            val all = s.data.sort()
            RecentSlice(items = all.take(CLUSTER_OVERVIEW_RECENT_LIMIT), total = all.size, loading = false, errorMessage = null)
        }

        is ResourceState.Loading -> RecentSlice(emptyList(), total = 0, loading = true, errorMessage = null)

        is ResourceState.Error -> RecentSlice(emptyList(), total = 0, loading = false, errorMessage = s.message)
    }
}

data class RecentSlice<T>(
    val items: List<T>,
    val total: Int,
    val loading: Boolean,
    val errorMessage: String?,
) {
    companion object {
        fun <T> empty(): RecentSlice<T> = RecentSlice(emptyList(), 0, loading = true, errorMessage = null)
    }
}
