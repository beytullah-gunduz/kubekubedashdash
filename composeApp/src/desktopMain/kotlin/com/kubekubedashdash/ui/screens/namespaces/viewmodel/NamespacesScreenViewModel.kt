package com.kubekubedashdash.ui.screens.namespaces.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.services.KubeClientService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class NamespacesScreenViewModel : ViewModel() {
    private val reactiveClient = KubeClientService.reactiveClient
    private val _selected = MutableStateFlow<GenericResourceInfo?>(null)
    val selected: StateFlow<GenericResourceInfo?> = _selected.asStateFlow()

    val state: StateFlow<ResourceState<List<GenericResourceInfo>>> = reactiveClient.namespaces
        .onEach { state ->
            if (state is ResourceState.Success) syncSelection(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResourceState.Loading)

    fun selectItem(item: GenericResourceInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    private fun syncSelection(current: List<GenericResourceInfo>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
