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
 * - [NEW_WINDOW]: spawn a new window with this cluster (Phase 3, not yet wired).
 */
enum class OpenTarget { CURRENT_VIEW, NEW_TAB, NEW_WINDOW }

/**
 * Process-wide coordinator for [Workspace]s and the [ClusterSession]s they contain.
 *
 * Phase 2: bootstraps with one workspace + one (initially-disconnected) session
 * and supports `openCluster(target)` for `CURRENT_VIEW` and `NEW_TAB`. Phase 3
 * will add multi-window (`NEW_WINDOW`, multiple workspaces, last-window-quits-app).
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
     * The active session of the (currently single) workspace. Used only by the
     * legacy [KubeClientService] facade — new code should reach the active
     * session through `LocalViewModelStoreOwner` and the per-screen ViewModel.
     */
    val activeSession: ClusterSession
        get() = _workspaces.value.firstOrNull()?.activeSession
            ?: error("No active session — bootstrap should always create one")

    fun workspaceById(id: WorkspaceId): Workspace? = _workspaces.value.firstOrNull { it.id == id }

    /**
     * Apply a cluster pick from the modal. Mutates [workspace] according to
     * [target]: replace its active session, append a new session, or (Phase 3)
     * spawn a new window.
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

            OpenTarget.NEW_WINDOW -> error("NEW_WINDOW is not yet supported (Phase 3)")
        }
    }

    /**
     * Close a session in [workspace] and dispose its connection + ViewModels.
     * In Phase 2 the workspace is allowed to become empty; Phase 3 will close
     * the host window when that happens (and exit the app if it was the last
     * window).
     */
    fun closeSession(workspace: Workspace, sessionId: SessionId) {
        workspace.removeSession(sessionId)?.close()
    }
}
