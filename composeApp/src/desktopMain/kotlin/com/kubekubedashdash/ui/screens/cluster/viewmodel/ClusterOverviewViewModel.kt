package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.services.KubeClientService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ClusterOverviewViewModel : ViewModel() {
    private val reactiveClient = KubeClientService.reactiveClient

    val state: StateFlow<ResourceState<ClusterInfo>> = reactiveClient.clusterInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)
}
