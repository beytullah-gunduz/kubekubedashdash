package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a freshly-picked cluster lands when the user confirms it from the
 * cluster selector. Wired to per-row action buttons in
 * [com.kubekubedashdash.ui.modals.ClusterSelectorModal].
 *
 * - [CURRENT_VIEW]: replace the active session's connection in this window.
 * - [NEW_TAB]: append a new session to this window's workspace.
 * - [NEW_WINDOW]: spawn a new window/workspace pre-connected to this cluster.
 */
enum class OpenTarget { CURRENT_VIEW, NEW_TAB, NEW_WINDOW }

/**
 * Process-wide coordinator for [Workspace]s (one per OS window) and the
 * [ClusterSession]s they contain (one per tab).
 *
 * Phase 3: bootstraps with one workspace + one (initially-disconnected) session.
 * Supports all three [OpenTarget]s and propagates "close last tab → close
 * window" / "close last window → exit app" per Decision 2 of
 * `.docs/multi-cluster-plan.md`. The exit-app step happens in
 * [com.kubekubedashdash.Main]'s `application` block when [workspaces] goes
 * empty — there is no `exitApplication` call from this object directly,
 * keeping the lifecycle decision in Compose's scope.
 */
object WorkspaceManager {
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    init {
        val workspace = Workspace()
        workspace.addSession(ClusterSession(id = SessionId.new()), makeActive = true)
        _workspaces.value = listOf(workspace)
    }

    /**
     * The active session of the first workspace. Used only by the legacy
     * [KubeClientService] facade — new code should reach the active session via
     * `LocalViewModelStoreOwner` and the per-screen ViewModel, which is correctly
     * scoped to the user's currently-focused window/tab.
     */
    val activeSession: ClusterSession
        get() = _workspaces.value.firstOrNull()?.activeSession
            ?: error("No active session — bootstrap should always create one")

    fun workspaceById(id: WorkspaceId): Workspace? = _workspaces.value.firstOrNull { it.id == id }

    /**
     * Apply a cluster pick from the modal. Mutates state according to [target]:
     * replace the active session of [workspace], append a new session, or spawn
     * a fresh workspace (= new window) pre-connected to [ctx].
     *
     * If [workspace] has no active session yet (the bootstrap state right after
     * app launch), [NEW_TAB] is downgraded to [CURRENT_VIEW] so the user doesn't
     * end up with two pending sessions.
     */
    fun openCluster(workspace: Workspace, ctx: String, target: OpenTarget) {
        when (target) {
            OpenTarget.CURRENT_VIEW -> {
                val session = workspace.activeSession ?: ClusterSession().also {
                    workspace.addSession(it, makeActive = true)
                }
                session.viewModel.connectToCluster(ctx)
            }

            OpenTarget.NEW_TAB -> {
                val active = workspace.activeSession
                if (active == null || active.viewModel.selectedContext.value.isBlank()) {
                    // Bootstrap state: no real "current" view to add to. Just
                    // connect the existing empty session in place.
                    openCluster(workspace, ctx, OpenTarget.CURRENT_VIEW)
                    return
                }
                val session = ClusterSession()
                workspace.addSession(session, makeActive = true)
                session.viewModel.connectToCluster(ctx)
            }

            OpenTarget.NEW_WINDOW -> {
                val newWorkspace = Workspace()
                val session = ClusterSession()
                newWorkspace.addSession(session, makeActive = true)
                _workspaces.value = _workspaces.value + newWorkspace
                session.viewModel.connectToCluster(ctx)
            }
        }
    }

    /**
     * Close a session in [workspace] and dispose its connection + ViewModels.
     * If this empties [workspace], the workspace is closed too (Decision 2 — no
     * empty windows). Closing the last workspace propagates up via the
     * [workspaces] flow, which `Main.kt` watches to call `exitApplication`.
     */
    fun closeSession(workspace: Workspace, sessionId: SessionId) {
        workspace.removeSession(sessionId)?.close()
        if (workspace.sessions.value.isEmpty()) {
            closeWorkspace(workspace.id)
        }
    }

    /**
     * Close a workspace and dispose every session it still holds. Intended both
     * as the cascade target for [closeSession] when the last tab leaves and as
     * the OS-window-close handler.
     */
    fun closeWorkspace(workspaceId: WorkspaceId) {
        val workspace = _workspaces.value.firstOrNull { it.id == workspaceId } ?: return
        workspace.sessions.value.forEach { it.close() }
        _workspaces.value = _workspaces.value.filterNot { it.id == workspaceId }
    }
}
