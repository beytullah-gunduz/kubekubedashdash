package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.ClusterInfo
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClusterOverviewViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<ClusterInfo>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<ClusterInfo>> = _state.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(namespace: String?) {
        pollingJob?.cancel()
        _state.value = ResourceState.Loading
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val info = withContext(Dispatchers.IO) { kubeClient.getClusterInfo(namespace) }
                    _state.value = ResourceState.Success(info)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(10_000)
            }
        }
    }
}
