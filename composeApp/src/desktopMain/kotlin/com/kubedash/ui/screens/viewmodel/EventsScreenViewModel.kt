package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.EventInfo
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

class EventsScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _state = MutableStateFlow<ResourceState<List<EventInfo>>>(ResourceState.Loading)
    val state: StateFlow<ResourceState<List<EventInfo>>> = _state.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(namespace: String?) {
        pollingJob?.cancel()
        _state.value = ResourceState.Loading
        pollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val events = withContext(Dispatchers.IO) { kubeClient.getEvents(namespace) }
                    _state.value = ResourceState.Success(events)
                } catch (e: Exception) {
                    if (_state.value is ResourceState.Loading) {
                        _state.value = ResourceState.Error(e.message ?: "Unknown error")
                    }
                }
                delay(5_000)
            }
        }
    }
}
