package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.MockClusterProvider
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Per-cluster-session UI state. One instance per [com.kubekubedashdash.model.ClusterSession]
 * — owns the navigation state, namespace selection, connection-status flags, and retry
 * scheduling for that single cluster.
 */
class SessionViewModel(
    val reactiveClient: ReactiveKubeClient,
    private val scope: CoroutineScope,
) {
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Main.Connecting)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _previousScreen = MutableStateFlow<Screen?>(null)
    val previousScreen: StateFlow<Screen?> = _previousScreen.asStateFlow()

    private val _extraPaneScreen = MutableStateFlow<Screen?>(null)
    val extraPaneScreen: StateFlow<Screen?> = _extraPaneScreen.asStateFlow()

    private val _selectedNamespace = MutableStateFlow("All Namespaces")
    val selectedNamespace: StateFlow<String> = _selectedNamespace.asStateFlow()

    private val _selectedContext = MutableStateFlow("")
    val selectedContext: StateFlow<String> = _selectedContext.asStateFlow()

    private val _namespaces = MutableStateFlow<List<String>>(emptyList())
    val namespaces: StateFlow<List<String>> = _namespaces.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _retryCountdown = MutableStateFlow(0)
    val retryCountdown: StateFlow<Int> = _retryCountdown.asStateFlow()

    private var retryJob: Job? = null

    init {
        observeConnectionHealth()
        observeNamespaces()
    }

    private fun observeNamespaces() {
        scope.launch {
            reactiveClient.namespaceNames.collect { state ->
                if (state is ResourceState.Success) {
                    _namespaces.value = state.data
                }
            }
        }
    }

    private fun observeConnectionHealth() {
        scope.launch {
            reactiveClient.connectionError.filterNotNull().collect { error ->
                if (_isConnected.value) {
                    _isConnected.value = false
                    _connectionError.value = error
                    _currentScreen.value = Screen.Main.ConnectionError(error, 10)
                    scheduleRetry()
                }
            }
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val ctx = _selectedContext.value
        if (ctx.isBlank()) return
        retryJob = scope.launch {
            for (countdown in 10 downTo 1) {
                _retryCountdown.value = countdown
                _currentScreen.value = Screen.Main.ConnectionError(_connectionError.value, countdown)
                delay(1_000)
            }
            _retryCountdown.value = 0
            connectToCluster(ctx)
        }
    }

    fun navigate(screen: Screen) {
        if (screen is Screen.Main.Pods && screen.selectPodUid != null) {
            _selectedNamespace.value = "All Namespaces"
        }
        if (screen is Screen.Detail) {
            _previousScreen.value = _currentScreen.value
            _extraPaneScreen.value = screen
        } else {
            _extraPaneScreen.value = null
            _currentScreen.value = screen
        }
    }

    fun closeExtraPane() {
        _extraPaneScreen.value = null
    }

    fun connectToCluster(ctx: String) {
        retryJob?.cancel()
        _retryCountdown.value = 0
        _selectedContext.value = ctx
        _isConnecting.value = true
        _connectionError.value = null
        _currentScreen.value = Screen.Main.Connecting
        scope.launch(Dispatchers.IO) {
            val isMock = ctx == MockClusterProvider.MOCK_CONTEXT_NAME
            val result = if (isMock) {
                reactiveClient.connectMock()
            } else {
                MockClusterProvider.stop()
                reactiveClient.connect(ctx)
            }
            result.fold(
                onSuccess = {
                    _isConnected.value = true
                    _connectionError.value = null
                    _currentScreen.value = Screen.Main.ClusterOverview
                    _selectedNamespace.value = "All Namespaces"
                    reactiveClient.setSelectedNamespace(null)
                },
                onFailure = { e ->
                    _isConnected.value = false
                    _connectionError.value = e.message
                    _currentScreen.value = Screen.Main.ConnectionError(e.message, 10)
                    if (!isMock) scheduleRetry()
                },
            )
            _isConnecting.value = false
        }
    }

    fun setSelectedNamespace(namespace: String) {
        _selectedNamespace.value = namespace
        reactiveClient.setSelectedNamespace(if (namespace == "All Namespaces") null else namespace)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
