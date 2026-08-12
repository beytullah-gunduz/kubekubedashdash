package com.kubekubedashdash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.Identifiable
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.BulkActionRunner
import com.kubekubedashdash.ui.components.SelectionFunnel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Base for simple resource-list view models: owns the [selected] item, exposes the
 * list [state], and keeps the selection in sync with the latest data by [Identifiable.uid].
 * Subclasses just supply the source flow.
 */
abstract class ResourceListViewModel<T : Identifiable>(
    source: Flow<ResourceState<List<T>>>,
) : ViewModel() {

    private val _selected = MutableStateFlow<T?>(null)
    val selected: StateFlow<T?> = _selected.asStateFlow()

    /** Multi-select bulk-action state; wired by screens that opt in. */
    val selection = SelectionFunnel()
    val bulkRunner = BulkActionRunner<T>(viewModelScope)

    val state: StateFlow<ResourceState<List<T>>> = source
        .onEach { state -> if (state is ResourceState.Success) syncSelection(state.data) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    fun selectItem(item: T?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    fun clearSelection() {
        _selected.value = null
    }

    private fun syncSelection(current: List<T>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
