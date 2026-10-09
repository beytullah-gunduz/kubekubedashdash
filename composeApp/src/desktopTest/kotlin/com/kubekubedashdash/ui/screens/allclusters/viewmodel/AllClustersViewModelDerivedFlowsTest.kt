package com.kubekubedashdash.ui.screens.allclusters.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.ui.screens.allclusters.EventGroup
import com.kubekubedashdash.ui.screens.allclusters.HeatmapData
import com.kubekubedashdash.ui.screens.allclusters.TimeWindow
import com.kubekubedashdash.ui.screens.viewmodel.SessionViewModel
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The All Clusters event pipeline end to end: the merge across tabs, the time
 * window, the filters, the groups, the heatmap and each cluster's warning
 * count. The view model computes them on an injected dispatcher, off the UI
 * thread; the expected values are what it produced while it still computed
 * on viewModelScope and parsed every timestamp on every pass.
 *
 * Two tabs on one mock cluster, each following its own namespace: ns-a's
 * events belong to cluster-1, ns-b's to cluster-2. Every timestamp sits in the
 * middle of a 5-minute histogram bucket, so the buckets don't depend on the
 * seconds the test takes. One event carries a timestamp that does not parse.
 */
class AllClustersViewModelDerivedFlowsTest {

    /** Hands work to one thread and counts what it was handed. */
    private class CountingDispatcher(private val delegate: ExecutorCoroutineDispatcher) : CoroutineDispatcher() {
        val dispatched = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private lateinit var server: KubernetesMockServer
    private lateinit var scope: CoroutineScope
    private val tabs = MutableStateFlow<List<WorkspaceTab.Cluster>>(emptyList())
    private val sessions = mutableListOf<ClusterSession>()
    private val computeThread = Executors.newSingleThreadExecutor { Thread(it, "all-clusters-test-compute").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val compute = CountingDispatcher(computeThread)
    private lateinit var vm: AllClustersViewModel

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    /** [minutes] minutes and 30 seconds ago, as the mapper writes it. */
    private fun ago(minutes: Long): String = now.minusSeconds(minutes * 60 + 30).toString()

    private data class Seed(val ns: String, val name: String, val type: String, val reason: String, val pod: String, val count: Int, val last: String)

    private val seeds = listOf(
        Seed("ns-a", "a-backoff-new", "Warning", "BackOff", "api-1", 3, ago(2)),
        Seed("ns-a", "a-backoff-old", "Warning", "BackOff", "api-1", 2, ago(22)),
        Seed("ns-a", "a-scheduled", "Normal", "Scheduled", "api-2", 1, ago(5)),
        Seed("ns-a", "a-mount", "Warning", "FailedMount", "db-0", 4, ago(90)),
        Seed("ns-b", "b-backoff", "Warning", "BackOff", "api-1", 5, ago(12)),
        Seed("ns-b", "b-unhealthy-1", "Warning", "Unhealthy", "web-0", 1, ago(32)),
        Seed("ns-b", "b-unhealthy-2", "Warning", "Unhealthy", "web-0", 2, ago(42)),
        Seed("ns-b", "b-pulled", "Normal", "Pulled", "web-0", 1, ago(1)),
        Seed("ns-b", "b-garbled", "Warning", "Garbled", "web-1", 7, "not-a-time"),
    )

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        val seed = server.createClient()
        try {
            for (s in seeds) {
                seed.v1().events().inNamespace(s.ns).resource(
                    EventBuilder()
                        .withNewMetadata().withName(s.name).withNamespace(s.ns).endMetadata()
                        .withType(s.type)
                        .withReason(s.reason)
                        // The message names the seed, so assertions can say which event is which.
                        .withMessage(s.name)
                        .withCount(s.count)
                        .withLastTimestamp(s.last)
                        .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName(s.pod).withNamespace(s.ns).build())
                        .build(),
                ).create()
            }
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        vm = AllClustersViewModel(tabs, compute)
        // The screen's collectAsState keeps every derived flow subscribed.
        for (flow in listOf(vm.aggregatedEvents, vm.availableClusters, vm.availableNamespaces, vm.availableReasons, vm.filteredEvents, vm.groupedEvents, vm.heatmapData, vm.clusterSummaries)) {
            scope.launch { flow.collect {} }
        }
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            *sessions.map { it.scope }.toTypedArray(),
            label = "AllClustersViewModelDerivedFlowsTest",
        )
        sessions.forEach { runCatching { it.connectionManager.close() } }
        shutdownCleanly(label = "AllClustersViewModelDerivedFlowsTest", servers = listOf(server))
        computeThread.close()
    }

    /** Opens a tab named [label] on the mock cluster, following namespace [ns]. */
    private fun openTab(label: String, ns: String): ClusterSession {
        val session = ClusterSession()
        sessions += session
        session.connectionManager.connectWithClient(server.createClient(), label).getOrThrow()
        session.reactiveClient.setNamespaceScope(NamespaceScope.single(ns))
        // connectWithClient leaves the tab's context blank; connectToCluster,
        // which needs a kubeconfig, would set it exactly like this.
        val selected = SessionViewModel::class.java.getDeclaredField("_selectedContext").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (selected.get(session.viewModel) as MutableStateFlow<String>).value = label
        tabs.value = tabs.value + WorkspaceTab.Cluster(session)
        return session
    }

    /** Waits for [flow]'s projection to equal [expected]; on a timeout, fails with the last projection. */
    private suspend fun <T, R> awaitEquals(expected: R, flow: StateFlow<T>, project: (T) -> R) {
        val got = withTimeoutOrNull(15_000) { flow.map(project).first { it == expected } }
        assertEquals(expected, got ?: project(flow.value))
    }

    private fun messages(events: List<EventInfo>): List<String> = events.map { it.message }

    /** What a group shows, with its members named by their seeds. */
    private data class GroupView(
        val reason: String,
        val objectRef: String,
        val totalCount: Int,
        val perCluster: Map<String, Int>,
        val lastSeen: Instant,
        val latestMessage: String,
        val members: List<String>,
        val histogram: List<Int>,
    )

    private fun view(groups: List<EventGroup>): List<GroupView> = groups.map { g ->
        GroupView(g.key.reason, g.key.objectRef, g.totalCount, g.perClusterCounts, g.lastSeenInstant, g.latestMessage, g.members.map { it.message }, g.bucketHistogram)
    }

    private fun bucketsWith(vararg indices: Int): List<Int> = List(12) { i -> indices.count { it == i } }

    @Test
    fun `derived flows merge, filter, group and count every tab's events`() = runBlocking {
        val first = openTab("cluster-1", "ns-a")
        val second = openTab("cluster-2", "ns-b")

        // Newest first by timestamp text: the unparseable one sorts on top.
        awaitEquals(
            listOf(
                "cluster-2:b-garbled", "cluster-2:b-pulled", "cluster-1:a-backoff-new", "cluster-1:a-scheduled", "cluster-2:b-backoff",
                "cluster-1:a-backoff-old", "cluster-2:b-unhealthy-1", "cluster-2:b-unhealthy-2", "cluster-1:a-mount",
            ),
            vm.aggregatedEvents,
        ) { list -> list.map { "${it.cluster}:${it.message}" } }
        assertEquals(
            mapOf<String?, Set<String?>>("cluster-1" to setOf(first.id.value), "cluster-2" to setOf(second.id.value)),
            vm.aggregatedEvents.value.groupBy { it.cluster }.mapValues { (_, evs) -> evs.map { it.sessionId }.toSet() },
        )
        awaitEquals(setOf("cluster-1", "cluster-2"), vm.availableClusters) { it }
        awaitEquals(setOf("ns-a", "ns-b"), vm.availableNamespaces) { it }

        // Default filters: Warning and Error over the last hour.
        awaitEquals(setOf("Pulled", "BackOff", "Scheduled", "Unhealthy"), vm.availableReasons) { it }
        awaitEquals(listOf("a-backoff-new", "b-backoff", "a-backoff-old", "b-unhealthy-1", "b-unhealthy-2"), vm.filteredEvents, ::messages)
        awaitEquals(
            listOf(
                GroupView(
                    "BackOff",
                    "Pod/api-1",
                    10,
                    mapOf("cluster-1" to 5, "cluster-2" to 5),
                    Instant.parse(ago(2)),
                    "a-backoff-new",
                    listOf("a-backoff-new", "b-backoff", "a-backoff-old"),
                    bucketsWith(11, 9, 7),
                ),
                GroupView(
                    "Unhealthy",
                    "Pod/web-0",
                    3,
                    mapOf("cluster-2" to 3),
                    Instant.parse(ago(32)),
                    "b-unhealthy-1",
                    listOf("b-unhealthy-1", "b-unhealthy-2"),
                    bucketsWith(5, 3),
                ),
            ),
            vm.groupedEvents,
            ::view,
        )
        awaitEquals(
            HeatmapData(
                clusters = listOf("cluster-1", "cluster-2"),
                reasons = listOf("BackOff", "Unhealthy"),
                cells = mapOf(("cluster-1" to "BackOff") to 5, ("cluster-2" to "BackOff") to 5, ("cluster-2" to "Unhealthy") to 3),
            ),
            vm.heatmapData,
        ) { it }
        awaitEquals(listOf("cluster-1" to 2, "cluster-2" to 3), vm.clusterSummaries) { list -> list.map { it.contextName to it.recentErrorCount } }

        // A day's window takes in the 90-minute-old mount failure everywhere.
        vm.updateFilters { it.copy(timeWindow = TimeWindow.LAST_24H) }
        awaitEquals(setOf("Pulled", "BackOff", "Scheduled", "Unhealthy", "FailedMount"), vm.availableReasons) { it }
        awaitEquals(listOf("a-backoff-new", "b-backoff", "a-backoff-old", "b-unhealthy-1", "b-unhealthy-2", "a-mount"), vm.filteredEvents, ::messages)
        awaitEquals(
            listOf("BackOff" to bucketsWith(11, 11, 11), "FailedMount" to bucketsWith(11), "Unhealthy" to bucketsWith(11, 11)),
            vm.groupedEvents,
        ) { groups -> groups.map { it.key.reason to it.bucketHistogram } }
        awaitEquals(listOf("BackOff", "FailedMount", "Unhealthy"), vm.heatmapData) { it.reasons }
        awaitEquals(listOf("cluster-1" to 3, "cluster-2" to 3), vm.clusterSummaries) { list -> list.map { it.contextName to it.recentErrorCount } }

        // Search (debounced) narrows by object; Normal shows the other events.
        vm.updateFilters { it.copy(timeWindow = TimeWindow.LAST_1H, searchText = "web-0") }
        awaitEquals(listOf("b-unhealthy-1", "b-unhealthy-2"), vm.filteredEvents, ::messages)
        vm.updateFilters { it.copy(searchText = "", types = setOf("Normal")) }
        awaitEquals(listOf("b-pulled", "a-scheduled"), vm.filteredEvents, ::messages)

        // A cluster filter narrows the table but not the heatmap.
        vm.resetFilters()
        vm.updateFilters { it.copy(clusters = setOf("cluster-2")) }
        awaitEquals(listOf("b-backoff", "b-unhealthy-1", "b-unhealthy-2"), vm.filteredEvents, ::messages)
        awaitEquals(listOf("cluster-1", "cluster-2"), vm.heatmapData) { it.clusters }

        assertTrue(compute.dispatched.get() > 0, "the derived flows must run on the injected dispatcher")
    }
}
