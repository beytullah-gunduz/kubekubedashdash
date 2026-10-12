package com.kubekubedashdash.util

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The usage sampler as the client wires it, against a loopback mock whose CRUD
 * store serves the metrics: samples build up while nothing collects
 * [ReactiveKubeClient.resourceUsage], and follow the connection the way the
 * sparklines need (a reconnect keeps them, another context starts them over).
 *
 * The clock steps a slot per sample so each reading has a slot of its own.
 */
class ReactiveKubeClientUsageSamplesTest {

    private companion object {
        const val FIRST_EPOCH = 1_700_000_010L
        const val HOUR_MS = 3_600_000L
    }

    private lateinit var server: KubernetesMockServer
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private val clock = AtomicLong(FIRST_EPOCH)

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()

        server.createClient().use { seed ->
            seed.nodes().resource(
                NodeBuilder()
                    .withNewMetadata().withName("example-cluster").endMetadata()
                    .withNewStatus()
                    .addToAllocatable("cpu", Quantity("4"))
                    .addToAllocatable("memory", Quantity("4Gi"))
                    .endStatus()
                    .build(),
            ).create()
            seed.resources(PodMetrics::class.java).inNamespace("team-a").resource(
                PodMetricsBuilder()
                    .withNewMetadata().withName("web-0").withNamespace("team-a").endMetadata()
                    .addToContainers(
                        ContainerMetricsBuilder()
                            .withName("frontend")
                            .addToUsage("cpu", Quantity("1"))
                            .addToUsage("memory", Quantity("1Gi"))
                            .build(),
                    )
                    .build(),
            ).create()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(newClient(), "example-context").getOrThrow()
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientUsageSamplesTest", manager = manager, servers = listOf(server))
    }

    // No request retries: fabric8 retries a 5xx for ~100 s by default.
    private fun newClient() = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        .let { KubernetesClientBuilder().withConfig(it).build() }

    private fun client(usageSampleIntervalMs: Long) = ReactiveKubeClient(
        scope,
        manager,
        probeIntervalMs = HOUR_MS,
        metricsPollIntervalMs = HOUR_MS,
        usageSampleIntervalMs = usageSampleIntervalMs,
        nowEpochSec = { clock.getAndAdd(USAGE_SAMPLE_SLOT_SEC) },
    )

    private suspend fun ReactiveKubeClient.await(what: String, condition: (List<UsageSample>) -> Boolean): List<UsageSample> {
        try {
            withTimeout(10_000) { while (!condition(usageSamples.value)) delay(10) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; samples: ${usageSamples.value}", e)
        }
        return usageSamples.value
    }

    @Test
    fun `samples build up with nothing collecting the usage poll`() = runBlocking {
        val client = client(usageSampleIntervalMs = 25)

        val samples = client.await("three samples") { it.size >= 3 }

        // A quarter of the 4 cores and of the 4 GiB, as the node's allocatable and the pod's usage give it.
        assertEquals(1_000L, samples.first().cpuUsedMillis)
        assertEquals(4_000L, samples.first().cpuCapacityMillis)
        assertEquals(0.25f, samples.first().cpuFraction)
        assertEquals(0.25f, samples.first().memFraction)
        assertTrue(client.resourceUsage.value is ResourceState.Loading, "the usage poll never started: nothing subscribed to it")
    }

    @Test
    fun `a namespace selection samples only that namespace`() = runBlocking {
        val client = client(usageSampleIntervalMs = HOUR_MS)
        client.await("a sample of every namespace") { it.size == 1 }

        client.setNamespaceScope(NamespaceScope.single("team-b"))

        val samples = client.await("a sample of the empty namespace") { it.singleOrNull()?.cpuUsedMillis == 0L }
        assertEquals(1, samples.size)
        assertEquals(4_000L, samples.single().cpuCapacityMillis, "capacity stays the cluster's")
    }

    @Test
    fun `a reconnect to the same context keeps the samples and another context starts them over`() = runBlocking {
        val client = client(usageSampleIntervalMs = HOUR_MS)
        val first = client.await("the first sample") { it.size == 1 }.single()

        manager.connectWithClient(newClient(), "example-context").getOrThrow()
        val reconnected = client.await("a second sample beside the first") { it.size == 2 }
        assertEquals(first, reconnected.first())

        manager.connectWithClient(newClient(), "example-cluster").getOrThrow()
        val switched = client.await("one sample of the new context") { it.size == 1 && it.single() != first }
        assertTrue(switched.single().epochSec > reconnected.last().epochSec, "a reading taken after the switch: $switched")
    }

    @Test
    fun `an interval of zero sends no metrics request`() = runBlocking {
        // The seeding POSTs of setUp are not under test.
        while (server.takeRequest(0, TimeUnit.MILLISECONDS) != null) Unit
        val client = client(usageSampleIntervalMs = 0)

        delay(400)

        assertTrue(client.usageSamples.value.isEmpty())
        while (true) {
            val request = server.takeRequest(0, TimeUnit.MILLISECONDS) ?: break
            assertTrue(request.path?.contains("metrics.k8s.io") != true, "no metrics request: ${request.path}")
        }
    }
}
