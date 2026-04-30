package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide coordinator for [Workspace]s and the [ClusterSession]s they contain.
 *
 * Phase 1: bootstraps with exactly one workspace and one session — UX is unchanged.
 * Phase 2 will add `openCluster(target)` (current view / new tab / new window) and
 * tab/window plumbing on top of this same model.
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
     * The single active session for Phase 1. In Phase 2, screens will read their
     * session via a `LocalSessionViewModel` Composition Local instead of this
     * global accessor — the accessor will then become "the active session of
     * the focused window" and is intentionally narrow surface area.
     */
    val activeSession: ClusterSession
        get() = _workspaces.value.firstOrNull()?.activeSession
            ?: error("No active session — bootstrap should always create one")

    fun workspaceById(id: WorkspaceId): Workspace? = _workspaces.value.firstOrNull { it.id == id }
}
