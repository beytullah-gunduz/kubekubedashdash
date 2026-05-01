package com.kubekubedashdash.ui.screens.events.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class EventsScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    private val _selected = MutableStateFlow<EventInfo?>(null)
    val selected: StateFlow<EventInfo?> = _selected.asStateFlow()

    private var pendingSelectUid: String? = null

    val state: StateFlow<ResourceState<List<EventInfo>>> = reactiveClient.events
        .onEach { s ->
            if (s is ResourceState.Success) {
                val uid = pendingSelectUid
                if (uid != null) {
                    _selected.value = s.data.firstOrNull { it.uid == uid }
                    pendingSelectUid = null
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    fun setParams(selectEventUid: String? = null) {
        _selected.value = null
        pendingSelectUid = selectEventUid
        if (selectEventUid != null) {
            val current = state.value
            if (current is ResourceState.Success) {
                _selected.value = current.data.firstOrNull { it.uid == selectEventUid }
                pendingSelectUid = null
            }
        }
    }
}
