package com.kubekubedashdash.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One OS window's worth of cluster sessions. A workspace holds an ordered list of
 * [ClusterSession]s (rendered as tabs once N≥2 lands in Phase 2) and tracks which
 * one is currently active.
 *
 * Phase 1 only ever has one workspace with one session; the multi-session API is
 * already present so Phase 2 can wire the tab strip without further refactoring.
 */
class Workspace(
    val id: WorkspaceId = WorkspaceId.new(),
) {
    private val _sessions = MutableStateFlow<List<ClusterSession>>(emptyList())
    val sessions: StateFlow<List<ClusterSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<SessionId?>(null)
    val activeSessionId: StateFlow<SessionId?> = _activeSessionId.asStateFlow()

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
}
