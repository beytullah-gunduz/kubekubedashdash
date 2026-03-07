package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val podName = MutableStateFlow("")
    private val namespace = MutableStateFlow("")
    private val containerName = MutableStateFlow<String?>(null)
    private val resetTrigger = MutableStateFlow(0)

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    private val _filterText = MutableStateFlow("")
    val filterText: StateFlow<String> = _filterText.asStateFlow()

    private val _wrapLines = MutableStateFlow(false)
    val wrapLines: StateFlow<Boolean> = _wrapLines.asStateFlow()

    val logLines: StateFlow<List<String>> = combine(
        podName,
        namespace,
        containerName,
        resetTrigger,
    ) { pod, ns, container, _ -> Triple(pod, ns, container) }
        .flatMapLatest { (pod, ns, container) ->
            if (pod.isBlank()) {
                flowOf(emptyList())
            } else {
                kubeClient.streamPodLogs(pod, ns, container)
                    .runningFold(emptyList<String>()) { acc, line -> acc + line }
                    .onEach { if (it.isNotEmpty()) _loading.value = false }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setStreamParams(podName: String, namespace: String, containerName: String?) {
        _loading.value = true
        this.podName.value = podName
        this.namespace.value = namespace
        this.containerName.value = containerName
    }

    fun toggleFollowing() {
        _following.value = !_following.value
    }

    fun toggleWrapLines() {
        _wrapLines.value = !_wrapLines.value
    }

    fun setFilterText(text: String) {
        _filterText.value = text
    }

    fun clearLogs() {
        _loading.value = true
        resetTrigger.value++
    }
}
