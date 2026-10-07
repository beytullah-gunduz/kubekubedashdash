package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerUsage
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.PodUsage
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `resourceUsage` takes ONE `top pods` sample per poll and derives both the
 * totals (KPI strip) and the per-pod map (Pods table) from it, so the two
 * cannot disagree. Seeded cluster: one node with 4 cores / 4 GiB allocatable;
 * ns-a/web-0 (an app and a sidecar container), ns-a/web-1 and ns-b/db-0.
 */
class ReactiveKubeClientPodUsageTest {

    private companion object {
        const val MIB = 1L shl 20
    }

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    private fun metrics(seed: KubernetesClient, ns: String, name: String, vararg containers: Triple<String, String, String>) {
        seed.resources(PodMetrics::class.java).inNamespace(ns).resource(
            PodMetricsBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .addToContainers(
                    *containers.map { (container, cpu, memory) ->
                        ContainerMetricsBuilder()
                            .withName(container)
                            .addToUsage("cpu", Quantity(cpu))
                            .addToUsage("memory", Quantity(memory))
                            .build()
                    }.toTypedArray(),
                )
                .build(),
        ).create()
    }

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()

        val seed = server.createClient()
        try {
            seed.nodes().resource(
                NodeBuilder()
                    .withNewMetadata().withName("n1").endMetadata()
                    .withNewStatus()
                    .addToAllocatable("cpu", Quantity("4"))
                    .addToAllocatable("memory", Quantity("4Gi"))
                    .endStatus()
                    .build(),
            ).create()

            metrics(seed, "ns-a", "web-0", Triple("app", "100m", "200Mi"), Triple("sidecar", "20m", "16Mi"))
            metrics(seed, "ns-a", "web-1", Triple("app", "50m", "100Mi"))
            metrics(seed, "ns-b", "db-0", Triple("db", "300m", "1Gi"))
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientPodUsageTest", manager = manager, servers = listOf(server))
    }

    private suspend fun awaitUsage(predicate: (ResourceUsageSummary) -> Boolean): ResourceUsageSummary = withTimeout(15_000) {
        (client.resourceUsage.first { it is ResourceState.Success && predicate(it.data) } as ResourceState.Success).data
    }

    @Test
    fun `one sample carries per-pod usage whose sum is the namespace total`() = runBlocking {
        client.setSelectedNamespace("ns-a")

        val usage = awaitUsage { it.podUsages.keys == setOf("ns-a/web-0", "ns-a/web-1") }

        assertEquals(
            PodUsage(
                cpuMillis = 120,
                memoryBytes = 216 * MIB,
                containers = listOf(
                    ContainerUsage("app", 100, 200 * MIB),
                    ContainerUsage("sidecar", 20, 16 * MIB),
                ),
            ),
            usage.podUsages["ns-a/web-0"],
        )
        assertEquals(usage.podUsages.values.sumOf { it.cpuMillis }, usage.cpuUsedMillis)
        assertEquals(usage.podUsages.values.sumOf { it.memoryBytes }, usage.memoryUsedBytes)
        assertEquals(4000L, usage.cpuCapacityMillis)
        assertTrue(usage.metricsAvailable)
    }

    @Test
    fun `all namespaces keys every pod by its own namespace`() = runBlocking {
        client.setSelectedNamespace(null)

        val usage = awaitUsage { it.podUsages.size == 3 }

        assertEquals(setOf("ns-a/web-0", "ns-a/web-1", "ns-b/db-0"), usage.podUsages.keys)
        assertEquals(300L, usage.podUsages["ns-b/db-0"]?.cpuMillis)
    }

    @Test
    fun `two selected namespaces sample every namespace and keep the selected ones`() = runBlocking {
        val seed = server.createClient()
        try {
            metrics(seed, "ns-c", "batch-0", Triple("batch", "10m", "8Mi"))
        } finally {
            seed.close()
        }
        client.setNamespaceScope(NamespaceScope.of(listOf("ns-a", "ns-b")))

        val usage = awaitUsage { it.podUsages.keys == setOf("ns-a/web-0", "ns-a/web-1", "ns-b/db-0") }

        assertEquals(470L, usage.cpuUsedMillis)
        assertEquals(1340 * MIB, usage.memoryUsedBytes)
    }
}
