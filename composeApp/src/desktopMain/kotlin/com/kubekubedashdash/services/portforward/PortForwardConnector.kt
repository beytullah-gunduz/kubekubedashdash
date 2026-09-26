package com.kubekubedashdash.services.portforward

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.PortForward
import io.fabric8.kubernetes.client.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One tunnelled local connection. */
interface ForwardedConnection {
    val isAlive: Boolean

    /** The first error seen on this connection (raw text), or null. */
    val errorMessage: String?
    fun close()
}

/** Starts tunnelling [socket] to [podName]:[podPort]. Returns promptly; owns [socket] from here on. */
fun interface PortForwardConnector {
    fun open(client: KubernetesClient, namespace: String, podName: String, podPort: Int, socket: SocketChannel): ForwardedConnection
}

internal const val EOF_CLOSE_GRACE_MS = 2_000L

object Fabric8PortForwardConnector : PortForwardConnector {
    override fun open(client: KubernetesClient, namespace: String, podName: String, podPort: Int, socket: SocketChannel): ForwardedConnection {
        val pfRef = AtomicReference<PortForward?>()
        val eofSeen = AtomicBoolean(false)
        // D13: the listener's pipe() just returns on local EOF and never closes the websocket.
        // Close it ourselves after a grace period so a half-closing client still gets its answer.
        // The delay also covers the window before pfRef is set (portForward() returns at once).
        val eofAwareIn = object : ReadableByteChannel {
            override fun read(dst: ByteBuffer): Int = socket.read(dst).also { n ->
                if (n < 0 && eofSeen.compareAndSet(false, true)) {
                    CompletableFuture.delayedExecutor(EOF_CLOSE_GRACE_MS, TimeUnit.MILLISECONDS).execute {
                        pfRef.get()?.let { runCatching { it.close() } }
                    }
                }
            }

            override fun isOpen(): Boolean = socket.isOpen

            override fun close() = socket.close()
        }
        val pf = client.pods().inNamespace(namespace).withName(podName).portForward(podPort, eofAwareIn, socket)
        pfRef.set(pf)
        return object : ForwardedConnection {
            override val isAlive: Boolean get() = pf.isAlive
            override val errorMessage: String?
                get() = (pf.serverThrowables + pf.clientThrowables).firstOrNull()?.let(::describeForwardFailure)

            override fun close() {
                runCatching { pf.close() }
                runCatching { socket.close() }
            }
        }
    }
}

/** D14: a rejected upgrade has no message — only an HTTP code. */
internal fun describeForwardFailure(t: Throwable): String {
    val root = if (t is CompletionException && t.cause != null) t.cause!! else t
    return when (root) {
        is WebSocketHandshakeException -> when (val code = root.response.code()) {
            404 -> "The pod no longer exists (HTTP 404)"
            403 -> "Permission denied by the API server (HTTP 403 — needs create on pods/portforward)"
            else -> "API server refused the port-forward (HTTP $code)"
        }

        else -> root.message ?: root::class.simpleName ?: "Connection failed"
    }
}

/** Starts tunnelling to the demo simulator. Answers every connection with a fixed HTML page. */
object DemoPortForwardConnector : PortForwardConnector {
    private const val READ_BUFFER_BYTES = 8192
    private const val POLL_SLEEP_MS = 20L
    private const val READ_TIMEOUT_MS = 2_000L

    override fun open(client: KubernetesClient, namespace: String, podName: String, podPort: Int, socket: SocketChannel): ForwardedConnection {
        val thread = Thread({ serve(namespace, podName, podPort, socket) }, "port-forward-demo-$podName").apply { isDaemon = true }
        thread.start()
        return object : ForwardedConnection {
            override val isAlive: Boolean get() = thread.isAlive
            override val errorMessage: String? get() = null
            override fun close() {
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(namespace: String, podName: String, podPort: Int, socket: SocketChannel) {
        runCatching {
            socket.configureBlocking(false)
            val buf = ByteBuffer.allocate(READ_BUFFER_BYTES)
            val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000
            while (System.nanoTime() < deadline && buf.remaining() > 0) {
                val n = socket.read(buf)
                if (n < 0) break
                if (n == 0) {
                    Thread.sleep(POLL_SLEEP_MS)
                    continue
                }
                val readSoFar = String(buf.array(), 0, buf.position(), Charsets.UTF_8)
                if (readSoFar.contains("\r\n\r\n")) break
            }
        }
        runCatching {
            socket.configureBlocking(true)
            val body =
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>KubeKubeDashDash demo</title></head>" +
                    "<body style=\"font-family:sans-serif;margin:3em\"><h1>Demo pod $namespace/$podName</h1>" +
                    "<p>This page came through a simulated port forward to port $podPort. The demo cluster has no real containers, " +
                    "so every forwarded connection gets this page.</p></body></html>"
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val headers =
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            val response = ByteBuffer.wrap(headers.toByteArray(Charsets.UTF_8) + bodyBytes)
            while (response.hasRemaining()) socket.write(response)
        }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
