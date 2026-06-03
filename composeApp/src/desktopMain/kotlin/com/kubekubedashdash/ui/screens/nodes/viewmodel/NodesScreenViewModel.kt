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
import com.kubekubedashdash.util.STALE_PRUNE_INTERVAL_MS
import com.kubekubedashdash.util.StaleEntry
import com.kubekubedashdash.util.pruneExpiredStale
import com.kubekubedashdash.util.reduceStale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class NodesScreenViewModel(
    private val reactiveClient: ReactiveKubeClient,
    private val now: () -> Instant = { Instant.now() },
    private val ttl: Duration = DEFAULT_STALE_TTL,
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
    // they went stale, then evicted [ttl] later (prune ticker below / next
    // snapshot via reduceStale). The frozen NodeInfo is never refreshed — the
    // node is gone from the stream. Public flow exposes just NodeInfo so callers
    // keep `s.data + staleNodes.values`; `staleNodes.keys` is the stale UID set.
    private val _staleNodes = MutableStateFlow<Map<String, StaleEntry<NodeInfo>>>(emptyMap())
    val staleNodes: StateFlow<Map<String, NodeInfo>> = _staleNodes
        .map { stale -> stale.mapValues { (_, entry) -> entry.info } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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

    private var previousNodesByUid: Map<String, NodeInfo> = emptyMap()
    private var pendingSelectName: String? = null

    val state: StateFlow<ResourceState<List<NodeInfo>>> = reactiveClient.nodes
        .onEach { state ->
            if (state is ResourceState.Loading) {
                _staleNodes.value = emptyMap()
                previousNodesByUid = emptyMap()
            }
            if (state is ResourceState.Success) processNodeUpdate(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResourceState.Loading)

    // Per-node CPU/memory usage, keyed by node name. Powers the "Pressure
    // only" filter chip on the Nodes screen — without it the banner click
    // can't deliver a filtered view (status alone doesn't imply pressure).
    val nodeUsages: StateFlow<Map<String, NodeResourceUsage>> = reactiveClient.nodeUsages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val podsCount: StateFlow<Int> = podStats
        .map { it?.count ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val podsCapacity: StateFlow<Int> = podStats
        .map { it?.capacity ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val podsLoaded: StateFlow<Boolean> = podStats
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Age stale entries out independently of informer traffic (see the
        // matching ticker in PodsScreenViewModel). Loops only while the stale
        // set is non-empty.
        viewModelScope.launch {
            _staleNodes
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { hasStale ->
                    while (hasStale) {
                        delay(STALE_PRUNE_INTERVAL_MS)
                        _staleNodes.update { pruneExpiredStale(it, now(), ttl) }
                    }
                }
        }
    }

    fun setParams(selectNodeName: String? = null) {
        _selected.value = null
        _staleNodes.value = emptyMap()
        previousNodesByUid = emptyMap()
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
        val updatedStale = reduceStale(_staleNodes.value, currentByUid, previousNodesByUid, now(), ttl)

        previousNodesByUid = currentByUid
        _staleNodes.value = updatedStale

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
