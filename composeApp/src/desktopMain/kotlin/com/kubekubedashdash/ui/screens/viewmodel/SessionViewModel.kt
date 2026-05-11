package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.clusterHealthFlow
import com.kubekubedashdash.util.MockClusterProvider
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Stable key identifying a resource-kind screen (e.g. "Pods", "Deployments")
 * used to scope label/annotation filters per-screen on [SessionViewModel].
 * Returns an empty string for transient screens (Connecting, ConnectionError, etc.)
 * which means filters there share a single "" slot — harmless because those
 * screens never render the filter chips.
 */
fun screenKeyOf(screen: Screen): String = if (screen is Screen.Main) screen::class.simpleName.orEmpty() else ""

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

    // Per-screen label/annotation selectors, keyed by Screen.Main subclass simpleName
    // so each resource-kind screen keeps its own filter independent of others.
    private val _labelQueries = MutableStateFlow<Map<String, String>>(emptyMap())
    val labelQueries: StateFlow<Map<String, String>> = _labelQueries.asStateFlow()

    private val _annotationQueries = MutableStateFlow<Map<String, String>>(emptyMap())
    val annotationQueries: StateFlow<Map<String, String>> = _annotationQueries.asStateFlow()

    private val _retryCountdown = MutableStateFlow(0)
    val retryCountdown: StateFlow<Int> = _retryCountdown.asStateFlow()

    // Session-scoped health summary. Lives for the lifetime of this cluster
    // session so the sidebar can show a dot on the Cluster nav item from any
    // screen, not just when the cluster overview is visible. The cluster
    // overview screen has its own screen-scoped subscription via
    // [com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterOverviewViewModel.health];
    // both subscribe to the same upstream informers so there's no extra
    // network/api work — only the combine lambda runs in two places.
    val clusterHealth: StateFlow<ClusterHealthSummary?> = reactiveClient.clusterHealthFlow()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private var retryJob: Job? = null
    private var connectJob: Job? = null

    init {
        observeConnectionHealth()
        observeNamespaces()
        observeClusterInfoHealth()
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

    // Project clusterInfo's polling state onto the tab-ring's _isConnected
    // flag so the ring tracks actual reachability (red within one polling
    // tick after the cluster dies), not just the 3-failure connectionError
    // threshold which only governs navigation. Loading is intentionally
    // ignored — connectToCluster() owns _isConnecting, and Loading also
    // fires on the first subscription emission and on every connection-
    // version bump from connect()/scheduleRetry(), so reacting to it would
    // race those explicit writes.
    private fun observeClusterInfoHealth() {
        scope.launch {
            reactiveClient.clusterInfo.collect { state ->
                when (state) {
                    is ResourceState.Success -> {
                        if (!_isConnected.value) _isConnected.value = true
                    }

                    is ResourceState.Error -> {
                        if (_isConnected.value) _isConnected.value = false
                    }

                    is ResourceState.Loading -> Unit
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
        connectJob?.cancel()
        _retryCountdown.value = 0
        _selectedContext.value = ctx
        _isConnecting.value = true
        _connectionError.value = null
        _currentScreen.value = Screen.Main.Connecting
        connectJob = scope.launch(Dispatchers.IO) {
            val isMock = MockClusterProvider.isMockContext(ctx)
            val result = if (isMock) {
                // Bare prefix means "mint a new mock" (picker path); a `#N` label
                // means reattach (e.g. retry of an existing session).
                val mockLabel = if (ctx == MockClusterProvider.MOCK_CONTEXT_NAME) null else ctx
                reactiveClient.connectMock(mockLabel)
            } else {
                reactiveClient.connect(ctx)
            }
            result.fold(
                onSuccess = {
                    if (isMock) {
                        // Replace the picker's bare "demo-cluster (mock)" intent
                        // with the unique "...#N" the provider actually minted, so
                        // the chip text, color, and All Clusters aggregation key
                        // off the live label.
                        _selectedContext.value = reactiveClient.getCurrentContext()
                    }
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

    fun setLabelQuery(screenKey: String, query: String) {
        _labelQueries.update { it + (screenKey to query) }
    }

    fun setAnnotationQuery(screenKey: String, query: String) {
        _annotationQueries.update { it + (screenKey to query) }
    }
}
