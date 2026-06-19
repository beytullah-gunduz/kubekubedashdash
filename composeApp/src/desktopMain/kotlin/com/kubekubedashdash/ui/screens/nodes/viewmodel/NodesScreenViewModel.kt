package com.kubekubedashdash.ui.screens.nodes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.ui.screens.nodes.MAX_HISTORY_SIZE
import com.kubekubedashdash.util.DEFAULT_STALE_TTL
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.StaleTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant

class NodesScreenViewModel(
    private val reactiveClient: ReactiveKubeClient,
    private val now: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val log = LoggerFactory.getLogger(NodesScreenViewModel::class.java)
    private val _selected = MutableStateFlow<NodeInfo?>(null)
    val selected: StateFlow<NodeInfo?> = _selected.asStateFlow()

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _podsHistory = MutableStateFlow<List<Float>>(emptyList())
    val podsHistory: StateFlow<List<Float>> = _podsHistory.asStateFlow()

    // Nodes that have left the live watch stream, kept briefly with the instant
    // they went stale, then evicted after the TTL (prune ticker in StaleTracker /
    // next snapshot via reduceStale). The frozen NodeInfo is never refreshed —
    // the node is gone from the stream. Public flow exposes just NodeInfo so
    // callers keep `s.data + staleNodes.values`; `staleNodes.keys` is the stale
    // UID set. Nodes use the uniform window — a vanished node is a vanished node,
    // there's no "error departure" distinction like pods have.
    private val staleTracker = StaleTracker<NodeInfo>(viewModelScope, { it.uid }, { DEFAULT_STALE_TTL }, now)
    val staleNodes: StateFlow<Map<String, NodeInfo>> get() = staleTracker.stale

    // null = "no explicit allowlist" (= show every status). Non-null Set is
    // the explicit allowlist. Survives screen navigation (session-scoped VM).
    private val _statusFilter = MutableStateFlow<Set<String>?>(null)
    val statusFilter: StateFlow<Set<String>?> = _statusFilter.asStateFlow()

    private val _pressureOnly = MutableStateFlow(false)
    val pressureOnly: StateFlow<Boolean> = _pressureOnly.asStateFlow()

    fun setStatusFilter(value: Set<String>?) {
        _statusFilter.value = value
    }

    fun setPressureOnly(value: Boolean) {
        _pressureOnly.value = value
    }

    private var pendingSelectName: String? = null

    val state: StateFlow<ResourceState<List<NodeInfo>>> = reactiveClient.nodes
        .onEach { state ->
            if (state is ResourceState.Loading) {
                staleTracker.reset()
            }
            if (state is ResourceState.Success) processNodeUpdate(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    // Per-node CPU/memory usage, keyed by node name. Powers the "Pressure
    // only" filter chip on the Nodes screen — without it the banner click
    // can't deliver a filtered view (status alone doesn't imply pressure).
    val nodeUsages: StateFlow<Map<String, NodeResourceUsage>> = reactiveClient.nodeUsages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val resourceUsage: StateFlow<ResourceUsageSummary?> = reactiveClient.resourceUsage
        .onEach { state ->
            val usage = if (state is ResourceState.Success) state.data else null
            if (usage != null && usage.metricsAvailable) {
                val cpuF = if (usage.cpuCapacityMillis > 0) {
                    usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis.toFloat()
                } else {
                    0f
                }
                val memF = if (usage.memoryCapacityBytes > 0) {
                    usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes.toFloat()
                } else {
                    0f
                }
                _cpuHistory.update { (it + cpuF).takeLast(MAX_HISTORY_SIZE) }
                _memHistory.update { (it + memF).takeLast(MAX_HISTORY_SIZE) }
            }
        }
        .map { state -> if (state is ResourceState.Success) state.data else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class PodStats(val count: Int, val capacity: Int)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val podStats: StateFlow<PodStats?> = state
        .transformLatest { s ->
            val nodes = (s as? ResourceState.Success)?.data ?: return@transformLatest
            while (true) {
                val stats = withContext(Dispatchers.IO) {
                    val countsByNode = try {
                        reactiveClient.getPodCountsByNode()
                    } catch (_: Exception) {
                        emptyMap<String, Int>()
                    }
                    var totalPods = 0
                    var totalCapacity = 0
                    nodes.forEach { node ->
                        totalPods += countsByNode[node.name] ?: 0
                        totalCapacity += node.pods.toIntOrNull() ?: 0
                    }
                    PodStats(totalPods, totalCapacity)
                }
                log.debug("Pod stats refreshed: {}/{} pods across {} nodes", stats.count, stats.capacity, nodes.size)
                emit(stats)
                delay(5_000)
            }
        }
        .onEach { stats ->
            val frac = if (stats.capacity > 0) stats.count.toFloat() / stats.capacity.toFloat() else 0f
            _podsHistory.update { (it + frac).takeLast(MAX_HISTORY_SIZE) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podsCount: StateFlow<Int> = podStats
        .map { it?.count ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val podsCapacity: StateFlow<Int> = podStats
        .map { it?.capacity ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val podsLoaded: StateFlow<Boolean> = podStats
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setParams(selectNodeName: String? = null) {
        _selected.value = null
        staleTracker.reset()
        pendingSelectName = selectNodeName
        if (selectNodeName != null) {
            val current = state.value
            if (current is ResourceState.Success) {
                _selected.value = current.data.firstOrNull { it.name == selectNodeName }
                pendingSelectName = null
            }
        }
    }

    fun selectItem(item: NodeInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    fun clearSelection() {
        _selected.value = null
    }

    private fun processNodeUpdate(current: List<NodeInfo>) {
        val currentByUid = current.associateBy { it.uid }
        val updatedStale = staleTracker.onSnapshot(currentByUid)

        val name = pendingSelectName
        if (name != null) {
            _selected.value = current.firstOrNull { it.name == name }
                ?: updatedStale.values.firstOrNull { it.info.name == name }?.info
            pendingSelectName = null
        } else {
            _selected.value = _selected.value?.let { sel ->
                currentByUid[sel.uid] ?: updatedStale[sel.uid]?.info
            }
        }
    }
}
