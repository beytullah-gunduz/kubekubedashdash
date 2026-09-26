package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import kotlinx.coroutines.flow.StateFlow

/**
 * The process-wide facade the UI uses. Mirrors `TerminalSessionRegistry` and
 * keeps [ClusterSession] out of the testable [PortForwardManager].
 */
object PortForwardRegistry {
    private val manager = PortForwardManager()
    val forwards: StateFlow<List<PortForwardEntry>> get() = manager.forwards

    /** Blocking — call on Dispatchers.IO. */
    fun start(session: ClusterSession, target: PortForwardTarget, localPort: Int?): StartResult = manager.start(session.id.value, session.connectionManager, target, localPort)

    fun stop(id: String) = manager.stop(id)
    fun remove(id: String) = manager.remove(id)
    fun stopAllForSession(sessionId: SessionId) = manager.stopAllForSession(sessionId.value)
    fun stopAll() = manager.stopAll()
}
