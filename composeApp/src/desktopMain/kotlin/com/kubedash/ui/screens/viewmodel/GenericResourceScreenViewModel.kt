package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.GenericResourceInfo
import com.kubedash.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class GenericResourceScreenViewModel(
    private val fetcher: () -> List<GenericResourceInfo>,
) : ViewModel() {
    private val _selected = MutableStateFlow<GenericResourceInfo?>(null)
    val selected: StateFlow<GenericResourceInfo?> = _selected.asStateFlow()

    val state: StateFlow<ResourceState<List<GenericResourceInfo>>> = flow {
        emit(ResourceState.Loading)
        var loaded = false
        while (true) {
            try {
                val items = fetcher()
                emit(ResourceState.Success(items))
                loaded = true
            } catch (e: Exception) {
                if (!loaded) {
                    emit(ResourceState.Error(e.message ?: "Unknown error"))
                }
            }
            delay(5_000)
        }
    }
        .onEach { state ->
            if (state is ResourceState.Success) syncSelection(state.data)
        }
        .flowOn(Dispatchers.IO)
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
