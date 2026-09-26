package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.util.KubeConnectionManager
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList

/** Internal: one running forward — its accept loop, connection handles and sweep. */
internal class PortForwardSession(
    val id: String,
    private val server: ServerSocketChannel,
    private val context: String,
    private val target: PortForwardTarget,
    initial: ResolvedTarget,
    private val connection: KubeConnectionManager,
    private val resolver: PortForwardResolver,
    private val connector: PortForwardConnector,
    private val onAccepted: () -> Unit,
    private val onResolved: (ResolvedTarget) -> Unit,
    private val onError: (String) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    data class Sweep(val cleanEnds: Int, val error: String?)

    private val handles = CopyOnWriteArrayList<ForwardedConnection>()

    @Volatile
    private var resolved: ResolvedTarget? = initial

    @Volatile
    private var closed = false
    private val localPort = (server.localAddress as InetSocketAddress).port
    private val acceptThread = Thread(::acceptLoop, "port-forward-$localPort").apply { isDaemon = true }

    /** Test hook. */
    internal val handleCount: Int get() = handles.size

    fun start() = acceptThread.start()

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!closed) onFatal(e.message ?: e::class.simpleName ?: "accept failed")
                return
            }
            onAccepted()
            handle(socket)
        }
    }

    private fun handle(socket: SocketChannel) {
        if (closed) {
            runCatching { socket.close() }
            return
        }
        val client = connection.clientIfConnectedTo(context)
        if (client == null) {
            runCatching { socket.close() }
            onError(DISCONNECTED_MESSAGE)
            return
        }
        val to = resolved ?: try {
            resolver.resolve(client, target).also {
                resolved = it
                onResolved(it)
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            onError(cleanForwardError(e.message))
            return
        }
        // resolve() can take a GET+LIST's worth of time; don't open a websocket for a session stopped meanwhile.
        if (closed) {
            runCatching { socket.close() }
            return
        }
        try {
            val conn = connector.open(client, target.namespace, to.podName, to.podPort, socket)
            handles += conn
            // close() may have run while this thread was blocked in clientIfConnectedTo/resolve:
            // its handles.forEach{close} missed this one, so close it here.
            if (closed) {
                handles.remove(conn)
                runCatching { conn.close() }
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            if (target.kind == PortForwardKind.SERVICE) resolved = null
            onError(cleanForwardError(e.message))
        }
    }

    /** Prunes finished connections. A failed one invalidates a SERVICE resolution (D5). */
    fun sweep(): Sweep {
        var clean = 0
        var error: String? = null
        for (h in handles) {
            if (h.isAlive) continue
            handles.remove(h)
            val msg = h.errorMessage
            if (msg == null) {
                clean++
            } else {
                if (error == null) error = cleanForwardError(msg)
                if (target.kind == PortForwardKind.SERVICE) resolved = null
            }
        }
        return Sweep(clean, error)
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        handles.forEach { runCatching { it.close() } }
        handles.clear()
    }
}
