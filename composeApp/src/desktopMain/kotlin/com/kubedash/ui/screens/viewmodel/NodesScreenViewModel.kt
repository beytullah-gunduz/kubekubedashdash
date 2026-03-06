package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import com.kubedash.NodeInfo
import com.kubedash.ResourceState
import com.kubedash.ResourceUsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_HISTORY_SIZE = 20

class NodesScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<List<NodeInfo>>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<List<NodeInfo>>> = _state.asStateFlow()

    private val _selected = MutableStateFlow<NodeInfo?>(null)
    val selected: StateFlow<NodeInfo?> = _selected.asStateFlow()

    private val _resourceUsage = MutableStateFlow<ResourceUsageSummary?>(null)
    val resourceUsage: StateFlow<ResourceUsageSummary?> = _resourceUsage.asStateFlow()

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _staleNodes = MutableStateFlow<Map<String, NodeInfo>>(emptyMap())
    val staleNodes: StateFlow<Map<String, NodeInfo>> = _staleNodes.asStateFlow()

    private var previousNodesByUid: Map<String, NodeInfo> = emptyMap()
    private var nodePollingJob: Job? = null
    private var usagePollingJob: Job? = null

    init {
        startPolling()
    }

    private fun startPolling() {
        nodePollingJob?.cancel()
        usagePollingJob?.cancel()

        nodePollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val nodes = withContext(Dispatchers.IO) { kubeClient.getNodes() }
                    _state.value = ResourceState.Success(nodes)
                    processNodeUpdate(nodes)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(10_000)
            }
        }

        usagePollingJob = viewModelScope.launch {
            while (isActive) {
                val usage = try {
                    withContext(Dispatchers.IO) { kubeClient.getResourceUsage(null) }
                } catch (_: Exception) {
                    null
                }
                _resourceUsage.value = usage
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
                delay(10_000)
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

        _selected.value = _selected.value?.let { sel ->
            currentByUid[sel.uid] ?: updatedStale[sel.uid]
        }
    }
}
