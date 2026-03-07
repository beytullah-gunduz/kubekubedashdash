package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import com.kubedash.NodeInfo
import com.kubedash.ResourceState
import com.kubedash.ResourceUsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

private const val MAX_HISTORY_SIZE = 20

class NodesScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _selected = MutableStateFlow<NodeInfo?>(null)
    val selected: StateFlow<NodeInfo?> = _selected.asStateFlow()

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _staleNodes = MutableStateFlow<Map<String, NodeInfo>>(emptyMap())
    val staleNodes: StateFlow<Map<String, NodeInfo>> = _staleNodes.asStateFlow()

    private var previousNodesByUid: Map<String, NodeInfo> = emptyMap()

    val state: StateFlow<ResourceState<List<NodeInfo>>> = flow {
        emit(ResourceState.Loading)
        var loaded = false
        while (true) {
            try {
                val nodes = kubeClient.getNodes()
                emit(ResourceState.Success(nodes))
                loaded = true
            } catch (e: Exception) {
                if (!loaded) {
                    emit(ResourceState.Error(e.message ?: "Unknown error"))
                }
            }
            delay(10_000)
        }
    }
        .onEach { state ->
            if (state is ResourceState.Success) processNodeUpdate(state.data)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResourceState.Loading)

    val resourceUsage: StateFlow<ResourceUsageSummary?> = flow {
        while (true) {
            val usage = try {
                kubeClient.getResourceUsage(null)
            } catch (_: Exception) {
                null
            }
            emit(usage)
            delay(10_000)
        }
    }
        .onEach { usage ->
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
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

        _selected.value = _selected.value?.let { sel ->
            currentByUid[sel.uid] ?: updatedStale[sel.uid]
        }
    }
}
