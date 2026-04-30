package com.kubekubedashdash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * App-shell ViewModel — owns process-wide state that is meaningful at app
 * level rather than per-window or per-session: the kubeconfig contexts list,
 * the prerequisite-check result, and the prereq-modal visibility flag.
 *
 * One instance per app via [instance]. Multiple windows share it so the
 * prereq checks run exactly once at startup, and the contexts list refreshed
 * after EKS import is reflected everywhere.
 *
 * Per-window modal flags ([com.kubekubedashdash.model.Workspace.showClusterSelector],
 * [com.kubekubedashdash.model.Workspace.showEksDiscovery]) and per-session UI
 * state ([SessionViewModel]) live elsewhere — see Decision 1 in
 * `.docs/multi-cluster-plan.md`.
 */
class AppViewModel private constructor() : ViewModel() {

    private val _contexts = MutableStateFlow<List<String>>(emptyList())
    val contexts: StateFlow<List<String>> = _contexts.asStateFlow()

    private val _prerequisiteResult = MutableStateFlow<PrerequisiteResult?>(null)
    val prerequisiteResult: StateFlow<PrerequisiteResult?> = _prerequisiteResult.asStateFlow()

    private val _showPrerequisites = MutableStateFlow(true)
    val showPrerequisites: StateFlow<Boolean> = _showPrerequisites.asStateFlow()

    init {
        runPrerequisiteChecks()
    }

    private fun runPrerequisiteChecks() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { PrerequisiteChecker.runAll() }
            _prerequisiteResult.value = result
            if (result.allPassed) {
                _showPrerequisites.value = false
                WorkspaceManager.workspaces.value.firstOrNull()?.showClusterSelector()
                refreshContexts()
            }
        }
    }

    fun dismissPrerequisites() {
        _showPrerequisites.value = false
        WorkspaceManager.workspaces.value.firstOrNull()?.showClusterSelector()
        refreshContexts()
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

    /**
     * Reload the list of kubeconfig contexts. Call after the user imports new
     * EKS clusters so the picker shows them without a restart.
     */
    fun refreshContexts() {
        viewModelScope.launch(Dispatchers.IO) {
            _contexts.value = listOf(MockClusterProvider.MOCK_CONTEXT_NAME) +
                WorkspaceManager.activeSession.connectionManager.getContexts()
        }
    }

    /**
     * Called when the EKS-import flow completes (with or without imports).
     * If prereqs are still showing, re-run the checks (an aws-cli install just
     * happened); otherwise just refresh contexts so the new clusters appear in
     * the picker.
     */
    fun onEksImportComplete() {
        if (_showPrerequisites.value) {
            runPrerequisiteChecks()
        } else {
            refreshContexts()
        }
    }

    companion object {
        val instance: AppViewModel by lazy { AppViewModel() }
    }
}
