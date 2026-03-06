package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.DeploymentInfo
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

class DeploymentsScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<List<DeploymentInfo>>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<List<DeploymentInfo>>> = _state.asStateFlow()

    private val _selected = MutableStateFlow<DeploymentInfo?>(null)
    val selected: StateFlow<DeploymentInfo?> = _selected.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(namespace: String?) {
        pollingJob?.cancel()
        _state.value = ResourceState.Loading
        _selected.value = null
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val deps = withContext(Dispatchers.IO) { kubeClient.getDeployments(namespace) }
                    _state.value = ResourceState.Success(deps)
                    syncSelection(deps)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(5_000)
            }
        }
    }

    fun selectItem(item: DeploymentInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    fun clearSelection() {
        _selected.value = null
    }

    private fun syncSelection(current: List<DeploymentInfo>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
