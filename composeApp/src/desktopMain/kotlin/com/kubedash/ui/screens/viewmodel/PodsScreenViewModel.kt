package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import com.kubedash.PodInfo
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

class PodsScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<List<PodInfo>>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<List<PodInfo>>> = _state.asStateFlow()

    private val _selectedPod = MutableStateFlow<PodInfo?>(null)
    val selectedPod: StateFlow<PodInfo?> = _selectedPod.asStateFlow()

    private val _resourceUsage = MutableStateFlow<ResourceUsageSummary?>(null)
    val resourceUsage: StateFlow<ResourceUsageSummary?> = _resourceUsage.asStateFlow()

    private val _stalePods = MutableStateFlow<Map<String, PodInfo>>(emptyMap())
    val stalePods: StateFlow<Map<String, PodInfo>> = _stalePods.asStateFlow()

    private var previousPodsByUid: Map<String, PodInfo> = emptyMap()
    private var pendingSelectUid: String? = null

    private var podPollingJob: Job? = null
    private var usagePollingJob: Job? = null

    fun startPolling(namespace: String?, selectPodUid: String? = null) {
        podPollingJob?.cancel()
        usagePollingJob?.cancel()
        _state.value = ResourceState.Loading
        _selectedPod.value = null
        _stalePods.value = emptyMap()
        previousPodsByUid = emptyMap()
        pendingSelectUid = selectPodUid

        podPollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val pods = withContext(Dispatchers.IO) { kubeClient.getPods(namespace) }
                    _state.value = ResourceState.Success(pods)
                    processPodUpdate(pods)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(5_000)
            }
        }

        usagePollingJob = viewModelScope.launch {
            while (isActive) {
                _resourceUsage.value = try {
                    withContext(Dispatchers.IO) { kubeClient.getResourceUsage(namespace) }
                } catch (_: Exception) {
                    null
                }
                delay(10_000)
            }
        }
    }

    fun selectPod(pod: PodInfo?) {
        _selectedPod.value = if (_selectedPod.value?.uid == pod?.uid) null else pod
    }

    fun clearSelection() {
        _selectedPod.value = null
    }

    private fun processPodUpdate(current: List<PodInfo>) {
        val currentByUid = current.associateBy { it.uid }

        val updatedStale = _stalePods.value.toMutableMap()
        updatedStale.keys.removeAll(currentByUid.keys)
        for ((uid, pod) in previousPodsByUid) {
            if (uid !in currentByUid && uid !in updatedStale) {
                updatedStale[uid] = pod
            }
        }

        previousPodsByUid = currentByUid
        _stalePods.value = updatedStale

        val uid = pendingSelectUid
        if (uid != null) {
            _selectedPod.value = currentByUid[uid] ?: updatedStale[uid]
            pendingSelectUid = null
        } else {
            _selectedPod.value = _selectedPod.value?.let { sel ->
                currentByUid[sel.uid] ?: updatedStale[sel.uid]
            }
        }
    }
}
