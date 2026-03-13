package com.kubekubedashdash.ui.screens.events.viewmodel

import androidx.lifecycle.ViewModel
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.services.KubeClientService
import kotlinx.coroutines.flow.StateFlow

class EventsScreenViewModel : ViewModel() {
    private val reactiveClient = KubeClientService.reactiveClient

    val state: StateFlow<ResourceState<List<EventInfo>>> = reactiveClient.events
}
