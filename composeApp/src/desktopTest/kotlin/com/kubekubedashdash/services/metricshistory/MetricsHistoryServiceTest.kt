package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The service re-resolves the source from the live context, demo flag, settings and detection state
 * on every call, hands out one in-cluster source per cluster and target, and shares the access review.
 */
class MetricsHistoryServiceTest {

    private var previousCloudClis: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stubDelegate = lazy { ClusterStub() }
    private val stub by stubDelegate

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restoreCloudClis() {
        try {
            shutdownCleanly(scope, label = "MetricsHistoryServiceTest")
            if (stubDelegate.isInitialized()) stub.close("MetricsHistoryServiceTest")
        } finally {
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    private class FakeSource(override val label: String) : MetricsHistorySource {
        override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Ok(emptyList())
    }

    private val monitoring = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")
    private val clock = AtomicLong(1_000_000)
    private var context: String? = "example-context"
    private var demo = false
    private val settings = MutableStateFlow<Map<String, MetricsSourceSetting>>(emptyMap())
    private var settingsReads = 0
    private val requestedTargets = mutableListOf<GmpTarget>()
    private val fakeGmp = FakeSource("fake gmp")
    private val fakeDemo = FakeSource("fake demo")
    private val clientRequests = CopyOnWriteArrayList<String>()
    private val searches = AtomicInteger()
    private var searchResult: () -> DetectionState = { DetectionState.NothingFound(clock.get()) }
    private var clientOf: () -> KubernetesClient? = { dummyClient() }

    private val detection = InClusterDetection(scope, clock::get) {
        searches.incrementAndGet()
        searchResult()
    }

    private val service = MetricsHistoryService(
        currentContext = { context },
        isDemo = { demo },
        settingsFlow = {
            settingsReads++
            settings
        },
        gmpSourceFor = { target ->
            requestedTargets.add(target)
            fakeGmp
        },
        demoSource = fakeDemo,
        detection = detection,
        clientFor = { name ->
            clientRequests += name
            clientOf()
        },
        nowMs = clock::get,
    )

    private val key = "example-context"

    @Test
    fun `the settings are not read until they are needed`() {
        assertEquals(0, settingsReads)

        service.choice()
        service.choice()

        assertEquals(1, settingsReads)
    }

    @Test
    fun `the choice follows the live context`() {
        context = null
        assertEquals(SourceChoice.None(NoSourceReason.NotConnected), service.choice())

        context = "example-context"
        assertEquals(SourceChoice.Detecting, service.choice())

        context = "gke_example-project_us-central1_example-cluster"
        assertEquals(SourceChoice.Gmp(GmpTarget("example-project", "us-central1", "example-cluster"), auto = true), service.choice())
    }

    @Test
    fun `the choice follows the demo flag`() {
        demo = true
        assertEquals(SourceChoice.Demo, service.choice())

        demo = false
        context = DemoContext.MOCK_CONTEXT_NAME
        assertEquals(SourceChoice.Demo, service.choice())
    }

    @Test
    fun `the choice follows the settings`() {
        context = "gke_example-project_us-central1_example-cluster"
        settings.value = mapOf(context!! to MetricsSourceSetting(MetricsSourceMode.OFF))
        assertEquals(SourceChoice.None(NoSourceReason.Disabled), service.choice())

        settings.value = emptyMap()
        assertEquals(SourceChoice.Gmp(GmpTarget("example-project", "us-central1", "example-cluster"), auto = true), service.choice())
    }

    // ── detection ───────────────────────────────────────────────────────────────

    @Test
    fun `the choice follows what detection has found for the live context`() = runBlocking<Unit> {
        val cases = listOf(
            DetectionState.Found(monitoring) to SourceChoice.InCluster(monitoring, auto = true),
            DetectionState.NothingFound(clock.get()) to SourceChoice.None(NoSourceReason.NothingFound),
            DetectionState.CannotSearch(clock.get()) to SourceChoice.None(NoSourceReason.CannotSearch),
            DetectionState.SearchFailed(clock.get()) to SourceChoice.None(NoSourceReason.SearchFailed),
        )
        for ((state, expected) in cases) {
            detection.invalidate(key)
            searchResult = { state }
            assertEquals(SourceChoice.Detecting, service.choice(), "$state before the search")

            service.ensureDetected()

            assertEquals(expected, service.choice(), "$state")
        }
    }

    @Test
    fun `an expired answer is detecting again, and one ensureDetected searches again`() = runBlocking<Unit> {
        service.ensureDetected()
        assertEquals(SourceChoice.None(NoSourceReason.NothingFound), service.choice())
        assertEquals(1, searches.get())

        clock.addAndGet(InClusterDetection.NOT_FOUND_TTL_MS + 1)
        assertEquals(SourceChoice.Detecting, service.choice())
        service.ensureDetected()

        assertEquals(2, searches.get())
        assertEquals(SourceChoice.None(NoSourceReason.NothingFound), service.choice())
    }

    @Test
    fun `ensureDetected searches once while detecting and then does nothing`() = runBlocking<Unit> {
        service.ensureDetected()
        service.ensureDetected()

        assertEquals(1, searches.get())
        assertEquals(listOf("example-context"), clientRequests.toList())
    }

    @Test
    fun `ensureDetected does nothing unless the choice is detecting`() = runBlocking<Unit> {
        context = null
        service.ensureDetected()

        context = "gke_example-project_us-central1_example-cluster"
        service.ensureDetected()

        context = "example-context"
        demo = true
        service.ensureDetected()
        demo = false

        context = DemoContext.MOCK_CONTEXT_NAME
        service.ensureDetected()

        context = "example-context"
        settings.value = mapOf(key to MetricsSourceSetting(MetricsSourceMode.OFF))
        service.ensureDetected()
        settings.value = mapOf(key to MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster"))
        service.ensureDetected()
        settings.value = mapOf(key to MetricsSourceSetting(MetricsSourceMode.IN_CLUSTER, namespace = "monitoring", service = "prometheus-operated", port = "web"))
        service.ensureDetected()

        assertEquals(0, searches.get())
        assertEquals(emptyMap(), detection.state.value)
    }

    @Test
    fun `the detection state is exposed for recomposition, keyed by the preference key`() = runBlocking<Unit> {
        assertEquals(emptyMap(), service.detectionState.value)
        searchResult = { DetectionState.Found(monitoring) }

        service.ensureDetected()

        assertEquals(mapOf(key to DetectionState.Found(monitoring)), service.detectionState.value)
    }

    @Test
    fun `the search gets the client of the live context`() = runBlocking<Unit> {
        val client = dummyClient()
        clientOf = { client }
        var seen: KubernetesClient? = null
        val capturing = InClusterDetection(scope, clock::get) {
            seen = it
            DetectionState.NothingFound(clock.get())
        }
        val own = MetricsHistoryService(
            currentContext = { context },
            isDemo = { demo },
            settingsFlow = { settings },
            gmpSourceFor = { fakeGmp },
            demoSource = fakeDemo,
            detection = capturing,
            clientFor = { name ->
                clientRequests += name
                clientOf()
            },
        )

        own.ensureDetected()

        assertSame(client, seen)
        assertEquals(listOf("example-context"), clientRequests.toList())
    }

    // ── sources ─────────────────────────────────────────────────────────────────

    @Test
    fun `a source is picked for the demo, for a gmp target and not at all for none or detecting`() {
        assertSame(fakeDemo, service.sourceFor(SourceChoice.Demo))

        val target = GmpTarget("example-project", "us-central1", "example-cluster")
        assertSame(fakeGmp, service.sourceFor(SourceChoice.Gmp(target, auto = true)))
        assertEquals(listOf(target), requestedTargets)

        for (reason in NoSourceReason.entries) {
            assertNull(service.sourceFor(SourceChoice.None(reason)), "$reason")
        }
        assertNull(service.sourceFor(SourceChoice.Detecting))
        assertEquals(1, requestedTargets.size)
    }

    @Test
    fun `the in-cluster source is one per cluster and target, so its caches survive the panel asking again`() {
        val choice = SourceChoice.InCluster(monitoring, auto = true)

        val first = service.sourceFor(choice)

        assertIs<InClusterMetricsSource>(first)
        assertSame(first, service.sourceFor(choice))
        assertSame(first, service.sourceFor(SourceChoice.InCluster(monitoring.copy(), auto = true)))
        assertEquals("Prometheus · monitoring/prometheus-operated", first.label)

        val other = service.sourceFor(SourceChoice.InCluster(monitoring.copy(service = "prometheus-server"), auto = true))
        assertNotSame(first, other)
        assertNotSame(first, service.sourceFor(SourceChoice.InCluster(monitoring.copy(port = "9090"), auto = true)))
        assertNotSame(first, service.sourceFor(SourceChoice.InCluster(monitoring.copy(https = true), auto = true)))
        // The same target typed by hand is not one that detection can invalidate.
        assertNotSame(first, service.sourceFor(SourceChoice.InCluster(monitoring, auto = false)))
    }

    @Test
    fun `the in-cluster source of another cluster is another source`() {
        val choice = SourceChoice.InCluster(monitoring, auto = true)
        val first = service.sourceFor(choice)

        context = "other-context"

        assertNotSame(first, service.sourceFor(choice))
        context = null
        assertNull(service.sourceFor(choice))
    }

    @Test
    fun `a source never queries a cluster other than the one it was made for`() = runBlocking<Unit> {
        val live = stub.client()
        clientOf = { live }
        val source = service.sourceFor(SourceChoice.InCluster(monitoring, auto = true))!!
        val pod = PodRef("team-a", "web-0")
        val window = QueryWindow(1_000, 1_120, 30)
        stub.proxy = { jsonResponse(200, matrixBody("app" to listOf(1_000L to "1"))) }
        assertIs<SeriesResult.Ok>(source.podSeries(PodSeriesKind.Cpu, pod, window))
        clientRequests.clear()

        context = "other-context"
        val result = source.podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "not connected"), result)
        assertEquals(1, stub.proxyRequests.size)
        assertEquals(emptyList(), clientRequests.toList(), "the other cluster's client was asked for")
    }

    // ── the access review ───────────────────────────────────────────────────────

    private suspend fun cpuOf(source: MetricsHistorySource) = source.podSeries(PodSeriesKind.Cpu, PodRef("team-a", "web-0"), QueryWindow(1_000, 1_120, 30))

    @Test
    fun `the access review is posted once per namespace within ten minutes, across sources and panels`() = runBlocking<Unit> {
        val live = stub.client()
        clientOf = { live }
        stub.proxy = { jsonResponse(403, K8S_FORBIDDEN) }
        stub.review = { ClusterStub.reviewResponse(allowed = false) }
        val first = service.sourceFor(SourceChoice.InCluster(monitoring, auto = true))!!
        val again = service.sourceFor(SourceChoice.InCluster(monitoring, auto = true))!!
        val sameNamespace = service.sourceFor(SourceChoice.InCluster(monitoring.copy(service = "prometheus-server"), auto = true))!!

        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), cpuOf(first))
        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), cpuOf(again))
        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), cpuOf(sameNamespace))

        assertEquals(1, stub.reviewRequests.size, "the review was posted again")
        val body = stub.reviewRequests.single().body
        for (fragment in listOf("\"verb\":\"get\"", "\"resource\":\"services\"", "\"subresource\":\"proxy\"", "\"namespace\":\"monitoring\"")) {
            assertTrue(fragment in body, "$fragment missing from $body")
        }

        // Another namespace has its own answer.
        val elsewhere = service.sourceFor(SourceChoice.InCluster(monitoring.copy(namespace = "team-a"), auto = true))!!
        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), cpuOf(elsewhere))
        assertEquals(2, stub.reviewRequests.size)

        clock.addAndGet(ServiceProxyAccessReview.TTL_MS - 1)
        cpuOf(first)
        assertEquals(2, stub.reviewRequests.size, "the answer outlived its ten minutes early")

        clock.addAndGet(1)
        cpuOf(first)
        assertEquals(3, stub.reviewRequests.size, "the answer outlived its ten minutes")
    }

    @Test
    fun `an access review that failed is not remembered`() = runBlocking<Unit> {
        val live = stub.client()
        clientOf = { live }
        stub.proxy = { jsonResponse(403, K8S_FORBIDDEN) }
        stub.review = { jsonResponse(500, """{"kind":"Status","code":500}""") }
        val source = service.sourceFor(SourceChoice.InCluster(monitoring, auto = true))!!

        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), cpuOf(source))
        stub.review = { ClusterStub.reviewResponse(allowed = false) }
        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), cpuOf(source))

        assertEquals(2, stub.reviewRequests.size)
    }

    @Test
    fun `an access review that allows leaves a 403 as the Prometheus refusing`() = runBlocking<Unit> {
        val live = stub.client()
        clientOf = { live }
        stub.proxy = { jsonResponse(403, "denied by the Prometheus") }
        stub.review = { ClusterStub.reviewResponse(allowed = true) }
        val source = service.sourceFor(SourceChoice.InCluster(monitoring, auto = true))!!

        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), cpuOf(source))
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), cpuOf(source))
        assertEquals(1, stub.reviewRequests.size)
    }

    @Test
    fun `no review is posted when nothing was refused`() = runBlocking<Unit> {
        val live = stub.client()
        clientOf = { live }
        stub.proxy = { jsonResponse(200, matrixBody("app" to listOf(1_000L to "1"))) }
        val source = service.sourceFor(SourceChoice.InCluster(monitoring, auto = true))!!

        assertIs<SeriesResult.Ok>(cpuOf(source))

        assertEquals(0, stub.reviewRequests.size)
    }
}
