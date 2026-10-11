package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.services.metricshistory.ClusterStub
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.K8S_FORBIDDEN
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.services.metricshistory.jsonResponse
import com.kubekubedashdash.services.metricshistory.matrixBody
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

private const val NOW = 86_400L * 100

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

private val InClusterSetting = MetricsSourceSetting(MetricsSourceMode.IN_CLUSTER, namespace = "monitoring", service = "prometheus-server", port = "80")

private val DetectedTarget = InClusterTarget("monitoring", "prometheus-server", "80", https = false, pathPrefix = "")

/** Every text in [this] node's subtree, in tree order. */
private fun SemanticsNode.allTexts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.allTexts() }

/** One query the fake source answered. */
private data class Call(val kind: PodSeriesKind, val pod: String, val window: QueryWindow)

/**
 * Answers every kind with two points; records what it was asked. [failure] answers every kind (or
 * [failKinds]) with that failure instead, and [empty] answers every kind with no series, as Managed
 * Prometheus does for a pod it has no samples of yet.
 */
private class FakeSource(
    private val failure: SeriesResult.Failed? = null,
    private val failKinds: Set<PodSeriesKind> = emptySet(),
    private val empty: Boolean = false,
) : MetricsHistorySource {
    override val label = "Fake source"
    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult {
        calls += Call(kind, pod.name, window)
        if (failure != null && (failKinds.isEmpty() || kind in failKinds)) return failure
        if (empty) return SeriesResult.Ok(emptyList())
        fun points() = listOf(SeriesPoint(window.startEpochSec, 1.0), SeriesPoint(window.endEpochSec, 2.0))
        return SeriesResult.Ok(
            when (kind) {
                PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx -> listOf(Series("", points()))
                else -> listOf(Series("app", points()), Series("sidecar", points()))
            },
        )
    }
}

/**
 * [PodMetricsHistorySection] over fake sources: the Settings flow is a plain [MutableStateFlow] handed to
 * the service's internal constructor, so nothing here reads the preference store, the network or gcloud.
 * `refreshMs = Long.MAX_VALUE` keeps the refresh loop from ever scheduling, except in the one case that
 * drives it with the test clock.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class PodMetricsHistorySectionUiTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    // Unconfined: a search whose lambda does not suspend finishes inside the call that starts it, so
    // the tests need no sleeping to see its answer.
    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = AtomicLong(1_000_000)

    // Only the tests that go through the real in-cluster source need the loopback API server.
    private val stubDelegate = lazy { ClusterStub() }
    private val stub by stubDelegate
    private val stubClient: KubernetesClient by lazy { stub.client() }

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
    }

    @AfterTest
    fun restore() {
        try {
            shutdownCleanly(detectionScope, label = "PodMetricsHistorySectionUiTest")
            if (stubDelegate.isInitialized()) stub.close("PodMetricsHistorySectionUiTest")
        } finally {
            ThemeManager.syncStyleFromPreferences(originalStyle)
            // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
            // mode put it, so restore the flag first through the matching explicit mode.
            ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
            ThemeManager.syncFromPreferences(originalMode)
        }
    }

    private fun settingsOf(setting: MetricsSourceSetting) = MutableStateFlow(mapOf(DemoContext.preferenceKey(CONTEXT) to setting))

    /** A detection whose search is [detect]; it runs in the test-owned scope and reads the test clock. */
    private fun detectionOf(detect: suspend (KubernetesClient) -> DetectionState = { DetectionState.NothingFound(clock.get()) }) = InClusterDetection(scope = detectionScope, nowMs = clock::get, detect = detect)

    private fun serviceOver(
        settings: MutableStateFlow<Map<String, MetricsSourceSetting>>,
        source: MetricsHistorySource,
        detection: InClusterDetection = detectionOf(),
        clientFor: suspend (String) -> KubernetesClient? = { dummyClient() },
    ) = MetricsHistoryService(
        currentContext = { CONTEXT },
        settingsFlow = { settings },
        gmpSourceFor = { source },
        detection = detection,
        clientFor = clientFor,
        nowMs = clock::get,
    )

    private fun podNamed(
        name: String = "web-0",
        resources: List<ContainerResources> = listOf(ContainerResources("app", cpuLimitMillis = 500), ContainerResources("sidecar")),
        hostNetwork: Boolean = false,
        uid: String = "uid-$name",
    ) = PodInfo(
        uid = uid,
        name = name,
        namespace = "team-a",
        status = "Running",
        ready = "2/2",
        restarts = 0,
        age = "1h",
        node = "node-a",
        ip = "",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
        resources = resources,
        hostNetwork = hostNetwork,
    )

    private val withoutLimit = listOf(ContainerResources("app"), ContainerResources("sidecar"))

    private val liveSnapshots = listOf(PodMetricsSnapshot(1_000, 100, 1_048_576), PodMetricsSnapshot(2_000, 200, 2_097_152))

    @Test
    fun `a pod with a CPU limit shows four charts with one legend entry per container`() = runComposeUiTest {
        val source = FakeSource()
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("Fake source").assertExists()
        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onNodeWithText(it).assertExists() }
        // CPU, Memory and throttling each list both containers; the network chart lists Received and Sent.
        onAllNodesWithText("app").assertCountEquals(3)
        onAllNodesWithText("sidecar").assertCountEquals(3)
        onNodeWithText("Received").assertExists()
        onNodeWithText("Sent").assertExists()
        assertEquals(PodSeriesKind.entries.toSet(), source.calls.map { it.kind }.toSet())
    }

    @Test
    fun `a pod without a CPU limit explains why throttling is empty and does not ask for it`() = runComposeUiTest {
        val source = FakeSource()
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(resources = withoutLimit), service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("CPU throttling").assertExists()
        onNodeWithText(NO_CPU_LIMIT_NOTE).assertExists()
        assertTrue(source.calls.none { it.kind == PodSeriesKind.Throttling }, "throttling must not be queried without a CPU limit")
        onAllNodesWithText("app").assertCountEquals(2)
    }

    @Test
    fun `a host-network pod explains why network is empty and does not ask for it`() = runComposeUiTest {
        val source = FakeSource()
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(hostNetwork = true), service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("Network").assertExists()
        onNodeWithText(HOST_NETWORK_NOTE).assertExists()
        assertTrue(
            source.calls.none { it.kind == PodSeriesKind.NetworkRx || it.kind == PodSeriesKind.NetworkTx },
            "network must not be queried for a host-network pod",
        )
        onNodeWithText("Received").assertDoesNotExist()
    }

    @Test
    fun `switching the setting off falls back to the live charts under a hint`() = runComposeUiTest {
        val source = FakeSource()
        val settings = settingsOf(GmpSetting)
        val service = serviceOver(settings, source)
        var live by mutableStateOf(emptyList<PodMetricsSnapshot>())
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, live, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        onNodeWithText("CPU throttling").assertExists()

        settings.value = mapOf(DemoContext.preferenceKey(CONTEXT) to MetricsSourceSetting(MetricsSourceMode.OFF))
        live = liveSnapshots
        waitForIdle()

        onNodeWithText("metrics history is off for this cluster", substring = true).assertExists()
        onNodeWithText("CPU throttling").assertDoesNotExist()
        onNodeWithText("Fake source").assertDoesNotExist()
        // The metrics-server charts stand in: one "Min: … · Max: …" readout each for CPU and memory.
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(2)
    }

    @Test
    fun `with no source and no live data the section renders nothing`() = runComposeUiTest {
        val settings = settingsOf(MetricsSourceSetting(MetricsSourceMode.OFF))
        val service = serviceOver(settings, FakeSource())
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("Resource Usage").assertDoesNotExist()
        onNodeWithText("metrics history is off for this cluster", substring = true).assertDoesNotExist()
    }

    @Test
    fun `when CPU and memory both fail the reason is shown above the live charts`() = runComposeUiTest {
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"), failKinds = setOf(PodSeriesKind.Cpu, PodSeriesKind.Memory))
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("Managed Prometheus didn't answer (HTTP 503)", substring = true).assertExists()
        onNodeWithText("CPU throttling").assertDoesNotExist()
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(2)
        // One title: the live charts follow the header without their own.
        onAllNodesWithText("Resource Usage", ignoreCase = true).assertCountEquals(1)
        // The header stays, so a range that keeps failing can still be changed.
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("1h ▾").assertExists()
    }

    @Test
    fun `a source that throws fails its chart without ending the section or showing the message`() = runComposeUiTest {
        val source = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult {
                if (kind == PodSeriesKind.Cpu) throw IllegalArgumentException("invalid header value: \"Bearer fake-token-1\"")
                return SeriesResult.Ok(listOf(Series("app", listOf(SeriesPoint(window.startEpochSec, 1.0), SeriesPoint(window.endEpochSec, 2.0)))))
            }
        }
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("Not answering (error)").assertExists()
        onNodeWithText("Memory").assertExists()
        onAllNodesWithText("fake-token", substring = true).assertCountEquals(0)
    }

    @Test
    fun `when only the network fails its own chart says so and the others still draw`() = runComposeUiTest {
        val source = FakeSource(
            failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"),
            failKinds = setOf(PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx),
        )
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText("Not answering (HTTP 503)").assertExists()
        onAllNodesWithText("app").assertCountEquals(3)
    }

    @Test
    fun `choosing another range asks again with that window`() = runComposeUiTest {
        val source = FakeSource()
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        // The default range is one hour: 30 s steps.
        assertEquals(setOf(3_600L to 30L), source.calls.map { it.window.endEpochSec - it.window.startEpochSec to it.window.stepSec }.toSet())

        onNodeWithText("1h ▾").performClick()
        onNodeWithText("6h").performClick()
        waitForIdle()

        val last = source.calls.last().window
        assertEquals(21_600L to 180L, last.endEpochSec - last.startEpochSec to last.stepSec)
        onNodeWithText("6h ▾").assertExists()
    }

    @Test
    fun `switching to another pod asks about that pod`() = runComposeUiTest {
        val source = FakeSource()
        val service = serviceOver(settingsOf(GmpSetting), source)
        // Demo pods can carry a blank uid, so the name alone has to tell the two apart.
        var pod by mutableStateOf(podNamed("web-0", uid = ""))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(pod, service, emptyList(), { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        assertEquals(setOf("web-0"), source.calls.map { it.pod }.toSet())

        pod = podNamed("web-1", uid = "")
        waitForIdle()

        assertTrue(source.calls.any { it.pod == "web-1" }, "the new pod must be queried: ${source.calls.map { it.pod }}")
        onNodeWithText("CPU").assertExists()
    }

    @Test
    fun `a refresh of an identical, fully answered window asks nothing`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource()
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, 30_000L) }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val first = source.calls.size
        assertTrue(first >= 5, "the first round asks for every kind, got $first")

        mainClock.advanceTimeBy(30_001)
        waitForIdle()
        assertEquals(first, source.calls.size, "the same window, all answered: nothing to refetch")
        onNodeWithText("CPU throttling").assertExists()
    }

    @Test
    fun `a refresh repeats only what has not fully answered`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(
            failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"),
            failKinds = setOf(PodSeriesKind.NetworkRx),
        )
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, 30_000L) }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val first = source.calls.size
        assertTrue(first >= 5, "the first round asks for every kind, got $first")

        // Network failed, so the next round refetches: same window, but not fully answered.
        mainClock.advanceTimeBy(30_001)
        waitForIdle()
        assertEquals(first * 2, source.calls.size, "a round with a failure is repeated whole")
    }

    @Test
    fun `Auto on a context that is not a gke name shows the live charts under the loading line, searches once and asks no source`() = runComposeUiTest {
        // CONTEXT is not a gke_ name and nothing is stored: Auto is still looking for a Prometheus.
        val source = FakeSource()
        val searches = AtomicInteger()
        val gate = CompletableDeferred<DetectionState>()
        val service = serviceOver(
            MutableStateFlow(emptyMap()),
            source,
            detectionOf {
                searches.incrementAndGet()
                gate.await()
            },
        )
        var live by mutableStateOf(liveSnapshots)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, live, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(2)
        onAllNodesWithText("Resource Usage", ignoreCase = true).assertCountEquals(1)
        assertEquals(1, searches.get(), "the panel starts the search when it shows")

        // More recompositions while the search is out do not start another.
        live = liveSnapshots + PodMetricsSnapshot(3_000, 300, 3_145_728)
        waitForIdle()
        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        assertEquals(1, searches.get(), "a recomposition must not search again")
        assertTrue(source.calls.isEmpty(), "no history source may be asked: ${source.calls}")
    }

    @Test
    fun `Auto that finds nothing draws exactly the section from before metrics history`() = runComposeUiTest {
        val source = FakeSource()
        val searches = AtomicInteger()
        val service = serviceOver(
            MutableStateFlow(emptyMap()),
            source,
            detectionOf {
                searches.incrementAndGet()
                DetectionState.NothingFound(clock.get())
            },
        )
        var live by mutableStateOf(liveSnapshots)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) {
                    Box(Modifier.testTag("now")) { PodMetricsHistorySection(podNamed(), service, live, { NOW }, Long.MAX_VALUE) }
                    Box(Modifier.testTag("before")) { PodMetricsSection(live) }
                }
            }
        }
        waitForIdle()

        fun texts(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().allTexts()
        assertTrue(texts("now").isNotEmpty(), "the live charts must render")
        assertEquals(texts("before"), texts("now"))
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
        assertEquals(1, searches.get())

        // The answer is kept: new live data and a later frame do not search again.
        live = liveSnapshots + PodMetricsSnapshot(3_000, 300, 3_145_728)
        waitForIdle()
        assertEquals(texts("before"), texts("now"))
        assertEquals(1, searches.get(), "a recomposition must not search again")
        assertTrue(source.calls.isEmpty(), "no history source may be asked: ${source.calls}")
    }

    @Test
    fun `a search that cannot list or failed also leaves the section as it was before metrics history`() = runComposeUiTest {
        listOf(DetectionState.CannotSearch(clock.get()), DetectionState.SearchFailed(clock.get())).forEach { outcome ->
            val service = serviceOver(MutableStateFlow(emptyMap()), FakeSource(), detectionOf { outcome })
            setContent {
                MaterialTheme(typography = kdTypography()) {
                    Column(Modifier.width(640.dp)) {
                        Box(Modifier.testTag("now")) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
                        Box(Modifier.testTag("before")) { PodMetricsSection(liveSnapshots) }
                    }
                }
            }
            waitForIdle()

            val now = onNodeWithTag("now", useUnmergedTree = true).fetchSemanticsNode().allTexts()
            assertTrue(now.isNotEmpty(), "the live charts must render after $outcome")
            assertEquals(onNodeWithTag("before", useUnmergedTree = true).fetchSemanticsNode().allTexts(), now, "$outcome")
        }
    }

    @Test
    fun `a failed search is tried again only once its minute is over and the pod shows again`() = runComposeUiTest {
        val searches = AtomicInteger()
        val service = serviceOver(
            MutableStateFlow(emptyMap()),
            FakeSource(),
            detectionOf {
                searches.incrementAndGet()
                DetectionState.SearchFailed(clock.get())
            },
        )
        var pod by mutableStateOf(podNamed("web-0"))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(pod, service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        assertEquals(1, searches.get())

        // Within the minute another pod finds the failure still standing.
        clock.addAndGet(InClusterDetection.FAILED_TTL_MS)
        pod = podNamed("web-1")
        waitForIdle()
        assertEquals(1, searches.get(), "the failure had not yet expired")

        clock.addAndGet(1)
        pod = podNamed("web-2")
        waitForIdle()
        assertEquals(2, searches.get(), "the expired failure is searched again")
    }

    @Test
    fun `a Prometheus found by the search flips the panel to its history`() = runComposeUiTest {
        val gate = CompletableDeferred<DetectionState>()
        stub.proxy = { jsonResponse(200, matrixBody("app" to listOf(NOW - 60 to "1", NOW to "2"))) }
        val service = serviceOver(MutableStateFlow(emptyMap()), FakeSource(), detectionOf { gate.await() }, clientFor = { stubClient })
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        assertTrue(stub.proxyRequests.isEmpty(), "nothing is asked of a Prometheus before one is found")

        gate.complete(DetectionState.Found(DetectedTarget))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("CPU throttling").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Prometheus · monitoring/prometheus-server").assertExists()
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
        val request = stub.proxyRequests.first()
        assertEquals("/api/v1/namespaces/monitoring/services/prometheus-server:80/proxy/api/v1/query_range", request.path.substringBefore('?'))
    }

    @Test
    fun `a role without services proxy access sees the reason above the live charts`() = runComposeUiTest {
        stub.proxy = { jsonResponse(403, K8S_FORBIDDEN) }
        stub.review = { ClusterStub.reviewResponse(allowed = false) }
        val service = serviceOver(settingsOf(InClusterSetting), FakeSource(), clientFor = { stubClient })
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("it needs get on services/proxy in namespace monitoring", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("(granted by edit or admin, not view). Showing live metrics-server data.", substring = true).assertExists()
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(2)
        onAllNodesWithText("Resource Usage", ignoreCase = true).assertCountEquals(1)
        onNodeWithText("CPU throttling").assertDoesNotExist()
        onNodeWithText("Prometheus · monitoring/prometheus-server").assertExists()
        onNodeWithText("1h ▾").assertExists()
    }

    @Test
    fun `while the first fetch is out the live charts show under a loading line`() = runComposeUiTest {
        val hanging = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = awaitCancellation()
        }
        val service = serviceOver(settingsOf(GmpSetting), hanging)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(2)
        onAllNodesWithText("Resource Usage", ignoreCase = true).assertCountEquals(1)
        // The range can still be changed while it loads.
        onNodeWithText("1h ▾").assertExists()
        onNodeWithText("CPU throttling").assertDoesNotExist()
    }

    @Test
    fun `when CPU and memory answer with no point the live charts stand in`() = runComposeUiTest {
        val service = serviceOver(settingsOf(GmpSetting), FakeSource(empty = true))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, liveSnapshots, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()

        onNodeWithText(NO_HISTORY_NOTE).assertExists()
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(2)
        onNodeWithText("CPU throttling").assertDoesNotExist()
        onNodeWithText("No data in this range").assertDoesNotExist()
        onNodeWithText("1h ▾").assertExists()
    }

    @Test
    fun `an answer with no point is asked again at the next refresh`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(empty = true)
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { PodMetricsHistorySection(podNamed(), service, emptyList(), { NOW }, 30_000L) }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val first = source.calls.size
        assertTrue(first >= 5, "the first round asks for every kind, got $first")

        // Same window and every kind answered Ok, but nothing to draw: a new pod's points may have arrived.
        mainClock.advanceTimeBy(30_001)
        waitForIdle()
        assertEquals(first * 2, source.calls.size, "an empty round is repeated")
    }
}
