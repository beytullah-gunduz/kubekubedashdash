package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.DeploymentInfo
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class DeploymentsScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val namespace = MutableStateFlow<String?>(null)

    private val _selected = MutableStateFlow<DeploymentInfo?>(null)
    val selected: StateFlow<DeploymentInfo?> = _selected.asStateFlow()

    val state: StateFlow<ResourceState<List<DeploymentInfo>>> = namespace
        .flatMapLatest { ns ->
            flow {
                emit(ResourceState.Loading)
                var loaded = false
                while (true) {
                    try {
                        val deps = kubeClient.getDeployments(ns)
                        emit(ResourceState.Success(deps))
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
        .onEach { state ->
            if (state is ResourceState.Success) syncSelection(state.data)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    fun setNamespace(namespace: String?) {
        _selected.value = null
        this.namespace.value = namespace
    }

    fun selectItem(item: DeploymentInfo?) {
        _selected.value = if (_selected.value?.uid == item?.uid) null else item
    }

    fun clearSelection() {
        _selected.value = null
    }

    private fun syncSelection(current: List<DeploymentInfo>) {
        _selected.value = _selected.value?.let { sel -> current.find { it.uid == sel.uid } }
    }
}
