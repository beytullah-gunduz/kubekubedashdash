package com.kubekubedashdash.ui.screens.services.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ServiceInfo
import com.kubekubedashdash.services.KubeClientService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class ServicesScreenViewModel : ViewModel() {
    private val reactiveClient = KubeClientService.reactiveClient

    private val _selected = MutableStateFlow<ServiceInfo?>(null)
    val selected: StateFlow<ServiceInfo?> = _selected.asStateFlow()

    val state: StateFlow<ResourceState<List<ServiceInfo>>> = reactiveClient.services
        .onEach { state ->
            if (state is ResourceState.Success) syncSelection(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

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
