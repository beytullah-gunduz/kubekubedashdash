package com.kubekubedashdash.services.portforward

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DemoPortForwardConnector] does not use its [KubernetesClient] argument, but a
 * client is still minted from a mock server rather than passed null or built
 * against a real kubeconfig — no test may read real user state.
 */
class DemoPortForwardConnectorTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        client = server.createClient()
    }

    @AfterTest
    fun tearDown() {
        runCatching { client.close() }
        runCatching { server.destroy() }
    }

    private fun readAll(ch: SocketChannel, timeoutMs: Int = 5_000): String {
        ch.socket().soTimeout = timeoutMs
        val out = StringBuilder()
        val buf = ByteBuffer.allocate(4096)
        while (true) {
            val n = try {
                ch.read(buf)
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                if (msg.startsWith("Connection reset") || msg.startsWith("An existing connection was forcibly closed")) break else throw e
            }
            if (n < 0) break
            buf.flip()
            out.append(Charsets.UTF_8.decode(buf))
            buf.clear()
        }
        return out.toString()
    }

    @Test
    fun `answers a GET request with the demo page`() {
        val listener = ServerSocketChannel.open()
        listener.bind(InetSocketAddress(LOOPBACK_HOST, 0))
        try {
            val port = (listener.localAddress as InetSocketAddress).port
            val clientSocket = SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, port))
            val serverSocket = listener.accept()

            DemoPortForwardConnector.open(client, "test", "web-1", 8080, serverSocket)
            clientSocket.write(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.UTF_8)))
            val response = readAll(clientSocket)
            runCatching { clientSocket.close() }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Demo pod test/web-1"))
            val body = response.substringAfter("\r\n\r\n")
            val contentLength = Regex("Content-Length: (\\d+)").find(response)!!.groupValues[1].toInt()
            assertEquals(body.toByteArray(Charsets.UTF_8).size, contentLength)
        } finally {
            runCatching { listener.close() }
        }
    }

    @Test
    fun `a client that sends nothing still receives the full response within 5s`() {
        val listener = ServerSocketChannel.open()
        listener.bind(InetSocketAddress(LOOPBACK_HOST, 0))
        try {
            val port = (listener.localAddress as InetSocketAddress).port
            val clientSocket = SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, port))
            val serverSocket = listener.accept()

            DemoPortForwardConnector.open(client, "test", "web-1", 8080, serverSocket)
            val response = readAll(clientSocket, timeoutMs = 5_000)
            runCatching { clientSocket.close() }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        } finally {
            runCatching { listener.close() }
        }
    }
}
