package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import com.kubedash.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServicesScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<List<ServiceInfo>>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<List<ServiceInfo>>> = _state.asStateFlow()

    private val _selected = MutableStateFlow<ServiceInfo?>(null)
    val selected: StateFlow<ServiceInfo?> = _selected.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(namespace: String?) {
        pollingJob?.cancel()
        _state.value = ResourceState.Loading
        _selected.value = null
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val svcs = withContext(Dispatchers.IO) { kubeClient.getServices(namespace) }
                    _state.value = ResourceState.Success(svcs)
                    syncSelection(svcs)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(5_000)
            }
        }
    }

    fun selectItem(item: ServiceInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    fun clearSelection() {
        _selected.value = null
    }

    private fun syncSelection(current: List<ServiceInfo>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
