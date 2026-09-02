package com.kubekubedashdash.ui.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.services.session.RestorePlanner
import com.kubekubedashdash.services.session.SessionPersistence
import com.kubekubedashdash.services.session.SessionRestorer
import com.kubekubedashdash.util.CheckStatus
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.PrerequisiteCheck
import com.kubekubedashdash.util.PrerequisiteChecker
import com.kubekubedashdash.util.PrerequisiteResult
import com.kubekubedashdash.util.ScreenBoundsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

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
    private val log = LoggerFactory.getLogger(AppViewModel::class.java)

    private val _contexts = MutableStateFlow<List<String>>(emptyList())
    val contexts: StateFlow<List<String>> = _contexts.asStateFlow()
    val hasRealContexts: StateFlow<Boolean> = _contexts
        .map { list -> list.any { !DemoContext.isMockContext(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = false)

    private val _prerequisiteResult = MutableStateFlow<PrerequisiteResult?>(null)
    val prerequisiteResult: StateFlow<PrerequisiteResult?> = _prerequisiteResult.asStateFlow()

    private val _showPrerequisites = MutableStateFlow(true)
    val showPrerequisites: StateFlow<Boolean> = _showPrerequisites.asStateFlow()

    /**
     * Flips to `true` once the initial prereq pass completes — and, when
     * applicable, the contexts list has been loaded. Lets the UI render a
     * dedicated splash while we don't yet know whether to show FirstRunScreen,
     * the cluster selector, or the prereq-failure modal, instead of flashing
     * FirstRunScreen behind the modal during bootstrap.
     */
    private val _bootstrapComplete = MutableStateFlow(false)
    val bootstrapComplete: StateFlow<Boolean> = _bootstrapComplete.asStateFlow()

    init {
        runPrerequisiteChecks()
    }

    private suspend fun loadContextsSync(): List<String> = withContext(Dispatchers.IO) {
        listOf(DemoContext.MOCK_CONTEXT_NAME) +
            WorkspaceManager.activeSession.connectionManager.getContexts()
    }

    /**
     * Reload the kubeconfig contexts list and, if real contexts are now available
     * AND no workspace currently has a connected cluster session, automatically
     * open the cluster selector on the first workspace. This is the right
     * behavior for every "user just gained contexts" entry point — initial
     * prereq pass, dismissing the modal, post-EKS-import on first run, and
     * Re-scan from the welcome screen — but a no-op when the user already has a
     * session active (e.g. an EKS import triggered from inside an open cluster).
     */
    private suspend fun loadContextsAndOfferSelector() {
        val loaded = loadContextsSync()
        _contexts.value = loaded
        val hasReal = loaded.any { !DemoContext.isMockContext(it) }
        restoreSessionOnce(loaded)
        if (hasReal && !anyWorkspaceHasActiveCluster()) {
            WorkspaceManager.workspaces.value.firstOrNull()?.showClusterSelector()
        }
    }

    private fun anyWorkspaceHasActiveCluster(): Boolean = WorkspaceManager.workspaces.value.any { ws ->
        ws.tabs.value.filterIsInstance<WorkspaceTab.Cluster>()
            .any { it.session.viewModel.selectedContext.value.isNotBlank() }
    }

    @Volatile
    private var restoreAttempted = false

    /**
     * First call only: rebuild the last session's windows and tabs, then start
     * saving. Runs on viewModelScope's main dispatcher, which is what
     * WorkspaceManager's mutators expect. A restored tab sets its context
     * synchronously, so the picker check that follows stays quiet; with
     * nothing to restore the picker behaves exactly as before.
     */
    private suspend fun restoreSessionOnce(availableContexts: List<String>) {
        if (restoreAttempted) return
        restoreAttempted = true
        // The preference flows are seeded asynchronously from DataStore; wait
        // for that first emission so a user who turned restore OFF is not
        // overridden by the compile-time default.
        withTimeoutOrNull(2_000) { PreferenceRepository.preferencesLoaded.first { it } }
        if (PreferenceRepository.restoreSessionOnLaunch.value) {
            val plan = RestorePlanner.plan(
                SessionPersistence.initialSnapshot,
                availableContexts,
                ScreenBoundsProvider.current(),
            )
            runCatching { SessionRestorer.apply(plan) }
                .onFailure { log.warn("Session restore failed: {}", it::class.simpleName) }
        }
        SessionPersistence.start()
    }

    private fun runPrerequisiteChecks() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { PrerequisiteChecker.runAll() }
            _prerequisiteResult.value = result
            // A missing kubeconfig (or a kubeconfig with zero contexts) is a normal
            // first-launch state, not a prerequisite failure: FirstRunScreen handles it.
            // Only the modal blocks for genuine prereq problems (CLI missing for an
            // existing context, etc.).
            if (result.allPassed || result.onlyFirstRunIssues) {
                _showPrerequisites.value = false
                loadContextsAndOfferSelector()
            }
            // Flip last so the splash stays up until both the prereq result is
            // known AND (when applicable) contexts have been loaded — otherwise
            // FirstRunScreen flashes behind the modal during bootstrap.
            _bootstrapComplete.value = true
        }
    }

    fun dismissPrerequisites() {
        _showPrerequisites.value = false
        viewModelScope.launch { loadContextsAndOfferSelector() }
    }

    /**
     * Re-open the diagnostics modal from FirstRunScreen so power users can inspect
     * the prereq checks and the log stream when no kubeconfig is configured yet.
     */
    fun showDiagnostics() {
        _showPrerequisites.value = true
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
     * Reload the list of kubeconfig contexts. Used by the welcome-screen "Re-scan"
     * button and the post-EKS-import path: when fresh real contexts surface and
     * no cluster is currently connected, the cluster selector pops up so the
     * user can pick which one to open. While the reload is in flight and the
     * user has no active cluster, [bootstrapComplete] is flipped back to false
     * so the splash covers the gap — otherwise FirstRunScreen would flash
     * between modal close and cluster selector open.
     */
    fun refreshContexts() {
        val firstRunReload = !anyWorkspaceHasActiveCluster()
        if (firstRunReload) _bootstrapComplete.value = false
        viewModelScope.launch {
            loadContextsAndOfferSelector()
            if (firstRunReload) _bootstrapComplete.value = true
        }
    }

    /**
     * Called when the EKS-import flow completes (with or without imports). If
     * the prereq modal is still showing (the user installed an aws CLI mid-flow
     * for example), re-run the checks; otherwise just refresh contexts. In both
     * cases the cluster selector opens automatically when the user has no
     * active cluster yet — see [loadContextsAndOfferSelector] — and the
     * splash covers the reload window so FirstRunScreen doesn't flash.
     */
    fun onEksImportComplete() {
        if (_showPrerequisites.value) {
            // Mid-prereq-modal re-check: flip bootstrap state ourselves so the
            // splash covers the second pass. (runPrerequisiteChecks only flips
            // it back to true at the end.)
            if (!anyWorkspaceHasActiveCluster()) _bootstrapComplete.value = false
            runPrerequisiteChecks()
        } else {
            refreshContexts()
        }
    }

    internal fun overrideContextsForScreenshots(contexts: List<String>) {
        _contexts.value = contexts
    }

    companion object {
        val instance: AppViewModel by lazy { AppViewModel() }
    }
}
