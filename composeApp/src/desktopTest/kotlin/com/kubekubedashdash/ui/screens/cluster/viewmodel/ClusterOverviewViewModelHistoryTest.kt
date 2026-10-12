package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetricsBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overview's usage sparklines are session-lived (the ViewModel belongs to
 * the cluster session), while most of what they plot follows the selected
 * namespace. A namespace switch must therefore start a fresh series rather
 * than draw the old namespace's readings into the new one's trend as a cliff.
 *
 * The Pods series is fed by this ViewModel and cleared by its own reset; the
 * CPU and Memory series are the session's usage samples, which reset
 * themselves (UsageSampler). The sampler's clock is fixed here, so every
 * reading falls in one 3-minute bar however long the run takes.
 *
 * The mock cluster: one node with 10 pod slots, 4 CPU cores and 4 GiB; 2 pods in
 * ns-a and 5 in ns-b; and metrics that give each namespace 100m of CPU and 64 MiB per pod.
 */
class ClusterOverviewViewModelHistoryTest {

    private companion object {
        const val SAMPLE_EPOCH = 1_700_000_100L
    }

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var vm: ClusterOverviewViewModel
    private var podsCollector: Job? = null

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
                    .addToAllocatable("pods", Quantity("10"))
                    .addToAllocatable("cpu", Quantity("4"))
                    .addToAllocatable("memory", Quantity("4Gi"))
                    .endStatus()
                    .build(),
            ).create()
            // 2 pods in ns-a, 5 in ns-b, against 10 pods of capacity: 0.2 and 0.5.
            for ((ns, count) in listOf("ns-a" to 2, "ns-b" to 5)) {
                repeat(count) { i ->
                    seed.pods().inNamespace(ns).resource(
                        PodBuilder().withNewMetadata().withName("p$i").withNamespace(ns).endMetadata().build(),
                    ).create()
                }
                // One metrics entry carrying the whole namespace's usage.
                seed.resources(PodMetrics::class.java).inNamespace(ns).resource(
                    PodMetricsBuilder()
                        .withNewMetadata().withName("p0").withNamespace(ns).endMetadata()
                        .addToContainers(
                            ContainerMetricsBuilder()
                                .withName("c")
                                .addToUsage("cpu", Quantity("${count * 100}m"))
                                .addToUsage("memory", Quantity("${count * 64}Mi"))
                                .build(),
                        )
                        .build(),
                ).create()
            }
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager, nowEpochSec = { SAMPLE_EPOCH })
        client.setNamespaceScope(NamespaceScope.single("ns-a"))
        vm = ClusterOverviewViewModel(client)
        // The screen's collectAsState keeps this upstream alive; the Pods
        // series is only sampled while it is collected.
        podsCollector = scope.launch { vm.podsLoaded.collect {} }
    }

    @AfterTest
    fun tearDown() {
        podsCollector?.cancel()
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            label = "ClusterOverviewViewModelHistoryTest",
            manager = manager,
            servers = listOf(server),
        )
    }

    private suspend fun awaitLastPodsSample(value: Float): List<Float> = withTimeout(10_000) {
        vm.podsHistory.first { it.lastOrNull() == value }
    }

    private suspend fun awaitLastCpuBar(value: Float): List<Float> = withTimeout(10_000) {
        vm.cpuHistory.first { it.lastOrNull() == value }
    }

    private suspend fun awaitLastMemBar(value: Float): List<Float> = withTimeout(10_000) {
        vm.memHistory.first { it.lastOrNull() == value }
    }

    @Test
    fun `pods series starts at the first real reading, not a zero while pods load`() = runBlocking {
        // Capacity can land before the pod list; sampling then recorded a
        // spurious 0 % point at the head of the series.
        assertEquals(listOf(0.2f), awaitLastPodsSample(0.2f))
    }

    @Test
    fun `namespace switch starts a fresh series`() = runBlocking {
        awaitLastPodsSample(0.2f)

        client.setNamespaceScope(NamespaceScope.single("ns-b"))

        // Neither ns-a's 0.2 nor the 0 of the reload gap may survive the switch.
        assertEquals(listOf(0.5f), awaitLastPodsSample(0.5f))
    }

    @Test
    fun `cpu and memory series are the sampler's bars, there without the overview collecting usage`() = runBlocking {
        // Only podsLoaded is collected in setUp, never resourceUsage.
        assertEquals(listOf(200f / 4000), awaitLastCpuBar(200f / 4000))
        assertEquals(listOf(128f / 4096), awaitLastMemBar(128f / 4096))
    }

    @Test
    fun `namespace switch starts fresh cpu and memory series`() = runBlocking {
        awaitLastCpuBar(200f / 4000)
        awaitLastMemBar(128f / 4096)

        client.setNamespaceScope(NamespaceScope.single("ns-b"))

        // The sampler clears on the switch and reads again at once: ns-a's bar never survives it.
        assertEquals(listOf(500f / 4000), awaitLastCpuBar(500f / 4000))
        assertEquals(listOf(320f / 4096), awaitLastMemBar(320f / 4096))
    }
}
