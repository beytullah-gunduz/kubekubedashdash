package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
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
 * Only the Pods series is observable here: the CRUD mock serves no metrics
 * API, so CPU/Memory report `metricsAvailable = false` and never sample. All
 * three series share the one reset, so the Pods series pins it.
 */
class ClusterOverviewViewModelHistoryTest {

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
                    .withNewStatus().addToAllocatable("pods", Quantity("10")).endStatus()
                    .build(),
            ).create()
            // 2 pods in ns-a, 5 in ns-b, against 10 pods of capacity: 0.2 and 0.5.
            for ((ns, count) in listOf("ns-a" to 2, "ns-b" to 5)) {
                repeat(count) { i ->
                    seed.pods().inNamespace(ns).resource(
                        PodBuilder().withNewMetadata().withName("p$i").withNamespace(ns).endMetadata().build(),
                    ).create()
                }
            }
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
        client.setSelectedNamespace("ns-a")
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

    @Test
    fun `pods series starts at the first real reading, not a zero while pods load`() = runBlocking {
        // Capacity can land before the pod list; sampling then recorded a
        // spurious 0 % point at the head of the series.
        assertEquals(listOf(0.2f), awaitLastPodsSample(0.2f))
    }

    @Test
    fun `namespace switch starts a fresh series`() = runBlocking {
        awaitLastPodsSample(0.2f)

        client.setSelectedNamespace("ns-b")

        // Neither ns-a's 0.2 nor the 0 of the reload gap may survive the switch.
        assertEquals(listOf(0.5f), awaitLastPodsSample(0.5f))
    }
}
