package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.KubeConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The testable core of port forwarding: no [com.kubekubedashdash.model.ClusterSession] dependency. */
class PortForwardManager internal constructor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val resolver: PortForwardResolver = Fabric8PortForwardResolver,
    private val connectorFor: (context: String) -> PortForwardConnector =
        { ctx -> if (DemoContext.isMockContext(ctx)) DemoPortForwardConnector else Fabric8PortForwardConnector },
    private val sweepIntervalMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(PortForwardManager::class.java)
    private val _forwards = MutableStateFlow<List<PortForwardEntry>>(emptyList())
    val forwards: StateFlow<List<PortForwardEntry>> = _forwards.asStateFlow()
    private val sessions = ConcurrentHashMap<String, PortForwardSession>()
    private val sweepJobs = ConcurrentHashMap<String, Job>()
    private val nextId = AtomicLong(0)

    /** Blocking — call on Dispatchers.IO. */
    fun start(sessionId: String, connection: KubeConnectionManager, target: PortForwardTarget, localPort: Int?): StartResult {
        if (!isValidPort(target.remotePort) || (localPort != null && !isValidPort(localPort))) {
            throw PortForwardStartException("Enter a port between 1 and 65535")
        }
        val context = connection.connectedContextOrNull() ?: throw PortForwardStartException(NOT_CONNECTED_MESSAGE)
        findRunning(sessionId, context, target)?.let { return StartResult(it, alreadyRunning = true) }
        val client = connection.clientIfConnectedTo(context) ?: throw PortForwardStartException(NOT_CONNECTED_MESSAGE)
        if (!DemoContext.isMockContext(context) && resolver.canPortForward(client, target.namespace) == false) {
            throw PortForwardStartException(rbacMessage(target.namespace))
        }
        val resolved = try {
            resolver.resolve(client, target)
        } catch (e: PortForwardStartException) {
            throw e
        } catch (e: Exception) {
            throw PortForwardStartException("Could not look up ${target.kind.label} ${target.namespace}/${target.name}: ${e.message ?: e::class.simpleName}")
        }
        val server = bindLoopback(localPort)
        synchronized(this) {
            findRunning(sessionId, context, target)?.let {
                runCatching { server.close() }
                return StartResult(it, alreadyRunning = true)
            }
            val id = "pf-${nextId.incrementAndGet()}"
            val entry = PortForwardEntry(
                id = id,
                sessionId = sessionId,
                context = context,
                target = target,
                localPort = (server.localAddress as InetSocketAddress).port,
                status = PortForwardStatus.Active,
                podName = resolved.podName,
                podPort = resolved.podPort,
                startedAtMillis = clock(),
            )
            val session = PortForwardSession(
                id = id,
                server = server,
                context = context,
                target = target,
                initial = resolved,
                connection = connection,
                resolver = resolver,
                connector = connectorFor(context),
                onAccepted = { updateEntry(id) { it.copy(connectionsServed = it.connectionsServed + 1) } },
                onResolved = { resolvedTarget -> updateEntry(id) { it.copy(podName = resolvedTarget.podName, podPort = resolvedTarget.podPort) } },
                onError = { msg -> updateEntry(id) { it.copy(lastError = msg) } },
                onFatal = { msg -> stopInternal(id, keepEntry = true, reason = "Stopped listening: $msg") },
            )
            sessions[id] = session
            _forwards.update { it + entry }
            session.start()
            sweepJobs[id] = scope.launch { sweepLoop(id, connection, context, target.kind) }
            log.info(
                "Port forward {} started: {} -> {} {}/{}:{}",
                id,
                "127.0.0.1:${entry.localPort}",
                target.kind.label,
                target.namespace,
                target.name,
                target.remotePort,
            )
            return StartResult(entry, false)
        }
    }

    private suspend fun sweepLoop(id: String, connection: KubeConnectionManager, context: String, kind: PortForwardKind) {
        while (currentCoroutineContext().isActive) {
            delay(sweepIntervalMs)
            val session = sessions[id] ?: return
            if (connection.isClosed) {
                // D15: the tab's manager is gone (e.g. a start that raced closeTab's stopAllForSession).
                stopInternal(id, keepEntry = true, reason = CLOSED_REASON)
                return
            }
            val current = connection.connectedContextOrNull()
            if (current != null && current != context) {
                stopInternal(id, keepEntry = true, reason = SWITCHED_REASON)
                return
            }
            val sweep = session.sweep()
            val newStatus = if (current == null) PortForwardStatus.Disconnected else PortForwardStatus.Active
            updateEntry(id) { e ->
                // An onFatal stop may have landed after this iteration passed the sessions[id]
                // check: never overwrite a Stopped entry with Active/Disconnected.
                if (!e.isRunning) return@updateEntry e
                val reconnected = e.status == PortForwardStatus.Disconnected && newStatus == PortForwardStatus.Active
                e.copy(
                    status = newStatus,
                    lastError = when {
                        sweep.error != null -> sweep.error
                        sweep.cleanEnds > 0 -> null
                        reconnected && e.lastError == DISCONNECTED_MESSAGE -> null
                        else -> e.lastError
                    },
                )
            }
        }
    }

    fun stop(id: String) = stopInternal(id, keepEntry = false, reason = null)

    /** Removes a Stopped entry; a running one is left alone. */
    fun remove(id: String) = _forwards.update { list -> list.filterNot { it.id == id && !it.isRunning } }

    fun stopAllForSession(sessionId: String) = _forwards.value.filter { it.sessionId == sessionId }.forEach { stopInternal(it.id, keepEntry = false, reason = null) }

    fun stopAll() = _forwards.value.forEach { stopInternal(it.id, keepEntry = false, reason = null) }

    /** Test hook. */
    internal fun activeHandleCount(id: String): Int = sessions[id]?.handleCount ?: 0

    private fun stopInternal(id: String, keepEntry: Boolean, reason: String?) {
        sweepJobs.remove(id)?.cancel()
        val session = sessions.remove(id)
        if (keepEntry) {
            updateEntry(id) { it.copy(status = PortForwardStatus.Stopped(reason ?: "Stopped")) }
        } else {
            _forwards.update { list -> list.filterNot { it.id == id } }
        }
        session?.close() // synchronous: closing the server socket and fabric8 handles does not block
        if (session != null) log.info("Port forward {} stopped{}", id, reason?.let { ": $it" } ?: "")
    }

    private fun findRunning(sessionId: String, context: String, target: PortForwardTarget) = _forwards.value.firstOrNull { it.sessionId == sessionId && it.context == context && it.target == target && it.isRunning }

    private fun updateEntry(id: String, f: (PortForwardEntry) -> PortForwardEntry) = _forwards.update { list -> list.map { if (it.id == id) f(it) else it } }
}

internal fun bindLoopback(localPort: Int?): ServerSocketChannel {
    val port = localPort ?: 0
    val channel = ServerSocketChannel.open()
    try {
        channel.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port))
        return channel
    } catch (e: IOException) {
        runCatching { channel.close() }
        val inUse = e is BindException && e.message?.contains("in use", ignoreCase = true) == true
        throw PortForwardStartException(
            if (inUse) {
                "Local port $port is already in use. Pick another port, or leave it empty for a random free one."
            } else {
                "Can't listen on $LOOPBACK_HOST:$port — ${e.message ?: e::class.simpleName}"
            },
        )
    }
}
