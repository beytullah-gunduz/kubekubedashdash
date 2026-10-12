package com.kubekubedashdash.ui.screens.nodes.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.NodeBuilder
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Nodes screen's CPU and Memory bars are the session's usage samples as
 * 3-minute bars, there whether or not the screen ever collected the usage poll.
 * One node with 4 cores and 4 GiB, and a pod using a quarter of each. The
 * sampler's clock is fixed, so every reading falls in one bar.
 */
class NodesScreenViewModelUsageHistoryTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var scope: CoroutineScope
    private lateinit var vm: NodesScreenViewModel

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
            seed.resources(PodMetrics::class.java).inNamespace("default").resource(
                PodMetricsBuilder()
                    .withNewMetadata().withName("web-0").withNamespace("default").endMetadata()
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
        manager.connectWithClient(server.createClient(), "example-context").getOrThrow()
        vm = NodesScreenViewModel(ReactiveKubeClient(scope, manager, nowEpochSec = { 1_700_000_100L }))
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, vm.viewModelScope, label = "NodesScreenViewModelUsageHistoryTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `cpu and memory bars come from the sampler, with nothing collecting the usage poll`() = runBlocking {
        assertEquals(listOf(0.25f), withTimeout(10_000) { vm.cpuHistory.first { it.isNotEmpty() } })
        assertEquals(listOf(0.25f), withTimeout(10_000) { vm.memHistory.first { it.isNotEmpty() } })
    }
}
