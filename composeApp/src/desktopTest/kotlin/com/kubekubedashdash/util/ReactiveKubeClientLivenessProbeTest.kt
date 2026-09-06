package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract of [ReactiveKubeClient.isReachable], the liveness probe:
 *  1. it is a real round trip on every tick (nothing fabric8 caches);
 *  2. it uses an endpoint every authenticated principal can reach, so a
 *     namespace-scoped credential whose RBAC forbids listing namespaces is
 *     NOT reported as a dead cluster;
 *  3. it still detects a cluster that actually went away.
 *
 * The probe cadence is shortened through the constructor seam. The mock
 * server is loopback-only; no real user state is touched.
 */
class ReactiveKubeClientLivenessProbeTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var scope: CoroutineScope

    /** Expectations first, CRUD fallback — the same shape ReactiveKubeClientDrainEvictTest uses. */
    private fun newMixedServer(): KubernetesMockServer {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        return KubernetesMockServer(
            Context(),
            MockWebServer(),
            responses,
            KubernetesMixedDispatcher(responses),
            false,
        ).also { it.init() }
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        server = newMixedServer()
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientLivenessProbeTest", manager = manager, servers = listOf(server))
    }

    /** Every request the server has recorded so far, as paths, without blocking. */
    private fun recordedPaths(): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val request = server.takeRequest(0, TimeUnit.MILLISECONDS) ?: break
            paths += request.path ?: ""
        }
        return paths
    }

    @Test
    fun `a forbidden namespaces list does not trip the probe, which only ever hits version`() = runBlocking {
        server.expect().get()
            .withPath("/api/v1/namespaces")
            .andReturn(
                403,
                StatusBuilder().withCode(403).withReason("Forbidden").withMessage("namespaces is forbidden").build(),
            )
            .always()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        val observedErrors = CopyOnWriteArrayList<String?>()
        val errorCollector = scope.launch { manager.connectionError.collect { observedErrors.add(it) } }

        val client = ReactiveKubeClient(scope, manager, probeIntervalMs = 200)
        // Ten-plus ticks: well past the ≥3-consecutive-failure threshold.
        delay(2_500)

        assertTrue(client.isReachable.value, "a reachable cluster with restricted RBAC must stay reachable")
        assertEquals(
            emptyList<String>(),
            observedErrors.filterNotNull(),
            "a 403 on the namespaces list must never set connectionError",
        )
        val paths = recordedPaths()
        assertTrue(paths.size >= 3, "the probe must be a real round trip every tick, saw $paths")
        assertEquals(
            emptySet<String>(),
            paths.filterNot { it == "/version" }.toSet(),
            "the probe must hit /version and nothing else",
        )
        errorCollector.cancel()
    }

    @Test
    fun `a cluster that goes away is reported within a few ticks`() = runBlocking {
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        val client = ReactiveKubeClient(scope, manager, probeIntervalMs = 200)
        delay(600)
        assertTrue(client.isReachable.value, "healthy before the outage")

        server.destroy()

        // isReachable flips on the first failed tick; connectionError only on
        // the third consecutive one. Each failed probe may spend up to its 4 s
        // timeout inside fabric8's own retry budget before it counts, so three
        // of those must fit in the deadline.
        withTimeout(35_000) { manager.connectionError.first { it != null } }
        assertFalse(client.isReachable.value, "a dead cluster must read as unreachable")
    }
}
