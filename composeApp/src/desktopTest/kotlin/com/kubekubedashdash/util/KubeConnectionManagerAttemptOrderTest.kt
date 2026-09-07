package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A connect attempt carries its caller's attempt number, and the manager
 * refuses one that is no longer the newest BEFORE it builds or publishes
 * anything. Item 5 made the session reducer drop a superseded attempt's
 * outcome, but the manager still let it win: an older attempt descheduled
 * between its coroutine start and `connectLock` could enter after the newer
 * one had published, publish its own client, and retire the newer one —
 * the UI then showed the newer context while the informers restarted
 * against the older cluster. Both connect seams are injected; only loopback
 * mock servers are reached.
 */
class KubeConnectionManagerAttemptOrderTest {

    private var manager: KubeConnectionManager? = null
    private val servers = mutableListOf<KubernetesMockServer>()
    private val built = CopyOnWriteArrayList<KubernetesClient>()
    private lateinit var configs: Map<String, Config>

    private val recordingBuilder: (Config) -> KubernetesClient = { config ->
        KubernetesClientBuilder().withConfig(config).build().also { built += it }
    }

    private fun newServer(podName: String): KubernetesMockServer {
        val s = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        s.init()
        s.createClient().use { seed ->
            seed.pods().inNamespace("default").resource(
                PodBuilder().withNewMetadata().withName(podName).withNamespace("default").endMetadata().build(),
            ).create()
        }
        servers += s
        return s
    }

    @BeforeTest
    fun setUp() {
        configs = mapOf(
            "cluster-a" to newServer("pod-a").createClient().use { it.configuration },
            "cluster-b" to newServer("pod-b").createClient().use { it.configuration },
        )
        manager = KubeConnectionManager(
            loadConfig = { context -> configs.getValue(requireNotNull(context)) },
            buildClient = recordingBuilder,
        )
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "KubeConnectionManagerAttemptOrderTest", manager = manager, servers = servers)
        built.forEach { runCatching { it.close() } }
    }

    private val mgr: KubeConnectionManager get() = requireNotNull(manager)

    @Test
    fun `an older attempt that reaches the lock after a newer one is refused before building a client`() {
        assertTrue(mgr.connect("cluster-b", attempt = 2).isSuccess)
        assertEquals(1L, mgr.connectionVersion.value)
        assertEquals(1, built.size)

        val stale = mgr.connect("cluster-a", attempt = 1)

        assertTrue(stale.isFailure, "the older attempt must not publish")
        assertIs<ConnectSupersededException>(stale.exceptionOrNull())
        assertEquals(1, built.size, "a refused attempt builds nothing")
        assertEquals("cluster-b", mgr.getCurrentContext(), "the newer cluster stays connected")
        assertTrue(mgr.isConnected)
        assertEquals(1L, mgr.connectionVersion.value, "no teardown, no restart of the informers")
        assertFalse(built.single().httpClient.isClosed, "the newer client is not retired")
        assertEquals(2L, mgr.newestAttempt)
    }

    @Test
    fun `attempts arriving in order publish in order`() {
        assertTrue(mgr.connect("cluster-a", attempt = 1).isSuccess)
        assertTrue(mgr.connect("cluster-b", attempt = 2).isSuccess)

        assertEquals("cluster-b", mgr.getCurrentContext())
        assertEquals(2L, mgr.connectionVersion.value)
        assertEquals(2, built.size)
        assertEquals(2L, mgr.newestAttempt)
    }

    @Test
    fun `an unsequenced connect is never refused`() {
        assertTrue(mgr.connect("cluster-b", attempt = 2).isSuccess)

        assertTrue(mgr.connect("cluster-a").isSuccess, "callers that do not sequence their attempts keep working")

        assertEquals("cluster-a", mgr.getCurrentContext())
        assertEquals(2L, mgr.connectionVersion.value)
        assertEquals(2L, mgr.newestAttempt, "an unsequenced connect does not move the watermark")
    }

    @Test
    fun `a refused pre-built connect releases the client it was handed`() {
        assertTrue(mgr.connect("cluster-b", attempt = 2).isSuccess)
        val handed = servers.first().createClient()

        val stale = mgr.connectWithClient(handed, "cluster-a", attempt = 1)

        assertTrue(stale.isFailure)
        assertIs<ConnectSupersededException>(stale.exceptionOrNull())
        assertTrue(handed.httpClient.isClosed, "ownership was handed over; a refused connect can only release it")
        assertEquals("cluster-b", mgr.getCurrentContext())
        assertEquals(1L, mgr.connectionVersion.value)
    }

    @Test
    fun `a refused attempt does not clear or count as a connection error`() {
        assertTrue(mgr.connect("cluster-b", attempt = 2).isSuccess)
        repeat(3) { mgr.reportError("probe failed") }
        assertEquals("probe failed", mgr.connectionError.value)

        mgr.connect("cluster-a", attempt = 1)

        assertEquals("probe failed", mgr.connectionError.value, "a refused attempt touches no connection state at all")
    }
}
