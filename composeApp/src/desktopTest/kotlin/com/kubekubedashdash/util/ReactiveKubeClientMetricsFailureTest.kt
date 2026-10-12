package com.kubekubedashdash.util

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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
 * A metrics read that fails for a moment keeps the last sample. `nodeUsages`
 * turned any failed `top nodes` into an empty map, so the Nodes CPU/Memory
 * columns, the "Pressure only" filter and the health banner's node-pressure
 * count dropped out for a whole poll; `resourceUsage` flipped the KPI strip to
 * "Metrics server unavailable" the same way. A failure that outlasts a blip
 * ([MAX_SKIPPED_POLLS] polls) and a metrics API that is not there (404) still
 * read as unavailable, and a failed metrics read is never a connection
 * failure. Loopback mock only: the CRUD store serves the samples, canned
 * answers stand in for the failures.
 */
class ReactiveKubeClientMetricsFailureTest {

    private companion object {
        const val GIB = 1L shl 30
        const val NODE_METRICS_PATH = "/apis/metrics.k8s.io/v1beta1/nodes"
        const val POD_METRICS_PATH = "/apis/metrics.k8s.io/v1beta1/namespaces/ns-a/pods"
        const val NODE_LIST_PATH = "/api/v1/nodes"
    }

    private val goodNodeUsages = mapOf("node-a" to NodeResourceUsage("node-a", 1_000, 4_000, GIB, 4 * GIB))

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private val observedErrors = CopyOnWriteArrayList<String?>()
    private val collectors = mutableListOf<Job>()

    @BeforeTest
    fun setUp() {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()

        server.createClient().use { seed ->
            seed.nodes().resource(
                NodeBuilder()
                    .withNewMetadata().withName("node-a").endMetadata()
                    .withNewStatus()
                    .addToAllocatable("cpu", Quantity("4"))
                    .addToAllocatable("memory", Quantity("4Gi"))
                    .endStatus()
                    .build(),
            ).create()
            seed.resources(NodeMetrics::class.java).resource(
                NodeMetricsBuilder()
                    .withNewMetadata().withName("node-a").endMetadata()
                    .addToUsage("cpu", Quantity("1"))
                    .addToUsage("memory", Quantity("1Gi"))
                    .build(),
            ).create()
            seed.resources(PodMetrics::class.java).inNamespace("ns-a").resource(
                PodMetricsBuilder()
                    .withNewMetadata().withName("web-0").withNamespace("ns-a").endMetadata()
                    .addToContainers(
                        ContainerMetricsBuilder()
                            .withName("app")
                            .addToUsage("cpu", Quantity("100m"))
                            .addToUsage("memory", Quantity("1Gi"))
                            .build(),
                    )
                    .build(),
            ).create()
        }

        // No request retries: fabric8 retries a 5xx for ~100 s by default.
        val config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(KubernetesClientBuilder().withConfig(config).build(), "cluster-a").getOrThrow()
        // One liveness probe at connect, then none: its success would reset the
        // failure counter that the connection-failure test watches.
        // The usage sampler is off: it reads the same metrics paths, and these tests count the polls' requests.
        client = ReactiveKubeClient(scope, manager, probeIntervalMs = 3_600_000, metricsPollIntervalMs = 100, usageSampleIntervalMs = 0)
        client.setNamespaceScope(NamespaceScope.single("ns-a"))
        collectors += scope.launch { manager.connectionError.collect { observedErrors += it } }
    }

    @AfterTest
    fun tearDown() {
        collectors.forEach { it.cancel() }
        shutdownCleanly(scope, label = "ReactiveKubeClientMetricsFailureTest", manager = manager, servers = listOf(server))
    }

    private fun answer(path: String, code: Int, reason: String) {
        server.expect().get().withPath(path)
            .andReturn(code, StatusBuilder().withCode(code).withReason(reason).withMessage("synthetic $reason").build())
            .always()
    }

    private fun drainRequests() {
        while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) Unit
    }

    /**
     * Waits for [count] requests to [path], up to five seconds. Polls run one
     * at a time, so by the time the last one is seen every earlier answer has
     * been handled.
     */
    private fun awaitRequests(path: String, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var seen = 0
        while (seen < count) {
            assertTrue(System.nanoTime() < deadline, "only $seen of $count requests to $path")
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (request.path == path) seen++
        }
    }

    /** Records every value [flow] shows, and keeps it subscribed. */
    private fun <T> record(flow: StateFlow<T>): List<T> {
        val seen = CopyOnWriteArrayList<T>()
        collectors += scope.launch { flow.collect { seen += it } }
        return seen
    }

    private suspend fun awaitNodeUsages(expected: Map<String, NodeResourceUsage>) {
        withTimeout(5_000) { client.nodeUsages.first { it == expected } }
    }

    private suspend fun awaitUsage(predicate: (ResourceUsageSummary) -> Boolean): ResourceUsageSummary = withTimeout(5_000) {
        (client.resourceUsage.first { it is ResourceState.Success && predicate(it.data) } as ResourceState.Success).data
    }

    @Test
    fun `a failed top nodes keeps the last node usages`() = runBlocking {
        val seen = record(client.nodeUsages)
        awaitNodeUsages(goodNodeUsages)

        drainRequests()
        answer(NODE_METRICS_PATH, 500, "InternalError")
        // One poll may have been answered before the failure was canned; the
        // rest fail, never more of them than a blip is allowed.
        awaitRequests(NODE_METRICS_PATH, MAX_SKIPPED_POLLS)

        assertEquals(goodNodeUsages, client.nodeUsages.value)
        assertTrue(seen.dropWhile { it.isEmpty() }.none { it.isEmpty() }, "node usages never blank once loaded: $seen")
    }

    @Test
    fun `a failed node list keeps the last node capacities`() = runBlocking {
        val seen = record(client.nodeUsages)
        awaitNodeUsages(goodNodeUsages)

        drainRequests()
        answer(NODE_LIST_PATH, 500, "InternalError")
        awaitRequests(NODE_LIST_PATH, MAX_SKIPPED_POLLS)

        assertEquals(goodNodeUsages, client.nodeUsages.value)
        assertTrue(seen.dropWhile { it.isEmpty() }.all { it == goodNodeUsages }, "capacities never drop to zero: $seen")
    }

    @Test
    fun `node usages that keep failing past a blip empty out`() = runBlocking {
        record(client.nodeUsages)
        awaitNodeUsages(goodNodeUsages)

        answer(NODE_METRICS_PATH, 500, "InternalError")

        awaitNodeUsages(emptyMap())
    }

    @Test
    fun `a usage summary that keeps failing past a blip reads as unavailable`() = runBlocking {
        record(client.resourceUsage)
        awaitUsage { it.metricsAvailable }

        answer(POD_METRICS_PATH, 500, "InternalError")

        awaitUsage { !it.metricsAvailable }
        Unit
    }

    @Test
    fun `a missing metrics API empties node usages`() = runBlocking {
        record(client.nodeUsages)
        awaitNodeUsages(goodNodeUsages)

        answer(NODE_METRICS_PATH, 404, "NotFound")

        awaitNodeUsages(emptyMap())
    }

    @Test
    fun `a failed top pods keeps the last usage summary`() = runBlocking {
        val seen = record(client.resourceUsage)
        val good = awaitUsage { it.metricsAvailable }
        assertEquals(100L, good.cpuUsedMillis)

        drainRequests()
        answer(POD_METRICS_PATH, 500, "InternalError")
        awaitRequests(POD_METRICS_PATH, MAX_SKIPPED_POLLS)

        assertEquals(ResourceState.Success(good), client.resourceUsage.value)
        val afterLoad = seen.dropWhile { it !is ResourceState.Success }
        assertTrue(afterLoad.all { it == ResourceState.Success(good) }, "the summary never flips to unavailable: $afterLoad")
    }

    @Test
    fun `a missing metrics API reads as metrics unavailable`() = runBlocking {
        record(client.resourceUsage)
        awaitUsage { it.metricsAvailable }

        answer(POD_METRICS_PATH, 404, "NotFound")

        awaitUsage { !it.metricsAvailable }
        Unit
    }

    @Test
    fun `a top pods that fails from the start reads as unavailable, not loading`() = runBlocking {
        answer(POD_METRICS_PATH, 500, "InternalError")
        record(client.resourceUsage)

        val usage = awaitUsage { true }

        assertFalse(usage.metricsAvailable)
    }

    @Test
    fun `failed metrics reads never count as connection failures`() = runBlocking {
        answer(POD_METRICS_PATH, 500, "InternalError")
        answer(NODE_METRICS_PATH, 500, "InternalError")
        record(client.resourceUsage)
        record(client.nodeUsages)

        // Seeing the fifth means four were handled: one past the manager's
        // three-consecutive-failures threshold.
        awaitRequests(POD_METRICS_PATH, 5)

        // The value itself: the recording collector may not have run yet.
        assertEquals(null, manager.connectionError.value, "a metrics failure must never set connectionError")
        assertTrue(observedErrors.filterNotNull().isEmpty(), "a metrics failure must never set connectionError: $observedErrors")
    }
}
