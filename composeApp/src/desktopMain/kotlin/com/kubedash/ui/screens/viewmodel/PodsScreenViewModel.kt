package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import com.kubedash.PodInfo
import com.kubedash.ResourceState
import com.kubedash.ResourceUsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class PodsScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private data class PodParams(val namespace: String?, val selectPodUid: String? = null)

    private val params = MutableStateFlow(PodParams(null))

    private val _selectedPod = MutableStateFlow<PodInfo?>(null)
    val selectedPod: StateFlow<PodInfo?> = _selectedPod.asStateFlow()

    private val _stalePods = MutableStateFlow<Map<String, PodInfo>>(emptyMap())
    val stalePods: StateFlow<Map<String, PodInfo>> = _stalePods.asStateFlow()

    private var previousPodsByUid: Map<String, PodInfo> = emptyMap()
    private var pendingSelectUid: String? = null

    val state: StateFlow<ResourceState<List<PodInfo>>> = params
        .flatMapLatest { (ns, _) ->
            flow {
                emit(ResourceState.Loading)
                var loaded = false
                while (true) {
                    try {
                        val pods = kubeClient.getPods(ns)
                        emit(ResourceState.Success(pods))
                        loaded = true
                    } catch (e: Exception) {
                        if (!loaded) {
                            emit(ResourceState.Error(e.message ?: "Unknown error"))
                        }
                    }
                    delay(5_000)
                }
            }
        }
        .onEach { state ->
            if (state is ResourceState.Success) processPodUpdate(state.data)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    val resourceUsage: StateFlow<ResourceUsageSummary?> = params
        .flatMapLatest { (ns, _) ->
            flow {
                while (true) {
                    val usage = try {
                        kubeClient.getResourceUsage(ns)
                    } catch (_: Exception) {
                        null
                    }
                    emit(usage)
                    delay(10_000)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setParams(namespace: String?, selectPodUid: String? = null) {
        _selectedPod.value = null
        _stalePods.value = emptyMap()
        previousPodsByUid = emptyMap()
        pendingSelectUid = selectPodUid
        params.value = PodParams(namespace, selectPodUid)
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
