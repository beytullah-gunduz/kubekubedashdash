package com.kubekubedashdash.ui.screens.nodes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.services.KubeClientService
import com.kubekubedashdash.ui.screens.nodes.MAX_HISTORY_SIZE
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
import kotlinx.coroutines.withContext

class NodesScreenViewModel : ViewModel() {
    private val reactiveClient = KubeClientService.reactiveClient
    private val _selected = MutableStateFlow<NodeInfo?>(null)
    val selected: StateFlow<NodeInfo?> = _selected.asStateFlow()

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _podsHistory = MutableStateFlow<List<Float>>(emptyList())
    val podsHistory: StateFlow<List<Float>> = _podsHistory.asStateFlow()

    private val _staleNodes = MutableStateFlow<Map<String, NodeInfo>>(emptyMap())
    val staleNodes: StateFlow<Map<String, NodeInfo>> = _staleNodes.asStateFlow()

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
                _cpuHistory.value = (_cpuHistory.value + cpuF).takeLast(MAX_HISTORY_SIZE)
                _memHistory.value = (_memHistory.value + memF).takeLast(MAX_HISTORY_SIZE)
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
                    var totalPods = 0
                    var totalCapacity = 0
                    nodes.forEach { node ->
                        try {
                            totalPods += reactiveClient.getPodsByNode(node.name).size
                        } catch (_: Exception) {
                        }
                        totalCapacity += node.pods.toIntOrNull() ?: 0
                    }
                    PodStats(totalPods, totalCapacity)
                }
                emit(stats)
                delay(5_000)
            }
        }
        .onEach { stats ->
            val frac = if (stats.capacity > 0) stats.count.toFloat() / stats.capacity.toFloat() else 0f
            _podsHistory.value = (_podsHistory.value + frac).takeLast(MAX_HISTORY_SIZE)
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

        val updatedStale = _staleNodes.value.toMutableMap()
        updatedStale.keys.removeAll(currentByUid.keys)
        for ((uid, node) in previousNodesByUid) {
            if (uid !in currentByUid && uid !in updatedStale) {
                updatedStale[uid] = node
            }
        }

        previousNodesByUid = currentByUid
        _staleNodes.value = updatedStale

        val name = pendingSelectName
        if (name != null) {
            _selected.value = current.firstOrNull { it.name == name }
                ?: updatedStale.values.firstOrNull { it.name == name }
            pendingSelectName = null
        } else {
            _selected.value = _selected.value?.let { sel ->
                currentByUid[sel.uid] ?: updatedStale[sel.uid]
            }
        }
    }
}
