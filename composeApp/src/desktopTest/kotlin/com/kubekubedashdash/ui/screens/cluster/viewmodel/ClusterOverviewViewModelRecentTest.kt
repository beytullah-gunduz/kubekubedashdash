package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.formatAge
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.NodeConditionBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overview's recent-activity cards and pod phase counts: severity first,
 * then newest first, the first ten of them with fresh ages, and the total.
 *
 * Twelve pods and twelve events, so the slice drops two of each. Events carry
 * their own lastTimestamp, some minutes and 30 seconds old, so their order and
 * ages ("5m") are exact. The CRUD mock overwrites every object's
 * creationTimestamp with the second it was created, so for nodes and pods only
 * the severity order is pinned here, not the order within a severity; the
 * full order, ties included, is checked against the old algorithm below.
 * The view model computes all of it on an injected dispatcher.
 */
class ClusterOverviewViewModelRecentTest {

    /** Hands work to one thread and counts what it was handed. */
    private class CountingDispatcher(private val delegate: ExecutorCoroutineDispatcher) : CoroutineDispatcher() {
        val dispatched = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private val computeThread = Executors.newSingleThreadExecutor { Thread(it, "overview-test-compute").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val compute = CountingDispatcher(computeThread)

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var vm: ClusterOverviewViewModel

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    /** [minutes] minutes and 30 seconds ago. */
    private fun ago(minutes: Long): String = now.minusSeconds(minutes * 60 + 30).toString()

    // name to phase; the mock stamps creationTimestamp itself.
    private val pods = listOf("failed-1" to "Failed", "failed-2" to "Failed", "pending" to "Pending") +
        List(9) { i -> "run-$i" to "Running" }

    // name to (type, minutes since last seen)
    private val events = listOf(
        "warn-02" to ("Warning" to 2L),
        "warn-15" to ("Warning" to 15L),
        "warn-40" to ("Warning" to 40L),
        "norm-01" to ("Normal" to 1L),
        "norm-03" to ("Normal" to 3L),
        "norm-04" to ("Normal" to 4L),
        "norm-06" to ("Normal" to 6L),
        "norm-07" to ("Normal" to 7L),
        "norm-08" to ("Normal" to 8L),
        "norm-09" to ("Normal" to 9L),
        "norm-11" to ("Normal" to 11L),
        "norm-12" to ("Normal" to 12L),
    )

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        val seed = server.createClient()
        try {
            for ((name, ready) in listOf("node-a" to "True", "node-down" to "False", "node-b" to "True")) {
                seed.nodes().resource(
                    NodeBuilder()
                        .withNewMetadata().withName(name).endMetadata()
                        .withNewStatus().addToConditions(NodeConditionBuilder().withType("Ready").withStatus(ready).build()).endStatus()
                        .build(),
                ).create()
            }
            for ((name, phase) in pods) {
                seed.pods().inNamespace("default").resource(
                    PodBuilder()
                        .withNewMetadata().withName(name).withNamespace("default").endMetadata()
                        .withNewStatus().withPhase(phase).endStatus()
                        .build(),
                ).create()
            }
            for ((name, spec) in events) {
                seed.v1().events().inNamespace("default").resource(
                    EventBuilder()
                        .withNewMetadata().withName(name).withNamespace("default").endMetadata()
                        .withType(spec.first)
                        .withReason("Test")
                        // The message names the seed, so assertions can say which event is which.
                        .withMessage(name)
                        .withLastTimestamp(ago(spec.second))
                        .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("run-0").withNamespace("default").build())
                        .build(),
                ).create()
            }
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
        client.setNamespaceScope(NamespaceScope.All)
        vm = ClusterOverviewViewModel(client, compute)
        // The screen's collectAsState keeps these subscribed.
        for (flow in listOf(vm.recentNodes, vm.recentPods, vm.recentEvents, vm.podPhaseCounts)) {
            scope.launch { flow.collect {} }
        }
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            label = "ClusterOverviewViewModelRecentTest",
            manager = manager,
            servers = listOf(server),
        )
        computeThread.close()
    }

    /** Waits for [flow]'s projection to equal [expected]; on a timeout, fails with the last projection. */
    private suspend fun <T, R> awaitEquals(expected: R, flow: StateFlow<T>, project: (T) -> R) {
        val got = withTimeoutOrNull(15_000) { flow.map(project).first { it == expected } }
        assertEquals(expected, got ?: project(flow.value))
    }

    @Test
    fun `recent cards list the most severe first, then the newest, with fresh ages`() = runBlocking {
        awaitEquals(Triple(listOf("NotReady", "Ready", "Ready"), 3, false), vm.recentNodes) { s -> Triple(s.items.map { it.status }, s.total, s.loading) }
        assertEquals("node-down", vm.recentNodes.value.items.first().name)

        awaitEquals(
            Triple(listOf("Failed", "Failed", "Pending") + List(7) { "Running" }, 12, false),
            vm.recentPods,
        ) { s -> Triple(s.items.map { it.status }, s.total, s.loading) }
        assertEquals(setOf("failed-1", "failed-2"), vm.recentPods.value.items.take(2).map { it.name }.toSet())

        awaitEquals(
            Triple(
                listOf(
                    "warn-02" to "2m", "warn-15" to "15m", "warn-40" to "40m",
                    "norm-01" to "1m", "norm-03" to "3m", "norm-04" to "4m", "norm-06" to "6m", "norm-07" to "7m", "norm-08" to "8m", "norm-09" to "9m",
                ),
                12,
                false,
            ),
            vm.recentEvents,
        ) { s -> Triple(s.items.map { it.message to it.lastSeen }, s.total, s.loading) }

        awaitEquals(PodPhaseCounts(running = 9, pending = 1, failed = 2, succeeded = 0), vm.podPhaseCounts) { it }

        assertTrue(compute.dispatched.get() > 0, "the recent cards must be computed on the injected dispatcher")
    }

    /** The overview's slicing as it was before it sorted first and aged only the kept items. */
    private fun <T> referenceSlice(s: ResourceState<List<T>>, refresh: (T) -> T, order: Comparator<T>): RecentSlice<T> = when (s) {
        is ResourceState.Success -> {
            val all = s.data.map(refresh).sortedWith(order)
            RecentSlice(items = all.take(CLUSTER_OVERVIEW_RECENT_LIMIT), total = all.size, loading = false, errorMessage = null)
        }

        is ResourceState.Loading -> RecentSlice(emptyList(), total = 0, loading = true, errorMessage = null)

        is ResourceState.Error -> RecentSlice(emptyList(), total = 0, loading = false, errorMessage = s.message)
    }

    // Shared timestamps (ties), a blank and an unparseable one, as the informers can produce.
    private val clock = Instant.parse("2026-01-01T12:00:00Z")
    private val stamps = listOf("2026-01-01T11:59:30Z", "2026-01-01T11:00:00Z", "2026-01-01T11:00:00Z", "2025-12-31T09:15:00Z", "2026-01-01T11:30:00.123456Z", "", "not-a-time")

    private fun <T> assertSameSlices(make: (Random, Int) -> T, refresh: (T) -> T, severity: (T) -> HealthSeverity, timestamp: (T) -> String) {
        val order = compareByDescending<T> { severity(it) }.thenByDescending { timestamp(it) }
        repeat(200) { seed ->
            val r = Random(seed)
            val items = List(r.nextInt(0, 40)) { make(r, it) }
            val state = ResourceState.Success(items)
            assertEquals(referenceSlice(state, refresh, order), sliceRecent(state, severity, timestamp, refresh), "seed $seed")
        }
        assertEquals(referenceSlice(ResourceState.Loading, refresh, order), sliceRecent(ResourceState.Loading, severity, timestamp, refresh))
        assertEquals(referenceSlice(ResourceState.Error("boom"), refresh, order), sliceRecent(ResourceState.Error("boom"), severity, timestamp, refresh))
    }

    @Test
    fun `slicing matches the old order and ages, ties included`() {
        val podStatuses = listOf("Running", "running", "Pending", "CrashLoopBackOff", "Failed", "Terminating", "Unknown", "")
        assertSameSlices(
            make = { r, i ->
                PodInfo(
                    uid = "u$i", name = "p$i", namespace = "ns", status = podStatuses.random(r), ready = "1/1", restarts = 0, age = "",
                    creationTimestamp = stamps.random(r), node = "", ip = "", labels = emptyMap(), annotations = emptyMap(), containers = emptyList(),
                )
            },
            refresh = { it.copy(age = formatAge(it.creationTimestamp, clock)) },
            severity = { podStatusSeverity(it.status) },
            timestamp = { it.creationTimestamp },
        )
        val nodeStatuses = listOf("Ready", "NotReady", "true", "false", "Unknown")
        assertSameSlices(
            make = { r, i ->
                NodeInfo(
                    uid = "u$i", name = "n$i", status = nodeStatuses.random(r), roles = "", version = "", os = "", arch = "", containerRuntime = "",
                    cpu = "", memory = "", pods = "", age = "", creationTimestamp = stamps.random(r), labels = emptyMap(), annotations = emptyMap(),
                )
            },
            refresh = { it.copy(age = formatAge(it.creationTimestamp, clock)) },
            severity = { nodeStatusSeverity(it.status) },
            timestamp = { it.creationTimestamp },
        )
        val eventTypes = listOf("Warning", "warning", "Normal", "Error", "")
        assertSameSlices(
            make = { r, i ->
                EventInfo(
                    uid = "u$i", type = eventTypes.random(r), reason = "R", objectRef = "Pod/p", message = "e$i", count = 1,
                    firstSeen = "", lastSeen = "", lastSeenTimestamp = stamps.random(r), namespace = "ns",
                )
            },
            refresh = { it.copy(lastSeen = formatAge(it.lastSeenTimestamp, clock)) },
            severity = { eventTypeSeverity(it.type) },
            timestamp = { it.lastSeenTimestamp },
        )
    }
}
