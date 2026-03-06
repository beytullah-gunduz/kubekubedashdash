package com.kubedash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubedash.KubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewerScreenViewModel(
    private val kubeClient: KubeClient,
) : ViewModel() {
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    private val _filterText = MutableStateFlow("")
    val filterText: StateFlow<String> = _filterText.asStateFlow()

    private val _wrapLines = MutableStateFlow(false)
    val wrapLines: StateFlow<Boolean> = _wrapLines.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling(podName: String, namespace: String, containerName: String?) {
        pollingJob?.cancel()
        _loading.value = true
        _logLines.value = emptyList()

        pollingJob = viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) {
                kubeClient.getPodLogs(podName, namespace, containerName, tailLines = 2000)
            }
            _logLines.value = logs.lines()
            _loading.value = false

            while (isActive) {
                delay(3_000)
                val refreshed = withContext(Dispatchers.IO) {
                    kubeClient.getPodLogs(podName, namespace, containerName, tailLines = 2000)
                }
                _logLines.value = refreshed.lines()
            }
        }
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
        _logLines.value = emptyList()
    }
}
