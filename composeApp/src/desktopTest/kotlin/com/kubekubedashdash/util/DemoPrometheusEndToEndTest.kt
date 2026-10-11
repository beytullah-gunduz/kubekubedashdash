package com.kubekubedashdash.util

import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterMetricsSource
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PrometheusDetector
import com.kubekubedashdash.services.metricshistory.ReleaseCheckSupport
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The demo cluster as the app meets it: a booted mock instance, the real detection and the real
 * in-cluster source over fabric8's HTTP client, and no stand-in for the fake Prometheus.
 */
class DemoPrometheusEndToEndTest {

    private lateinit var handle: MockClusterHandle
    private var manager: KubeConnectionManager? = null
    private var previousCloudClis: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detection = InClusterDetection(scope)
    private val operated = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
        handle = MockClusterProvider.acquireNewInstance()
        // The churn adds and removes pods; the seeded ones the tests read stay, but nothing else needs to move.
        MockClusterProvider.simulators().forEach { it.stop() }
    }

    @AfterTest
    fun tearDown() {
        try {
            shutdownCleanly(scope, label = "DemoPrometheusEndToEndTest", manager = manager)
        } finally {
            // Releasing the last handle stops the simulator and destroys the mock server.
            if (::handle.isInitialized) handle.close()
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    private fun source(target: InClusterTarget = operated) = InClusterMetricsSource(
        target = target,
        auto = false,
        clientProvider = { handle.client },
        detection = detection,
        key = "example-context",
        accessReview = { null },
    )

    // A seeded pod with a CPU limit, which the simulator's churn never removes.
    private fun seededPod(): PodRef {
        val pod = assertNotNull(handle.client.pods().inNamespace("default").withName("frontend-7b9d5c8f4-abc12").get())
        return PodRef("default", "frontend-7b9d5c8f4-abc12", ResourceMappers.containerResources(pod))
    }

    @Test
    fun `detection finds the headless Service and skips the agent's decoy`() = runBlocking<Unit> {
        val services = handle.client.services().inNamespace("monitoring")
        assertEquals("true", services.withName("prometheus-operated").get().metadata.labels["operated-prometheus"])
        assertEquals("true", services.withName("prometheus-agent-operated").get().metadata.labels["operated-prometheus"], "the decoy must carry the finder's label")

        val state = PrometheusDetector().detect(handle.client)

        assertEquals(DetectionState.Found(operated), state)
    }

    @Test
    fun `the agent's decoy refuses a query, so the demo exercises the 422 as well as the skip`() = runBlocking<Unit> {
        val result = source(operated.copy(service = "prometheus-agent-operated"))
            .podSeries(PodSeriesKind.Cpu, seededPod(), HistoryRange.M15.window(System.currentTimeMillis() / 1000))

        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "execution"), result)
    }

    @Test
    fun `cpu, memory, network and throttling of a seeded pod are answered with points`() = runBlocking<Unit> {
        val pod = seededPod()
        val containerNames = pod.containers.map { it.name }.sorted()
        assertEquals(listOf("frontend"), containerNames)
        assertTrue(pod.containers.all { it.cpuLimitMillis != null }, "throttling series exist only for containers with a CPU limit")
        val window = HistoryRange.H1.window(System.currentTimeMillis() / 1000)
        val source = source()

        assertEquals("Prometheus · monitoring/prometheus-operated", source.label)
        for (kind in PodSeriesKind.entries) {
            val result = source.podSeries(kind, pod, window)

            assertIs<SeriesResult.Ok>(result, "$kind: $result")
            assertTrue(result.series.isNotEmpty(), "$kind has no series")
            assertTrue(result.series.all { it.points.isNotEmpty() }, "$kind has a series without points")
            val expectedNames = if (kind == PodSeriesKind.NetworkRx || kind == PodSeriesKind.NetworkTx) listOf("") else containerNames
            assertEquals(expectedNames, result.series.map { it.name }, "$kind")
            assertEquals((window.endEpochSec - window.startEpochSec) / window.stepSec + 1, result.series.first().points.size.toLong(), "$kind")
        }
    }

    @Test
    fun `a pod that is not in the cluster reads as no history, not as a failure`() = runBlocking<Unit> {
        val result = source().podSeries(PodSeriesKind.Cpu, PodRef("default", "gone-0"), HistoryRange.M15.window(System.currentTimeMillis() / 1000))

        assertEquals(SeriesResult.Ok(emptyList()), result)
    }

    @Test
    fun `a service the demo does not have is reported as gone`() = runBlocking<Unit> {
        val result = source(operated.copy(service = "prometheus-server")).podSeries(PodSeriesKind.Cpu, seededPod(), HistoryRange.M15.window(System.currentTimeMillis() / 1000))

        assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), result)
    }

    @Test
    fun `the release check helper reads CPU points from the demo`() {
        assertTrue(ReleaseCheckSupport.demoCpuHistoryPoints(handle.client) > 0)
    }

    // ── the app's wiring ────────────────────────────────────────────────────

    /**
     * A tab's connection manager attached to the demo instance, and the service built the way
     * ReactiveKubeClient builds it: the live context and the client both come from the manager.
     */
    private fun tabService(): Pair<KubeConnectionManager, MetricsHistoryService> {
        val tab = KubeConnectionManager()
        manager = tab
        // A second handle on the running instance, owned by the manager from here on.
        assertTrue(tab.connectWithMockHandle(MockClusterProvider.acquire(handle.label)).isSuccess)
        val service = MetricsHistoryService(
            currentContext = { tab.connectedContextOrNull() },
            settingsFlow = { MutableStateFlow<Map<String, MetricsSourceSetting>>(emptyMap()) },
            gmpSourceFor = { error("the demo is not a gke context") },
            detection = detection,
            clientFor = { context -> withContext(Dispatchers.IO) { tab.clientIfConnectedTo(context) } },
        )
        return tab to service
    }

    @Test
    fun `a tab connected to the demo has a client for the context it reports`() {
        val (tab, _) = tabService()

        val context = assertNotNull(tab.connectedContextOrNull())

        assertEquals(handle.label, context)
        assertTrue(DemoContext.isMockContext(context))
        assertSame(tab.client, tab.clientIfConnectedTo(context))
        assertNull(tab.clientIfConnectedTo("example-context"), "another cluster's name must not get the demo's client")
    }

    @Test
    fun `the demo resolves through detection to its Prometheus and reads history from it`() = runBlocking<Unit> {
        val (_, service) = tabService()
        assertEquals(SourceChoice.Detecting, service.choice())

        service.ensureDetected()

        val choice = service.choice()
        assertEquals(SourceChoice.InCluster(operated, auto = true), choice)
        val source = assertNotNull(service.sourceFor(choice))
        assertEquals("Prometheus · monitoring/prometheus-operated", source.label)
        val result = source.podSeries(PodSeriesKind.Memory, seededPod(), HistoryRange.M15.window(System.currentTimeMillis() / 1000))
        assertIs<SeriesResult.Ok>(result)
        assertEquals(listOf("frontend"), result.series.map { it.name })
        assertTrue(result.series.single().points.isNotEmpty())
    }

    @Test
    fun `turning the demo's history off wins over detection`() = runBlocking<Unit> {
        val (tab, _) = tabService()
        val off = MetricsHistoryService(
            currentContext = { tab.connectedContextOrNull() },
            settingsFlow = { MutableStateFlow(mapOf(DemoContext.MOCK_CONTEXT_NAME to MetricsSourceSetting(mode = MetricsSourceMode.OFF))) },
            gmpSourceFor = { error("the demo is not a gke context") },
            detection = detection,
            clientFor = { context -> withContext(Dispatchers.IO) { tab.clientIfConnectedTo(context) } },
        )

        assertEquals(SourceChoice.None(NoSourceReason.Disabled), off.choice())
    }
}
