package com.kubekubedashdash.util

import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ReactiveKubeClient's own metrics-history wiring against a tab connected to the demo: the live
 * context, the client lookup and the shared detection, with nothing swapped out but the data
 * directory. The shared detection is cleared around the test so nothing leaks into another class.
 */
class DemoPrometheusAppWiringTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var manager: KubeConnectionManager? = null
    private lateinit var reactive: ReactiveKubeClient
    private var previousCloudClis: String? = null

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
        InClusterDetection.shared.invalidate(DemoContext.MOCK_CONTEXT_NAME)
        val tab = KubeConnectionManager()
        manager = tab
        reactive = ReactiveKubeClient(scope, tab)
    }

    @AfterTest
    fun tearDown() {
        try {
            InClusterDetection.shared.invalidate(DemoContext.MOCK_CONTEXT_NAME)
            shutdownCleanly(scope, label = "DemoPrometheusAppWiringTest", manager = manager)
            // The tab's close released its handle; the instance goes with the last one.
            MockClusterProvider.forceShutdown()
        } finally {
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `a demo tab detects the demo's Prometheus and reads a seeded pod's history from it`() = runBlocking<Unit> {
        assertTrue(reactive.connectMock().isSuccess)
        MockClusterProvider.simulators().forEach { it.stop() }
        val history = reactive.metricsHistory
        assertEquals(SourceChoice.Detecting, history.choice())

        history.ensureDetected()

        val operated = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")
        val choice = history.choice()
        assertEquals(SourceChoice.InCluster(operated, auto = true), choice)
        val source = assertNotNull(history.sourceFor(choice))
        assertEquals("Prometheus · monitoring/prometheus-operated", source.label)
        val result = source.podSeries(PodSeriesKind.Cpu, PodRef("default", "frontend-7b9d5c8f4-abc12"), HistoryRange.M15.window(System.currentTimeMillis() / 1000))
        assertIs<SeriesResult.Ok>(result, "$result")
        assertTrue(result.series.isNotEmpty() && result.series.all { it.points.isNotEmpty() }, "the demo Prometheus answered no points")
    }
}
