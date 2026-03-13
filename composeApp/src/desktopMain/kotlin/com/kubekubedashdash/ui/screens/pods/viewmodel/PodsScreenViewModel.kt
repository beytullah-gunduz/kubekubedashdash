package com.kubekubedashdash.ui.screens.pods.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.services.KubeClientService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class PodsScreenViewModel : ViewModel() {
    private val reactiveClient = KubeClientService.reactiveClient

    private val _selectedPod = MutableStateFlow<PodInfo?>(null)
    val selectedPod: StateFlow<PodInfo?> = _selectedPod.asStateFlow()

    private val _stalePods = MutableStateFlow<Map<String, PodInfo>>(emptyMap())
    val stalePods: StateFlow<Map<String, PodInfo>> = _stalePods.asStateFlow()

    private var previousPodsByUid: Map<String, PodInfo> = emptyMap()
    private var pendingSelectUid: String? = null

    val state: StateFlow<ResourceState<List<PodInfo>>> = reactiveClient.pods
        .onEach { state ->
            if (state is ResourceState.Loading) {
                _stalePods.value = emptyMap()
                previousPodsByUid = emptyMap()
            }
            if (state is ResourceState.Success) processPodUpdate(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    val resourceUsage: StateFlow<ResourceUsageSummary?> = reactiveClient.resourceUsage
        .map { state -> if (state is ResourceState.Success) state.data else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setParams(selectPodUid: String? = null) {
        _selectedPod.value = null
        _stalePods.value = emptyMap()
        previousPodsByUid = emptyMap()
        pendingSelectUid = selectPodUid
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
