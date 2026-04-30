package com.kubekubedashdash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.Screen
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.util.CheckStatus
import com.kubekubedashdash.util.MockClusterProvider
import com.kubekubedashdash.util.PrerequisiteCheck
import com.kubekubedashdash.util.PrerequisiteChecker
import com.kubekubedashdash.util.PrerequisiteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-shell ViewModel — owns app-level state (kubeconfig contexts, prerequisite-check
 * status, EKS-discovery modal flag, cluster-picker visibility) and forwards
 * per-session reads/writes to [SessionViewModel] via the active session.
 *
 * Per-session state (current screen, namespace, search query, connection flags)
 * lives on the active [com.kubekubedashdash.model.ClusterSession]'s
 * [SessionViewModel]. The forwarding properties below preserve the existing public
 * API so [com.kubekubedashdash.ui.App] doesn't have to know about the slicing.
 */
class AppViewModel : ViewModel() {

    private val sessionVm get() = WorkspaceManager.activeSession.viewModel

    // ── Forwarded per-session state ─────────────────────────────────────────────

    val currentScreen: StateFlow<Screen> get() = sessionVm.currentScreen
    val previousScreen: StateFlow<Screen?> get() = sessionVm.previousScreen
    val extraPaneScreen: StateFlow<Screen?> get() = sessionVm.extraPaneScreen
    val selectedNamespace: StateFlow<String> get() = sessionVm.selectedNamespace
    val selectedContext: StateFlow<String> get() = sessionVm.selectedContext
    val namespaces: StateFlow<List<String>> get() = sessionVm.namespaces
    val connectionError: StateFlow<String?> get() = sessionVm.connectionError
    val isConnecting: StateFlow<Boolean> get() = sessionVm.isConnecting
    val isConnected: StateFlow<Boolean> get() = sessionVm.isConnected
    val searchQuery: StateFlow<String> get() = sessionVm.searchQuery
    val retryCountdown: StateFlow<Int> get() = sessionVm.retryCountdown

    // ── App-shell state ─────────────────────────────────────────────────────────

    private val _contexts = MutableStateFlow<List<String>>(emptyList())
    val contexts: StateFlow<List<String>> = _contexts.asStateFlow()

    private val _showClusterSelector = MutableStateFlow(false)
    val showClusterSelector: StateFlow<Boolean> = _showClusterSelector.asStateFlow()

    private val _prerequisiteResult = MutableStateFlow<PrerequisiteResult?>(null)
    val prerequisiteResult: StateFlow<PrerequisiteResult?> = _prerequisiteResult.asStateFlow()

    private val _showPrerequisites = MutableStateFlow(true)
    val showPrerequisites: StateFlow<Boolean> = _showPrerequisites.asStateFlow()

    private val _showEksDiscovery = MutableStateFlow(false)
    val showEksDiscovery: StateFlow<Boolean> = _showEksDiscovery.asStateFlow()

    init {
        runPrerequisiteChecks()
    }

    // ── Forwarded per-session methods ───────────────────────────────────────────

    fun navigate(screen: Screen) = sessionVm.navigate(screen)
    fun closeExtraPane() = sessionVm.closeExtraPane()
    fun connectToCluster(ctx: String) = sessionVm.connectToCluster(ctx)
    fun setSelectedNamespace(namespace: String) = sessionVm.setSelectedNamespace(namespace)
    fun setSearchQuery(query: String) = sessionVm.setSearchQuery(query)

    // ── App-shell methods ───────────────────────────────────────────────────────

    private fun runPrerequisiteChecks() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { PrerequisiteChecker.runAll() }
            _prerequisiteResult.value = result
            if (result.allPassed) {
                _showPrerequisites.value = false
                _showClusterSelector.value = true
                withContext(Dispatchers.IO) {
                    _contexts.value = listOf(MockClusterProvider.MOCK_CONTEXT_NAME) +
                        WorkspaceManager.activeSession.connectionManager.getContexts()
                }
            }
        }
    }

    fun showClusterSelector() {
        _showClusterSelector.value = true
    }

    fun dismissClusterSelector() {
        _showClusterSelector.value = false
    }

    fun dismissPrerequisites() {
        _showPrerequisites.value = false
        _showClusterSelector.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _contexts.value = listOf(MockClusterProvider.MOCK_CONTEXT_NAME) +
                WorkspaceManager.activeSession.connectionManager.getContexts()
        }
    }

    fun loadingPrerequisiteResult(): PrerequisiteResult = PrerequisiteResult(
        listOf(
            PrerequisiteCheck(
                name = "Initializing",
                description = "Running system checks…",
                status = CheckStatus.CHECKING,
            ),
        ),
    )

    fun showEksDiscovery() {
        _showEksDiscovery.value = true
    }

    fun dismissEksDiscovery() {
        _showEksDiscovery.value = false
    }

    fun onEksImportComplete() {
        _showEksDiscovery.value = false
        if (_showPrerequisites.value) {
            runPrerequisiteChecks()
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                _contexts.value = listOf(MockClusterProvider.MOCK_CONTEXT_NAME) +
                    WorkspaceManager.activeSession.connectionManager.getContexts()
            }
        }
    }
}
