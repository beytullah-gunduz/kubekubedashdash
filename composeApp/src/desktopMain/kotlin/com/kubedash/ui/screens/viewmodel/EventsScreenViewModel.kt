package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.EventInfo
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class EventsScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val namespace = MutableStateFlow<String?>(null)

    val state: StateFlow<ResourceState<List<EventInfo>>> = namespace
        .flatMapLatest { ns ->
            flow {
                emit(ResourceState.Loading)
                var loaded = false
                while (true) {
                    try {
                        val events = kubeClient.getEvents(ns)
                        emit(ResourceState.Success(events))
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
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    fun setNamespace(namespace: String?) {
        this.namespace.value = namespace
    }
}
