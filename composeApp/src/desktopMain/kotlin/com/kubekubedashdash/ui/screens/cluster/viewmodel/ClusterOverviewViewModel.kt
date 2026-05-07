package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.formatAge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant

internal const val CLUSTER_OVERVIEW_RECENT_LIMIT = 10
internal const val CLUSTER_OVERVIEW_TOP_NODES = 3
internal const val CLUSTER_OVERVIEW_HISTORY_SIZE = 20

private const val AGE_TICK_INTERVAL_MS = 10_000L

class ClusterOverviewViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    /**
     * Aggregate `clusterInfo` state — used for surfacing connection errors
     * via [ResourceErrorMessage]. The screen no longer gates its scaffold
     * on this, so `Loading` is a normal pre-sync state, not a "show
     * spinner" trigger. Per-card flows below populate the scaffold
     * incrementally as each informer syncs.
     */
    val state: StateFlow<ResourceState<ClusterInfo>> = reactiveClient.clusterInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    // ── Per-card counts (each fires as soon as its informer syncs) ──────────────

    val nodesCount: StateFlow<Int?> = reactiveClient.nodes
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val namespacesCount: StateFlow<Int?> = reactiveClient.namespaceNames
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val deploymentsCount: StateFlow<Int?> = reactiveClient.deployments
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val servicesCount: StateFlow<Int?> = reactiveClient.services
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podPhaseCounts: StateFlow<PodPhaseCounts?> = reactiveClient.pods
        .map { s ->
            if (s is ResourceState.Success) {
                PodPhaseCounts(
                    running = s.data.count { it.phase == "Running" },
                    pending = s.data.count { it.phase == "Pending" },
                    failed = s.data.count { it.phase == "Failed" },
                    succeeded = s.data.count { it.phase == "Succeeded" },
                )
            } else {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                _cpuHistory.update { (it + cpuF).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE) }
                _memHistory.update { (it + memF).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE) }
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

    val podsCount: StateFlow<Int?> = reactiveClient.pods
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podsLoaded: StateFlow<Boolean> = combine(podsCount, podsCapacity) { c, cap ->
        val cInt = c ?: 0
        if (cap > 0) {
            val frac = cInt.toFloat() / cap.toFloat()
            _podsHistory.update { (it + frac).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE) }
        }
        cap > 0 || cInt > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val topNodesByPressure: StateFlow<List<NodeResourceUsage>> = reactiveClient.nodeUsages
        .map { byName ->
            byName.values
                .sortedByDescending { it.pressureFraction }
                .take(CLUSTER_OVERVIEW_TOP_NODES)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Recent activity lists ───────────────────────────────────────────────────

    // Wake-up signal that re-runs the combine blocks below every
    // AGE_TICK_INTERVAL_MS so age strings refresh on a wall-clock cadence.
    // The emitted value is intentionally Unit — `Instant.now()` is read
    // fresh inside each combine lambda instead, so a fresh event arriving
    // between ticks is formatted against an up-to-the-millisecond now,
    // not a stale tick instant (which would yield negative durations).
    private val ageTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(AGE_TICK_INTERVAL_MS)
        }
    }

    val recentNodes: StateFlow<RecentSlice<NodeInfo>> = combine(reactiveClient.nodes, ageTicker) { s, _ ->
        val now = Instant.now()
        sliceRecent(s.refreshNodeAges(now)) { sortedByDescending { it.creationTimestamp } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    val recentPods: StateFlow<RecentSlice<PodInfo>> = combine(reactiveClient.pods, ageTicker) { s, _ ->
        val now = Instant.now()
        sliceRecent(s.refreshPodAges(now)) { sortedByDescending { it.creationTimestamp } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    val recentEvents: StateFlow<RecentSlice<EventInfo>> = combine(reactiveClient.events, ageTicker) { s, _ ->
        val now = Instant.now()
        sliceRecent(s.refreshEventAges(now)) { sortedByDescending { it.lastSeenTimestamp } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    private fun ResourceState<List<NodeInfo>>.refreshNodeAges(now: Instant): ResourceState<List<NodeInfo>> = if (this is ResourceState.Success) {
        ResourceState.Success(data.map { it.copy(age = formatAge(it.creationTimestamp, now)) })
    } else {
        this
    }

    private fun ResourceState<List<PodInfo>>.refreshPodAges(now: Instant): ResourceState<List<PodInfo>> = if (this is ResourceState.Success) {
        ResourceState.Success(data.map { it.copy(age = formatAge(it.creationTimestamp, now)) })
    } else {
        this
    }

    private fun ResourceState<List<EventInfo>>.refreshEventAges(now: Instant): ResourceState<List<EventInfo>> = if (this is ResourceState.Success) {
        ResourceState.Success(data.map { it.copy(lastSeen = formatAge(it.lastSeenTimestamp, now)) })
    } else {
        this
    }

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
