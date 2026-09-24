package com.kubekubedashdash.ui.screens.allclusters.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.WorkspaceTab
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * All Clusters sums each open tab's pods and usage, which follow that tab's
 * selected namespace, against whole-cluster capacity. Its trend lines are
 * only meaningful over one set of (tab, namespace) pairs: when a tab switches
 * namespace, or drops out of the sum while it reloads, the series must start
 * over rather than join two different sums into one trend with a cliff.
 *
 * Every tab here connects to the same mock cluster: one node with 10 pod
 * slots and 4 CPU cores; 2 pods in ns-a, 5 in ns-b, 3 in ns-c; and 100m of
 * CPU per pod in each namespace's metrics.
 */
class AllClustersViewModelHistoryTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var scope: CoroutineScope
    private val tabs = MutableStateFlow<List<WorkspaceTab.Cluster>>(emptyList())
    private val sessions = mutableListOf<ClusterSession>()
    private lateinit var vm: AllClustersViewModel

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
            for ((ns, count) in listOf("ns-a" to 2, "ns-b" to 5, "ns-c" to 3)) {
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
        vm = AllClustersViewModel(tabs)
        // The screen's collectAsState keeps these upstreams alive; the
        // histories are only sampled while they are collected.
        scope.launch { vm.aggregatedUsage.collect {} }
        scope.launch { vm.podsHistory.collect {} }
    }

    @AfterTest
    fun tearDown() {
        // Scopes first, then each session's connection, then the server.
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            *sessions.map { it.scope }.toTypedArray(),
            label = "AllClustersViewModelHistoryTest",
        )
        sessions.forEach { runCatching { it.connectionManager.close() } }
        shutdownCleanly(label = "AllClustersViewModelHistoryTest", servers = listOf(server))
    }

    /** Opens a cluster tab on the mock cluster with [namespace] selected. */
    private fun openTab(label: String, namespace: String?): ClusterSession {
        val session = ClusterSession()
        sessions += session
        session.connectionManager.connectWithClient(server.createClient(), label).getOrThrow()
        session.reactiveClient.setSelectedNamespace(namespace)
        tabs.value = tabs.value + WorkspaceTab.Cluster(session)
        return session
    }

    private suspend fun StateFlow<List<Float>>.awaitLast(value: Float): List<Float> = withTimeout(10_000) {
        first { it.lastOrNull() == value }
    }

    @Test
    fun `a namespace switch starts fresh cpu, memory and pods series`() = runBlocking {
        val tab = openTab("cluster-1", "ns-a")
        vm.podsHistory.awaitLast(2f / 10)
        vm.cpuHistory.awaitLast(200f / 4000)

        tab.reactiveClient.setSelectedNamespace("ns-b")

        assertEquals(listOf(5f / 10), vm.podsHistory.awaitLast(5f / 10))
        assertEquals(listOf(500f / 4000), vm.cpuHistory.awaitLast(500f / 4000))
        assertEquals(listOf(320f / 4096), vm.memHistory.awaitLast(320f / 4096))
    }

    @Test
    fun `a series never starts on the partial sum taken while a tab reloads`() = runBlocking {
        val first = openTab("cluster-1", "ns-a")
        openTab("cluster-2", "ns-b")
        // Both tabs in: (2 + 5) pods over 20 slots.
        vm.podsHistory.awaitLast(7f / 20)
        vm.cpuHistory.awaitLast(700f / 8000)

        first.reactiveClient.setSelectedNamespace("ns-c")

        // While the first tab reloads, the sum is the second tab alone (5 of
        // 10 slots, 500m of 4 cores). That point must not head the new
        // series: the first reading after the switch is (3 + 5) over both.
        assertEquals(listOf(8f / 20), vm.podsHistory.awaitLast(8f / 20))
        assertEquals(listOf(800f / 8000), vm.cpuHistory.awaitLast(800f / 8000))
    }

    @Test
    fun `each cluster summary names the namespace its tab follows`() = runBlocking {
        val first = openTab("cluster-1", "ns-a")
        val second = openTab("cluster-2", null)

        val namespaces = withTimeout(10_000) { vm.clusterSummaries.first { it.size == 2 } }
            .associate { it.sessionId to it.namespace }

        assertEquals(mapOf(first.id to "ns-a", second.id to null), namespaces)
    }
}
