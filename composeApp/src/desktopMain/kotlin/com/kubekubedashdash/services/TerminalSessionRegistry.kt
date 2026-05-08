package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.TerminalSession
import com.kubekubedashdash.model.TerminalSessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of live `TerminalSession`s, keyed by composite id.
 * Mirrors [LogStreamRegistry] — see that file for the analogous pattern.
 *
 * Used by [com.kubekubedashdash.services.WorkspaceManager.closeSession] to
 * tear down all of a cluster's terminals when its tab closes, and (later) by
 * pod row indicators that want to show "this pod has a live terminal."
 */
object TerminalSessionRegistry {
    private val _sessions = MutableStateFlow<Map<String, TerminalSession>>(emptyMap())
    val sessions: StateFlow<Map<String, TerminalSession>> = _sessions.asStateFlow()

    fun openOrFocus(
        cluster: ClusterSession,
        podName: String,
        namespace: String,
        container: String,
    ): TerminalSession {
        val id = TerminalSessionId.of(cluster.id.value, namespace, podName, container)
        _sessions.value[id.value]?.let { return it }
        val session = TerminalSession(id, cluster, podName, namespace, container)
        _sessions.update { it + (id.value to session) }
        return session
    }

    fun close(id: TerminalSessionId) {
        _sessions.update { current ->
            current[id.value]?.close()
            current - id.value
        }
    }

    fun closeAllForSession(sessionId: SessionId) {
        val toClose = _sessions.value.values.filter { it.clusterSession.id == sessionId }
        toClose.forEach { it.close() }
        _sessions.update { current -> current - toClose.map { it.id.value }.toSet() }
    }
}
