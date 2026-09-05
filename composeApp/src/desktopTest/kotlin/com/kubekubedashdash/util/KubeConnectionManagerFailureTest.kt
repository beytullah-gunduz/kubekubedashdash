package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Failure-path contract of [KubeConnectionManager]:
 *  - a client built for an attempt that fails is closed, never leaked (H1);
 *  - a failed connect that replaces a live connection bumps `connectionVersion`
 *    so that connection's flows park instead of erroring (M4);
 *  - `close()` and `connect*` serialise on one lock, and nothing handed to or
 *    built by a closed manager leaks (M3).
 * Both connect seams are injected: no test here reads a kubeconfig or any
 * other real user state.
 */
class KubeConnectionManagerFailureTest {

    private lateinit var scope: CoroutineScope
    private var manager: KubeConnectionManager? = null
    private val servers = mutableListOf<KubernetesMockServer>()
    private val built = CopyOnWriteArrayList<KubernetesClient>()

    /**
     * A Config for a loopback port nothing listens on. `requestRetryBackoffLimit = 0`
     * is load-bearing, not decoration: fabric8 retries the probe with exponential
     * backoff otherwise (measured 228 ms with it, 19.2 s without).
     */
    private fun deadConfig(): Config = Config.empty().apply {
        masterUrl = "http://127.0.0.1:1"
        requestRetryBackoffLimit = 0
        requestTimeout = 2_000
    }

    /** A real fabric8 client for [config], recorded so a test can check it was closed. */
    private val recordingBuilder: (Config) -> KubernetesClient = { config ->
        KubernetesClientBuilder().withConfig(config).build().also { built += it }
    }

    private fun newServer(podName: String): KubernetesMockServer {
        val s = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
        s.init()
        val seed = s.createClient()
        try {
            seed.pods().inNamespace("default").resource(
                PodBuilder().withNewMetadata().withName(podName).withNamespace("default").endMetadata().build(),
            ).create()
        } finally {
            seed.close()
        }
        servers += s
        return s
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "KubeConnectionManagerFailureTest", manager = manager, servers = servers)
        built.forEach { runCatching { it.close() } }
    }

    @Test
    fun `a failed connect closes the client it built and leaves the manager disconnected`() {
        val mgr = KubeConnectionManager(loadConfig = { deadConfig() }, buildClient = recordingBuilder)
        manager = mgr

        val result = mgr.connect("example-context")

        assertTrue(result.isFailure, "connect to a dead address must fail")
        assertEquals(1, built.size, "exactly one client is built per attempt")
        assertTrue(built.single().httpClient.isClosed, "the client of a failed connect must be closed")
        assertFalse(mgr.isConnected)
        assertEquals("", mgr.getClusterServer())
        assertEquals(0L, mgr.connectionVersion.value, "no previous connection: nothing to tear down, no bump")
    }

    @Test
    fun `a failed connect tears the previous connection down without a connection error`() = runBlocking {
        val serverA = newServer("pod-a")
        val clientA = serverA.createClient()
        val mgr = KubeConnectionManager(loadConfig = { deadConfig() }, buildClient = recordingBuilder)
        manager = mgr
        mgr.connectWithClient(clientA, "cluster-a").getOrThrow()
        val client = ReactiveKubeClient(scope, mgr)

        val observedErrors = CopyOnWriteArrayList<String?>()
        val podStates = CopyOnWriteArrayList<ResourceState<*>>()
        val errorCollector = scope.launch { mgr.connectionError.collect { observedErrors.add(it) } }
        // Several gated flows, so the failure-path version bump restarts several
        // inner flows at once — the shape that tripped the shared failure
        // counter when a restart could still reach the "Not connected" getter.
        val collectors = listOf(
            scope.launch { client.pods.collect { podStates.add(it) } },
            scope.launch { client.deployments.collect {} },
            scope.launch { client.services.collect {} },
            scope.launch { client.replicaSets.collect {} },
            scope.launch { client.statefulSets.collect {} },
        )
        withTimeout(15_000) {
            client.pods.first { s -> s is ResourceState.Success && s.data.any { it.name == "pod-a" } }
        }
        delay(500)
        val versionBefore = mgr.connectionVersion.value

        val result = mgr.connect("example-context")
        assertTrue(result.isFailure, "connect to a dead address must fail")

        // The previous client is retired on a daemon thread after RETIRE_GRACE_MS
        // (1 s). Poll for it rather than sleeping a fixed margin, so a loaded
        // full-suite run cannot flake this; then give the restarted flows a
        // moment to park before checking that nothing errored.
        withTimeout(10_000) {
            while (!clientA.httpClient.isClosed) delay(50)
        }
        delay(500)
        assertEquals(versionBefore + 1, mgr.connectionVersion.value, "a failed switch off a live connection bumps the version")
        assertFalse(mgr.isConnected)
        assertEquals("", mgr.getClusterServer())
        assertTrue(clientA.httpClient.isClosed, "the previous client is retired after the grace period")
        assertTrue(built.single().httpClient.isClosed, "the failed attempt's client is closed")
        assertEquals(
            emptyList<String>(),
            observedErrors.filterNotNull(),
            "tearing down the previous connection must never set connectionError",
        )
        assertEquals(
            emptyList<ResourceState.Error>(),
            podStates.filterIsInstance<ResourceState.Error>(),
            "no gated flow may surface ResourceState.Error on a failed switch",
        )
        val last = client.pods.value
        assertTrue(
            last is ResourceState.Success && last.data.any { it.name == "pod-a" },
            "parked flows keep their last value, got $last",
        )

        collectors.forEach { it.cancel() }
        errorCollector.cancel()
    }

    @Test
    fun `connect after close builds nothing, and a client handed to a closed manager is closed`() {
        val mgr = KubeConnectionManager(loadConfig = { deadConfig() }, buildClient = recordingBuilder)
        manager = mgr
        mgr.close()

        assertTrue(mgr.connect("example-context").isFailure, "a closed manager refuses to connect")
        assertTrue(built.isEmpty(), "a closed manager must not build anything")

        val clientA = newServer("pod-a").createClient()
        assertTrue(mgr.connectWithClient(clientA, "cluster-a").isFailure, "a closed manager refuses a pre-built client")
        assertTrue(clientA.httpClient.isClosed, "a client handed to a closed manager is closed, not leaked")
        assertFalse(mgr.isConnected)
        assertEquals(0L, mgr.connectionVersion.value)
    }

    @Test
    fun `close during an in-flight connect closes the client that connect publishes`() {
        val serverA = newServer("pod-a")
        val liveConfig: Config = serverA.createClient().use { it.configuration }
        // The CRUD dispatcher answers GET /version with its generic empty-list body, which
        // maps onto an all-null (but non-null) VersionInfo — so the probe passes and connect()
        // succeeds with "null.null". This test is about the lock ordering, not the version.
        val gate = CountDownLatch(1)
        val builtSignal = CountDownLatch(1)
        val mgr = KubeConnectionManager(
            loadConfig = { liveConfig },
            buildClient = { config ->
                KubernetesClientBuilder().withConfig(config).build().also {
                    built += it
                    builtSignal.countDown()
                    // Hold connectLock (we are inside connect()) until the test says go.
                    gate.await(10, TimeUnit.SECONDS)
                }
            },
        )
        manager = mgr

        val connectResult = AtomicReference<Result<String>?>(null)
        val connector = Thread { connectResult.set(mgr.connect("example-context")) }.apply { start() }
        assertTrue(builtSignal.await(10, TimeUnit.SECONDS), "connect must have built its client")

        val closer = Thread { mgr.close() }.apply { start() }
        closer.join(300)
        assertTrue(closer.isAlive, "close() must wait for the in-flight connect, not race it")

        gate.countDown()
        connector.join(15_000)
        closer.join(15_000)
        assertFalse(connector.isAlive, "connect must complete")
        assertFalse(closer.isAlive, "close must complete once connect released the lock")

        assertTrue(connectResult.get()?.isSuccess == true, "the in-flight connect completes normally: ${connectResult.get()}")
        assertTrue(built.single().httpClient.isClosed, "close() ran after publish and closed the published client")
        assertFalse(mgr.isConnected)
        assertEquals(1L, mgr.connectionVersion.value, "the in-flight connect did publish once")
    }
}
