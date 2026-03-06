package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.GenericResourceInfo
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

class NamespacesScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<List<GenericResourceInfo>>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<List<GenericResourceInfo>>> = _state.asStateFlow()

    private val _selected = MutableStateFlow<GenericResourceInfo?>(null)
    val selected: StateFlow<GenericResourceInfo?> = _selected.asStateFlow()

    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val items = withContext(Dispatchers.IO) { kubeClient.getNamespacesGeneric() }
                    _state.value = ResourceState.Success(items)
                    syncSelection(items)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(5_000)
            }
        }
    }

    fun selectItem(item: GenericResourceInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    private fun syncSelection(current: List<GenericResourceInfo>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
