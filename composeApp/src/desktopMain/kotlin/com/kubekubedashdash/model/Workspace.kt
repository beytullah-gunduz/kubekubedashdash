package com.kubekubedashdash.model

import androidx.compose.ui.window.WindowPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One OS window's worth of cluster sessions. A workspace holds an ordered list of
 * [ClusterSession]s (rendered as tabs when N≥2) and tracks which one is currently
 * active.
 *
 * Per-window concerns also live here: the cluster-picker visibility flag is
 * scoped per window (Decision 1 in `.docs/multi-cluster-plan.md`) so two open
 * windows can independently show or hide their own pickers.
 *
 * [initialPosition] is the desired top-left position when this workspace's OS
 * window first opens. Set when [com.kubekubedashdash.services.WorkspaceManager.tearOutSession]
 * spawns a new window at the cursor; otherwise null and the window opens at
 * the platform default.
 */
class Workspace(
    val id: WorkspaceId = WorkspaceId.new(),
    val initialPosition: WindowPosition? = null,
) {
    private val _sessions = MutableStateFlow<List<ClusterSession>>(emptyList())
    val sessions: StateFlow<List<ClusterSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<SessionId?>(null)
    val activeSessionId: StateFlow<SessionId?> = _activeSessionId.asStateFlow()

    private val _showClusterSelector = MutableStateFlow(false)
    val showClusterSelector: StateFlow<Boolean> = _showClusterSelector.asStateFlow()

    private val _showEksDiscovery = MutableStateFlow(false)
    val showEksDiscovery: StateFlow<Boolean> = _showEksDiscovery.asStateFlow()

    /** Snapshot accessor — the active session at this instant, or null if empty. */
    val activeSession: ClusterSession?
        get() = _sessions.value.firstOrNull { it.id == _activeSessionId.value }

    internal fun addSession(session: ClusterSession, makeActive: Boolean = true) {
        _sessions.value = _sessions.value + session
        if (makeActive) _activeSessionId.value = session.id
    }

    internal fun removeSession(id: SessionId): ClusterSession? {
        val session = _sessions.value.firstOrNull { it.id == id } ?: return null
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_activeSessionId.value == id) {
            _activeSessionId.value = _sessions.value.firstOrNull()?.id
        }
        return session
    }

    internal fun setActive(id: SessionId) {
        if (_sessions.value.any { it.id == id }) {
            _activeSessionId.value = id
        }
    }

    fun showClusterSelector() {
        _showClusterSelector.value = true
    }

    fun dismissClusterSelector() {
        _showClusterSelector.value = false
    }

    fun showEksDiscovery() {
        _showEksDiscovery.value = true
    }

    fun dismissEksDiscovery() {
        _showEksDiscovery.value = false
    }
}
