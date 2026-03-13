package com.kubekubedashdash.ui.screens.generic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class GenericResourceScreenViewModel(
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
) : ViewModel() {
    private val _selected = MutableStateFlow<GenericResourceInfo?>(null)
    val selected: StateFlow<GenericResourceInfo?> = _selected.asStateFlow()

    val state: StateFlow<ResourceState<List<GenericResourceInfo>>> = sourceFlow
        .onEach { state ->
            if (state is ResourceState.Success) syncSelection(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ResourceState.Loading)

    fun selectItem(item: GenericResourceInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    fun clearSelection() {
        _selected.value = null
    }

    private fun syncSelection(current: List<GenericResourceInfo>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
