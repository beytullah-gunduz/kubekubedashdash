package com.kubekubedashdash.services.portforward

import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * Fakes plus a CRUD mock server (only used to mint real [KubernetesClient]s —
 * neither fake talks to it).
 */
class PortForwardManagerTest {

    private class FakeResolver(var access: Boolean? = true, var failWith: String? = null) : PortForwardResolver {
        val resolveCalls = AtomicInteger(0)

        override fun resolve(client: KubernetesClient, target: PortForwardTarget): ResolvedTarget {
            resolveCalls.incrementAndGet()
            failWith?.let { throw PortForwardStartException(it) }
            return when (target.kind) {
                PortForwardKind.POD -> ResolvedTarget("web-1", target.remotePort)
                PortForwardKind.SERVICE -> ResolvedTarget("web-1", 8080)
            }
        }

        override fun canPortForward(client: KubernetesClient, namespace: String): Boolean? = access
    }

    private class GreetingConnector(private val handleErrorMessage: String? = null) : PortForwardConnector {
        val calls = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun open(client: KubernetesClient, namespace: String, podName: String, podPort: Int, socket: SocketChannel): ForwardedConnection {
            calls += podName to podPort
            runCatching {
                socket.configureBlocking(true)
                val buf = ByteBuffer.wrap("hello $podName:$podPort".toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) socket.write(buf)
                socket.close()
            }
            return object : ForwardedConnection {
                override val isAlive: Boolean = false
                override val errorMessage: String? = handleErrorMessage
                override fun close() = Unit
            }
        }
    }

    private lateinit var server: KubernetesMockServer
    private lateinit var connection: KubeConnectionManager
    private lateinit var scope: CoroutineScope
    private lateinit var manager: PortForwardManager

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        connection = KubeConnectionManager(loadConfig = { throw IllegalStateException("offline") })
        connection.connectWithClient(server.createClient(), "ctx-a").getOrThrow()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        if (::manager.isInitialized) manager.stopAll()
        shutdownCleanly(scope, label = "PortForwardManagerTest", manager = connection, servers = listOf(server))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Reads until the peer ends the stream, tolerant of a Windows-style reset. */
    private fun readAll(port: Int, timeoutMs: Int = 5_000): String {
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

    /** True once nothing is listening on [port] any more (the server socket was closed). */
    private fun isPortClosed(port: Int): Boolean = try {
        SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, port)).close()
        false
    } catch (e: IOException) {
        true
    }

    /** True when a connection to [port] succeeds at the TCP level but the peer ends the stream at once. */
    private fun readsEndOfStreamImmediately(port: Int): Boolean = try {
        SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, port)).use { ch ->
            ch.socket().soTimeout = 2_000
            val buf = ByteBuffer.allocate(16)
            try {
                ch.read(buf) < 0
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                msg.startsWith("Connection reset") || msg.startsWith("An existing connection was forcibly closed")
            }
        }
    } catch (e: IOException) {
        // A closed server socket also satisfies "the local connection did not work".
        true
    }

    /** Binds an ephemeral loopback port, then frees it immediately — a port very likely still free. */
    private fun freeLoopbackPort(): Int {
        val ch = ServerSocketChannel.open()
        ch.bind(InetSocketAddress(LOOPBACK_HOST, 0))
        val port = (ch.localAddress as InetSocketAddress).port
        ch.close()
        return port
    }

    // ── 1. Start binds loopback ─────────────────────────────────────────────

    @Test
    fun `start binds loopback and tunnels a connection`() {
        val resolver = FakeResolver()
        val connector = GreetingConnector()
        manager = PortForwardManager(scope, resolver, { connector }, sweepIntervalMs = 50)

        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)

        assertFalse(result.alreadyRunning)
        assertTrue(result.entry.localPort > 0)
        assertEquals(PortForwardStatus.Active, result.entry.status)
        assertEquals("hello web-1:80", readAll(result.entry.localPort))
        eventually { manager.forwards.value.first { it.id == result.entry.id }.connectionsServed == 1 }
    }

    // ── 2. Requested port honoured ───────────────────────────────────────────

    @Test
    fun `a requested local port is honoured`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val port = freeLoopbackPort()

        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), port)

        assertEquals(port, result.entry.localPort)
    }

    // ── 3. Port in use ───────────────────────────────────────────────────────

    @Test
    fun `starting on a port already in use fails with a readable message`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val held = ServerSocketChannel.open()
        held.bind(InetSocketAddress(LOOPBACK_HOST, 0))
        val port = (held.localAddress as InetSocketAddress).port
        try {
            val ex = assertFailsWith<PortForwardStartException> {
                manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), port)
            }
            assertTrue(ex.message.orEmpty().contains(port.toString()))
            assertTrue(manager.forwards.value.isEmpty())
        } finally {
            held.close()
        }
    }

    // ── 4. Duplicate ─────────────────────────────────────────────────────────

    @Test
    fun `an identical start is deduplicated`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val target = PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80)

        val first = manager.start("s1", connection, target, null)
        val second = manager.start("s1", connection, target, null)

        assertFalse(first.alreadyRunning)
        assertTrue(second.alreadyRunning)
        assertEquals(first.entry.id, second.entry.id)
        assertEquals(1, manager.forwards.value.size)
    }

    // ── 5. Stop ──────────────────────────────────────────────────────────────

    @Test
    fun `stop empties the forward list and closes the port`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)

        manager.stop(result.entry.id)

        assertTrue(manager.forwards.value.isEmpty())
        eventually { isPortClosed(result.entry.localPort) }
    }

    // ── 6. RBAC denied ───────────────────────────────────────────────────────

    @Test
    fun `RBAC denial fails the start before anything is bound`() {
        manager = PortForwardManager(scope, FakeResolver(access = false), { GreetingConnector() }, sweepIntervalMs = 50)

        val ex = assertFailsWith<PortForwardStartException> {
            manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)
        }

        assertEquals(rbacMessage("test"), ex.message)
        assertTrue(manager.forwards.value.isEmpty())
    }

    // ── 7. Resolver failure at start ─────────────────────────────────────────

    @Test
    fun `a resolver failure surfaces its message verbatim`() {
        val message = "Pod test/web-1 was not found."
        manager = PortForwardManager(scope, FakeResolver(failWith = message), { GreetingConnector() }, sweepIntervalMs = 50)

        val ex = assertFailsWith<PortForwardStartException> {
            manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)
        }

        assertEquals(message, ex.message)
    }

    // ── 8. Context switch ────────────────────────────────────────────────────

    @Test
    fun `switching context in place stops the forward`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)

        connection.connectWithClient(server.createClient(), "ctx-b").getOrThrow()

        eventually { manager.forwards.value.first { it.id == result.entry.id }.status == PortForwardStatus.Stopped(SWITCHED_REASON) }
        eventually { isPortClosed(result.entry.localPort) }
    }

    // ── 9. Disconnect and reconnect ──────────────────────────────────────────

    @Test
    fun `a disconnect refuses new connections and a reconnect to the same context resumes`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)
        val id = result.entry.id

        assertTrue(connection.connect("ctx-a").isFailure)

        eventually { manager.forwards.value.first { it.id == id }.status == PortForwardStatus.Disconnected }
        assertTrue(readsEndOfStreamImmediately(result.entry.localPort))
        eventually { manager.forwards.value.first { it.id == id }.lastError == DISCONNECTED_MESSAGE }

        connection.connectWithClient(server.createClient(), "ctx-a").getOrThrow()

        eventually { manager.forwards.value.first { it.id == id }.status == PortForwardStatus.Active }
        eventually { manager.forwards.value.first { it.id == id }.lastError == null }
        assertEquals("hello web-1:80", readAll(result.entry.localPort))
    }

    // ── 10. Service re-resolve on failure ────────────────────────────────────

    @Test
    fun `a failed service connection invalidates the cached resolution`() {
        val resolver = FakeResolver()
        val connector = GreetingConnector(handleErrorMessage = "Received an error from the remote socket boom")
        manager = PortForwardManager(scope, resolver, { connector }, sweepIntervalMs = 50)

        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.SERVICE, "test", "web-svc", 80), null)
        assertEquals(1, resolver.resolveCalls.get())

        readAll(result.entry.localPort)
        eventually { manager.forwards.value.first { it.id == result.entry.id }.lastError == "boom" }

        readAll(result.entry.localPort)
        eventually { resolver.resolveCalls.get() == 2 }
    }

    // ── 11. stopAllForSession ─────────────────────────────────────────────────

    @Test
    fun `stopAllForSession stops only that session's forwards`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val s1 = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)
        val s2 = manager.start("s2", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-2", 80), null)

        manager.stopAllForSession("s1")

        assertTrue(manager.forwards.value.none { it.id == s1.entry.id })
        assertTrue(manager.forwards.value.first { it.id == s2.entry.id }.isRunning)
    }

    // ── 12. Demo context skips RBAC ───────────────────────────────────────────

    @Test
    fun `a demo context skips the RBAC check`() {
        val resolver = FakeResolver(access = false)
        val connector = GreetingConnector()
        manager = PortForwardManager(scope, resolver, { connector }, sweepIntervalMs = 50)
        connection.connectWithClient(server.createClient(), "demo-cluster (mock) #1").getOrThrow()

        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)

        assertFalse(result.alreadyRunning)
    }

    // ── 13. Stop while a connection is being opened ──────────────────────────

    @Test
    fun `stopping while a connection is mid-open closes it exactly once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val connector = PortForwardConnector { _, _, _, _, socket ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            object : ForwardedConnection {
                override val isAlive: Boolean = true
                override val errorMessage: String? = null
                override fun close() {
                    closeCount.incrementAndGet()
                    runCatching { socket.close() }
                }
            }
        }
        manager = PortForwardManager(scope, FakeResolver(), { connector }, sweepIntervalMs = 50)
        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)

        val clientSocket = SocketChannel.open(InetSocketAddress(LOOPBACK_HOST, result.entry.localPort))
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        manager.stop(result.entry.id)
        release.countDown()

        eventually { closeCount.get() == 1 }
        runCatching { clientSocket.close() }
    }

    // ── 14. Closed manager (D15) ───────────────────────────────────────────────

    @Test
    fun `a closed connection manager stops its forwards`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)
        val result = manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), null)

        connection.close()

        eventually { manager.forwards.value.first { it.id == result.entry.id }.status == PortForwardStatus.Stopped(CLOSED_REASON) }
        eventually { isPortClosed(result.entry.localPort) }
    }

    // ── 15. Out-of-range port ─────────────────────────────────────────────────

    @Test
    fun `an out-of-range remote or local port is rejected`() {
        manager = PortForwardManager(scope, FakeResolver(), { GreetingConnector() }, sweepIntervalMs = 50)

        val badRemote = assertFailsWith<PortForwardStartException> {
            manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 0), null)
        }
        assertEquals("Enter a port between 1 and 65535", badRemote.message)

        val badLocal = assertFailsWith<PortForwardStartException> {
            manager.start("s1", connection, PortForwardTarget(PortForwardKind.POD, "test", "web-1", 80), 70_000)
        }
        assertEquals("Enter a port between 1 and 65535", badLocal.message)
        assertNull(manager.forwards.value.firstOrNull())
    }
}
