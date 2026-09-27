package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

private fun eventually(timeoutMs: Long = 5_000, check: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (check()) return
        Thread.sleep(20)
    }
    fail("condition not met within ${timeoutMs}ms")
}

/**
 * Real [Fabric8PortForwardResolver] and [Fabric8PortForwardConnector] against a
 * Mixed-dispatcher mock with a websocket expectation. Mirrors fabric8's own
 * `PodTest.testPortForward`.
 *
 * NEEDS-PATH-VALIDATION note (same caveat as `ReactiveKubeClientDrainEvictTest`):
 * the websocket path below was verified by reading
 * `PortForwarderWebsocket.forward(...)` / `PodOperationsImpl.portForward(...)` in
 * the fabric8 7.7.0 sources, not by running against a real API server.
 */
class Fabric8PortForwardIntegrationTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var connection: KubeConnectionManager
    private lateinit var scope: CoroutineScope
    private lateinit var manager: PortForwardManager

    private fun pfFrame(dataChannel: Boolean, text: String): String {
        val data = text.toByteArray(Charsets.UTF_8)
        val msg = ByteArray(data.size + 1)
        data.copyInto(msg, 1)
        msg[0] = if (dataChannel) 0 else 1
        return String(msg, Charsets.UTF_8)
    }

    private fun setUpServer(allowed: Boolean) {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()

        val review = SelfSubjectAccessReviewBuilder().withNewStatus().withAllowed(allowed).endStatus().build()
        server.expect().post()
            .withPath("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")
            .andReturn(201, review)
            .always()

        val seed = server.createClient()
        try {
            seed.pods().inNamespace("test").resource(
                PodBuilder()
                    .withNewMetadata().withName("web-1").withNamespace("test").endMetadata()
                    .withNewStatus().withPhase("Running").endStatus()
                    .build(),
            ).create()
        } finally {
            seed.close()
        }

        connection = KubeConnectionManager(loadConfig = { throw IllegalStateException("offline") })
        connection.connectWithClient(server.createClient(), "ctx-a").getOrThrow()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = PortForwardManager(scope, Fabric8PortForwardResolver, { Fabric8PortForwardConnector }, sweepIntervalMs = 50)
    }

    @AfterTest
    fun tearDown() {
        if (::manager.isInitialized) manager.stopAll()
        if (::scope.isInitialized) {
            shutdownCleanly(scope, label = "Fabric8PortForwardIntegrationTest", manager = connection, servers = listOf(server))
        }
    }

    private fun readToEndOfStream(port: Int, timeoutMs: Int = 10_000): String {
        SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, port)).use { ch ->
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
    }

    @Test
    fun `forwards a real websocket connection end to end`() {
        setUpServer(allowed = true)
        server.expect().get()
            .withPath("/api/v1/namespaces/test/pods/web-1/portforward?ports=8080")
            .andUpgradeToWebSocket().open()
            .waitFor(10).andEmit(pfFrame(true, "12"))
            .waitFor(10).andEmit(pfFrame(false, "12"))
            .waitFor(10).andEmit(pfFrame(true, "Hell"))
            .waitFor(10).andEmit(pfFrame(true, "o World"))
            .done().once()

        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 8080), null)

        assertEquals("Hello World", readToEndOfStream(result.entry.localPort))
    }

    @Test
    fun `RBAC denial fails the start`() {
        setUpServer(allowed = false)

        val ex = assertFailsWith<PortForwardStartException> {
            manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 8080), null)
        }

        assertEquals(rbacMessage("test"), ex.message)
    }

    @Test
    fun `local close ends the tunnel after the EOF grace (D13)`() {
        setUpServer(allowed = true)
        server.expect().get()
            .withPath("/api/v1/namespaces/test/pods/web-1/portforward?ports=8080")
            .andUpgradeToWebSocket().open()
            .waitFor(10).andEmit(pfFrame(true, "12"))
            .waitFor(10).andEmit(pfFrame(false, "12"))
            .waitFor(30_000).andEmit(pfFrame(true, "late"))
            .done().once()

        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 8080), null)
        val client = SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, result.entry.localPort))

        eventually { manager.forwards.value.first { it.id == result.entry.id }.connectionsServed == 1 }
        client.close()

        eventually(timeoutMs = 10_000) {
            manager.activeHandleCount(result.entry.id) == 0 &&
                manager.forwards.value.first { it.id == result.entry.id }.lastError == null
        }
    }
}
