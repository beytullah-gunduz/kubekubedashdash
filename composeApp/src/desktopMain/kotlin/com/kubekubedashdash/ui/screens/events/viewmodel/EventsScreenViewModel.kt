package com.kubekubedashdash.ui.screens.events.viewmodel

import androidx.lifecycle.ViewModel
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.flow.StateFlow

class EventsScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ViewModel() {
    val state: StateFlow<ResourceState<List<EventInfo>>> = reactiveClient.events
}
