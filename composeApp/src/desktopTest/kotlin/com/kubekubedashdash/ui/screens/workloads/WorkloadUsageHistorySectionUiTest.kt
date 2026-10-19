package com.kubekubedashdash.ui.screens.workloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.GmpTarget
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.components.CHART_LOADING_TEXT
import com.kubekubedashdash.ui.components.CHART_SKELETON_TAG
import com.kubekubedashdash.ui.components.ChartMarker
import com.kubekubedashdash.ui.components.podSetHue
import com.kubekubedashdash.ui.screens.pods.LoadedHistory
import com.kubekubedashdash.ui.screens.pods.needsFetch
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

// Not a multiple of any chart step, so a window clamped to it is visibly aligned down.
private const val NOW = 8_640_017L

private const val REFRESH_MS = 30_000L

private const val NAMESPACE = "team-a"

private const val JOB = "nightly-backup"

private const val DRIVER = "spark-pi-success-driver"
private const val EXECUTOR_1 = "spark-pi-success-exec-1"
private const val EXECUTOR_2 = "spark-pi-success-exec-2"

private const val GMP_SHORT_RUN_NOTE =
    "This run is shorter than the 5-minute window CPU is averaged over, so its CPU line is spread out and below the real peak."

/** A chart legend entry that counts the grey lines ("3 earlier pods"), unlike prose that happens to say it. */
private val earlierPodsEntry = SemanticsMatcher("is an earlier-pods legend entry") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("""\d+ earlier pods?""").matches(it.text) }
}

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

private val GmpChoice = SourceChoice.Gmp(GmpTarget("example-project", "us-central1", "example-cluster"), auto = false)

private val InClusterChoice = SourceChoice.InCluster(InClusterTarget("monitoring", "frontend", "80", https = false, pathPrefix = ""), auto = false)

private val Limited = listOf(ContainerResources("main", cpuRequestMillis = 100, cpuLimitMillis = 500, memoryRequestBytes = 64L shl 20, memoryLimitBytes = 128L shl 20))

private val JobPods = listOf("x7k2p", "92f6w", "h4m8q").map { "$JOB-$it" }

private val JobPodSet = PromQl.ofJob(NAMESPACE, JOB, indexed = false)!!

private val IndexedJobPodSet = PromQl.ofJob(NAMESPACE, JOB, indexed = true)!!

private val SparkPodSet = PromQl.ofSparkPods(NAMESPACE, listOf(DRIVER), listOf(EXECUTOR_1, EXECUTOR_2))!!

private val DriverRef = PromQl.ofExactPods(NAMESPACE, listOf(DRIVER))!!

private val ExecutorsRef = PromQl.ofSparkExecutors(NAMESPACE, listOf(EXECUTOR_1, EXECUTOR_2))!!

/** One query the fake source answered. */
private data class Call(val kind: PodSeriesKind, val mode: PodSetMode, val pods: PodSetRef, val window: QueryWindow)

/**
 * Answers a per-pod query with one series for each of [podNames], the later ones with the higher peak,
 * and a total with one unnamed series; records what it was asked. [failure] answers every kind with that
 * failure and [empty] with no series; both can be changed between refreshes. [failFor] answers a failure for
 * that pod set only, for the kinds in [failForKinds] (null: every kind). While [gate] is set, an answer is
 * held back until it completes.
 */
private class FakeSource(
    @Volatile var podNames: List<String> = emptyList(),
    @Volatile var failure: SeriesResult.Failed? = null,
    @Volatile var empty: Boolean = false,
    @Volatile var failFor: PodSetRef? = null,
    @Volatile var failForKinds: Set<PodSeriesKind>? = null,
    override val label: String = "Fake source",
) : MetricsHistorySource {
    val calls = CopyOnWriteArrayList<Call>()

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    override suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult {
        calls += Call(kind, mode, pods, window)
        gate?.await()
        failFor?.let { if (it == pods && failForKinds?.contains(kind) != false) return SeriesResult.Failed(HistoryFailure.Unavailable, "ref") }
        failure?.let { return it }
        if (empty) return SeriesResult.Ok(emptyList())

        fun points(peak: Double) = listOf(SeriesPoint(window.startEpochSec, 1.0), SeriesPoint(window.endEpochSec, peak))
        return SeriesResult.Ok(
            when (mode) {
                PodSetMode.Total -> listOf(Series("", points(2.0)))
                PodSetMode.PerPod -> podNames.mapIndexed { index, name -> Series(name, points(2.0 + index)) }
            },
        )
    }

    /** The windows of the queries made so far, one entry per round (CPU is asked in every round). */
    val cpuWindows get() = calls.filter { it.kind == PodSeriesKind.Cpu }.map { it.window }
}

private fun pod(name: String, resources: List<ContainerResources> = Limited, age: String = "1h") = PodInfo(
    uid = "uid-$name",
    name = name,
    namespace = NAMESPACE,
    status = "Running",
    ready = "1/1",
    restarts = 0,
    age = age,
    node = "node-a",
    ip = "",
    labels = emptyMap(),
    annotations = emptyMap(),
    containers = emptyList(),
    resources = resources,
)

private fun targetOf(
    window: UsageWindow,
    grouping: UsageGrouping = UsageGrouping.PerPod(null),
    podSet: PodSetRef? = JobPodSet,
    currentPods: List<PodInfo> = emptyList(),
    notes: List<String> = emptyList(),
    markers: List<ChartMarker> = emptyList(),
    runLabel: String = if (window is UsageWindow.Runs) "Runs" else "Run",
    key: String = "uid-job-1",
) = UsageTarget(
    key = key,
    podSet = podSet,
    missingNote = "No pods can be named for this workload.",
    grouping = grouping,
    defaultWindow = window,
    markers = markers,
    currentPods = currentPods,
    notes = notes,
    runLabel = runLabel,
    perPodDescription = "One line per pod.",
)

/**
 * [WorkloadUsageHistorySection] over fake sources: the Settings flow is a plain [MutableStateFlow] handed to
 * the service's internal constructor, so nothing here reads the preference store, the network or gcloud.
 * `refreshMs = Long.MAX_VALUE` keeps the refresh loop from ever scheduling, except in the cases that drive
 * it with the test clock and a clock of their own for the section.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class WorkloadUsageHistorySectionUiTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    // Unconfined: a search whose lambda does not suspend finishes inside the call that starts it.
    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val detectionClock = AtomicLong(1_000_000)

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
            shutdownCleanly(detectionScope, label = "WorkloadUsageHistorySectionUiTest")
        } finally {
            ThemeManager.syncStyleFromPreferences(originalStyle)
            // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
            // mode put it, so restore the flag first through the matching explicit mode.
            ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
            ThemeManager.syncFromPreferences(originalMode)
        }
    }

    private fun serviceOver(source: MetricsHistorySource) = MetricsHistoryService(
        currentContext = { CONTEXT },
        settingsFlow = { MutableStateFlow(mapOf(DemoContext.preferenceKey(CONTEXT) to GmpSetting)) },
        gmpSourceFor = { source },
        detection = InClusterDetection(scope = detectionScope, nowMs = detectionClock::get, detect = { DetectionState.NothingFound(detectionClock.get()) }),
        clientFor = { dummyClient() },
        nowMs = detectionClock::get,
    )

    private fun ComposeUiTest.show(
        source: MetricsHistorySource,
        target: UsageTarget,
        refreshMs: Long = Long.MAX_VALUE,
        nowEpochSec: () -> Long = { NOW },
        width: Dp = 640.dp,
    ) {
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(width)) { WorkloadUsageHistorySection(target, service, nowEpochSec, refreshMs) }
            }
        }
        settle()
    }

    /** [show] for a target that changes while the section is composed: [target] is read in composition. */
    private fun ComposeUiTest.showChanging(source: MetricsHistorySource, target: () -> UsageTarget) {
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(target(), service, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
    }

    // Under a paused test clock a frame composes the content and a second one the answer the effect fetched.
    private fun ComposeUiTest.settle() {
        waitForIdle()
        if (!mainClock.autoAdvance) {
            repeat(2) {
                mainClock.advanceTimeByFrame()
                waitForIdle()
            }
        }
    }

    /** One refresh period of the section's loop under a paused test clock. */
    private fun ComposeUiTest.refresh() {
        mainClock.advanceTimeBy(REFRESH_MS + 1)
        settle()
    }

    private val Call.pair get() = kind to mode

    // ── the run window ──────────────────────────────────────────────────────

    @Test
    fun `a finished run is charted from its start to its end with a margin, under its own menu entry`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))

        val run = QueryWindow.spanning(NOW - 7_200, NOW - 3_600)
        assertTrue(run.endEpochSec <= NOW, "the margin of a run that ended an hour ago is past")
        assertEquals(setOf(run), source.calls.map { it.window }.toSet())
        assertEquals(setOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx), source.calls.map { it.kind }.toSet())
        assertEquals(setOf(JobPodSet), source.calls.map { it.pods }.toSet())
        onNodeWithText("Run ▾").assertExists()
        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText("Fake source").assertExists()
        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onNodeWithText(it).assertExists() }
    }

    @Test
    fun `the window menu lists the run first and then the fixed ranges, and choosing one asks again with its window`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))
        val run = QueryWindow.spanning(NOW - 7_200, NOW - 3_600)

        onNodeWithText("Run ▾").performClick()
        onNodeWithText("Run").assertExists()
        HistoryRange.entries.forEach { onNodeWithText(it.label).assertExists() }
        onNodeWithText("7d").assertExists()
        onNodeWithText("1h").performClick()
        waitForIdle()

        onNodeWithText("1h ▾").assertExists()
        assertEquals(HistoryRange.H1.window(NOW), source.calls.last().window)
        assertEquals(8, source.calls.size)

        onNodeWithText("1h ▾").performClick()
        onNodeWithText("Run").performClick()
        waitForIdle()

        onNodeWithText("Run ▾").assertExists()
        assertEquals(run, source.calls.last().window)
        assertEquals(12, source.calls.size)
    }

    @Test
    fun `a sliding default lists the fixed ranges only, as the Deployment section does`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        show(source, targetOf(UsageWindow.Sliding(HistoryRange.H6), runLabel = ""))

        onNodeWithText("6h ▾").performClick()
        onNodeWithText("15m").assertExists()
        onNodeWithText("7d").assertExists()
        onNodeWithText("Run").assertDoesNotExist()
        onNodeWithText("24h").performClick()
        waitForIdle()

        onNodeWithText("24h ▾").assertExists()
        assertEquals(HistoryRange.H24.window(NOW), source.calls.last().window)
    }

    @Test
    fun `a run that is still going ends at the present, aligned down to its step`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        val start = NOW - 600
        show(source, targetOf(UsageWindow.Run(start, null)))

        val spanning = QueryWindow.spanning(start, NOW)
        assertTrue(spanning.endEpochSec > NOW, "the margin reaches past the present")
        assertEquals(setOf(spanning.copy(endEpochSec = NOW - NOW % spanning.stepSec)), source.calls.map { it.window }.toSet())
        assertTrue(source.calls.first().window.endEpochSec <= NOW)
    }

    @Test
    fun `the runs of a scheduled workload are charted from the oldest run to the present, under Runs`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        val start = NOW - 86_400
        show(source, targetOf(UsageWindow.Runs(start)))

        val spanning = QueryWindow.spanning(start, NOW)
        assertEquals(setOf(spanning.copy(endEpochSec = NOW - NOW % spanning.stepSec)), source.calls.map { it.window }.toSet())
        onNodeWithText("Runs ▾").performClick()
        onNodeWithText("Runs").assertExists()
        onNodeWithText("Run").assertDoesNotExist()
    }

    @Test
    fun `a target without a pod pattern says why and asks nothing`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), podSet = null))

        onNodeWithText("No pods can be named for this workload.").assertExists()
        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText("Run ▾").assertExists()
        onNodeWithText("CPU").assertDoesNotExist()
        assertTrue(source.calls.isEmpty(), "no query without a pod set: ${source.calls}")
    }

    // ── a finished window is final ──────────────────────────────────────────

    @Test
    fun `a run that ended moments ago is asked again on the next refresh, as its window still moves`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(JobPods)
        var now = NOW
        // 10 s after the run's end the 60 s margin of its window reaches 50 s past the present.
        val run = UsageWindow.Run(NOW - 1_000, NOW - 10)
        show(source, targetOf(run), REFRESH_MS, { now })

        val first = source.cpuWindows.single()
        assertEquals(4, source.calls.size)
        assertTrue(QueryWindow.spanning(NOW - 1_000, NOW - 10).endEpochSec > NOW)
        assertEquals(NOW - NOW % first.stepSec, first.endEpochSec)

        now += first.stepSec
        refresh()

        assertEquals(8, source.calls.size)
        assertEquals(listOf(first, first.copy(endEpochSec = first.endEpochSec + first.stepSec)), source.cpuWindows)
    }

    @Test
    fun `once the window has stopped moving an empty answer is asked for once, not at every refresh`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(empty = true)
        val start = NOW - 7_200
        val end = NOW - 3_600
        val final = QueryWindow.spanning(start, end)
        // The run's end plus its margin is past, even by the clock of the first refresh.
        var now = final.endEpochSec + 1
        show(source, targetOf(UsageWindow.Run(start, end)), REFRESH_MS, { now })

        assertEquals(4, source.calls.size)
        repeat(3) {
            now += 30
            refresh()
        }

        assertEquals(4, source.calls.size, "an empty answer of a final window is the answer: ${source.calls.size} queries")
        assertEquals(setOf(final), source.calls.map { it.window }.toSet())
        onAllNodesWithText("No data in this range").assertCountEquals(3)
    }

    @Test
    fun `a sliding window asks again for an empty answer at every refresh`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(empty = true)
        show(source, targetOf(UsageWindow.Sliding(HistoryRange.H6), runLabel = ""), REFRESH_MS)

        repeat(3) { refresh() }

        assertEquals(16, source.calls.size)
    }

    @Test
    fun `a window that lies ahead of the clock is not final, so an empty answer is asked for again`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(empty = true)
        // A run that starts after this machine's clock: skew between the API server's time and ours.
        show(source, targetOf(UsageWindow.Run(NOW + 1_000, null)), REFRESH_MS)

        repeat(3) { refresh() }

        assertEquals(16, source.calls.size)
        assertTrue(source.cpuWindows.all { it.endEpochSec > it.startEpochSec }, "a window never ends before it starts: ${source.cpuWindows}")
    }

    @Test
    fun `a failure of a final window is asked for again at every refresh, and the next good answer is kept`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(JobPods, failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"))
        val start = NOW - 7_200
        val end = NOW - 3_600
        show(source, targetOf(UsageWindow.Run(start, end)), REFRESH_MS)

        assertEquals(4, source.calls.size)
        onNodeWithText("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s.").assertExists()

        refresh()
        assertEquals(8, source.calls.size)

        source.failure = null
        refresh()
        assertEquals(12, source.calls.size)
        onNodeWithText("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s.").assertDoesNotExist()
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }

        repeat(2) { refresh() }
        assertEquals(12, source.calls.size, "the good answer of a final window is kept")
        assertEquals(setOf(QueryWindow.spanning(start, end)), source.calls.map { it.window }.toSet())
    }

    @Test
    fun `a run that gains its end restarts the fetch with its final window, which is then kept`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(JobPods)
        val start = NOW - 3_600
        val end = NOW - 3_000
        var shown by mutableStateOf(targetOf(UsageWindow.Run(start, null)))
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(shown, service, { NOW }, REFRESH_MS) }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()

        val running = QueryWindow.spanning(start, NOW)
        assertEquals(4, source.calls.size)
        assertEquals(running.copy(endEpochSec = NOW - NOW % running.stepSec), source.cpuWindows.single())

        shown = shown.copy(defaultWindow = UsageWindow.Run(start, end))
        mainClock.advanceTimeByFrame()
        waitForIdle()

        val final = QueryWindow.spanning(start, end)
        assertTrue(final.endEpochSec <= NOW)
        assertEquals(8, source.calls.size)
        assertEquals(final, source.cpuWindows.last())

        repeat(3) { refresh() }
        assertEquals(8, source.calls.size, "the final window is not asked for again")
    }

    @Test
    fun `a run window that arrives after the first composition replaces the fallback range`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(JobPods)
        // The panel opens before the CronJob's Jobs are loaded: no run yet, so the fallback range.
        var shown by mutableStateOf(targetOf(UsageWindow.Sliding(HistoryRange.H24), runLabel = "Runs"))
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(shown, service, { NOW }, REFRESH_MS) }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("24h ▾").assertExists()

        val start = NOW - 2_700
        shown = shown.copy(defaultWindow = UsageWindow.Runs(start))
        mainClock.advanceTimeByFrame()
        waitForIdle()

        onNodeWithText("Runs ▾").assertExists()
        val runs = QueryWindow.spanning(start, NOW)
        assertEquals(runs.copy(endEpochSec = NOW - NOW % runs.stepSec), source.cpuWindows.last())
    }

    // ── state keys ──────────────────────────────────────────────────────────

    @Test
    fun `a target rebuilt with other live pods that change nothing the section asks leaves the fetch alone`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        var shown by mutableStateOf(targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), currentPods = listOf(pod("$JOB-x7k2p"))))
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(shown, service, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        assertEquals(5, source.calls.size)

        // The informer re-emits the pod with another age: the list is another value, the kinds are the same.
        shown = shown.copy(currentPods = listOf(pod("$JOB-x7k2p", age = "2h")))
        waitForIdle()

        assertEquals(5, source.calls.size)
        // The charts stay: the answer is not dropped for a loading line.
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        onNodeWithText("CPU throttling").assertExists()
    }

    @Test
    fun `live pod updates do not postpone the next refresh`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(JobPods)
        var now = NOW
        var shown by mutableStateOf(targetOf(UsageWindow.Run(NOW - 1_000, NOW - 10), currentPods = listOf(pod("$JOB-x7k2p"))))
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(shown, service, { now }, REFRESH_MS) }
            }
        }
        settle()
        assertEquals(5, source.calls.size)

        // An informer tick in the middle of the period: a restarted timer would put the refresh off by 20 s.
        mainClock.advanceTimeBy(20_000)
        shown = shown.copy(currentPods = listOf(pod("$JOB-x7k2p", age = "2h")))
        settle()
        now += 30
        mainClock.advanceTimeBy(20_000)
        settle()

        assertEquals(10, source.calls.size)
    }

    @Test
    fun `another workload starts over with its own window`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        var shown by mutableStateOf(targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(shown, service, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
        onNodeWithText("Run ▾").performClick()
        onNodeWithText("6h").performClick()
        waitForIdle()
        onNodeWithText("6h ▾").assertExists()

        shown = shown.copy(key = "uid-job-2", defaultWindow = UsageWindow.Run(NOW - 1_800, NOW - 900))
        waitForIdle()

        onNodeWithText("Run ▾").assertExists()
        assertEquals(QueryWindow.spanning(NOW - 1_800, NOW - 900), source.calls.last().window)
    }

    // ── a changed query keeps the charts of the last answer ─────────────────

    @Test
    fun `a pod set that changes keeps the charts drawn until the new answer arrives`() = runComposeUiTest {
        val source = FakeSource(JobPods.take(2))
        var shown by mutableStateOf(targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))
        showChanging(source) { shown }
        JobPods.take(2).forEach { onAllNodesWithText(it).assertCountEquals(2) }
        assertEquals(4, source.calls.size)

        // A pod appears: the pattern changes, and the second answer is held back.
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        source.podNames = JobPods
        shown = shown.copy(podSet = IndexedJobPodSet)
        waitForIdle()

        assertEquals(8, source.calls.size)
        assertEquals(setOf(IndexedJobPodSet), source.calls.drop(4).map { it.pods }.toSet())
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        JobPods.take(2).forEach { onAllNodesWithText(it).assertCountEquals(2) }
        onAllNodesWithText(JobPods.last()).assertCountEquals(0)

        gate.complete(Unit)
        waitForIdle()

        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }
        assertEquals(8, source.calls.size, "one more round, not a refetch loop")
    }

    @Test
    fun `a Spark application that gains an executor keeps the charts drawn, and then asks for the new pod set`() = runComposeUiTest {
        val source = FakeSource(listOf(DRIVER, EXECUTOR_1))
        val oneExecutor = PromQl.ofSparkPods(NAMESPACE, listOf(DRIVER), listOf(EXECUTOR_1))!!
        assertNotEquals(SparkPodSet, oneExecutor)
        // The driver and executors refs change with the pod set, so the grouping has to restart the fetch too.
        val oneExecutorRef = PromQl.ofSparkExecutors(NAMESPACE, listOf(EXECUTOR_1))!!
        assertNotEquals(ExecutorsRef, oneExecutorRef)
        var shown by mutableStateOf(sparkTarget().copy(podSet = oneExecutor, grouping = UsageGrouping.DriverExecutors(DriverRef, oneExecutorRef)))
        showChanging(source) { shown }
        onAllNodesWithText("Driver").assertCountEquals(3)
        assertEquals(8, source.calls.size)

        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        shown = shown.copy(podSet = SparkPodSet, grouping = UsageGrouping.DriverExecutors(DriverRef, ExecutorsRef))
        waitForIdle()

        assertEquals(16, source.calls.size)
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        onAllNodesWithText("Driver").assertCountEquals(3)
        onAllNodesWithText("Executors").assertCountEquals(3)

        gate.complete(Unit)
        waitForIdle()

        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        onAllNodesWithText("Driver").assertCountEquals(3)
        assertEquals(setOf(SparkPodSet, DriverRef, ExecutorsRef), source.calls.drop(8).map { it.pods }.toSet())
    }

    @Test
    fun `a Spark application whose group refs change while its pod set does not asks again`() = runComposeUiTest {
        val source = FakeSource()
        var shown by mutableStateOf(sparkTarget())
        showChanging(source) { shown }
        assertEquals(8, source.calls.size)

        val oneExecutorRef = PromQl.ofSparkExecutors(NAMESPACE, listOf(EXECUTOR_1))!!
        shown = shown.copy(grouping = UsageGrouping.DriverExecutors(DriverRef, oneExecutorRef))
        waitForIdle()

        assertEquals(16, source.calls.size)
        assertEquals(setOf(SparkPodSet, DriverRef, oneExecutorRef), source.calls.drop(8).map { it.pods }.toSet())
    }

    @Test
    fun `charts that gain a kind keep the charts drawn until the new answer arrives`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        var shown by mutableStateOf(targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))
        showChanging(source) { shown }
        assertEquals(4, source.calls.size)
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertExists()

        // A pod with a CPU limit is listed: throttling applies, and the answer that has it is held back.
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        shown = shown.copy(currentPods = listOf(pod("$JOB-x7k2p")))
        waitForIdle()

        assertEquals(9, source.calls.size)
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }

        gate.complete(Unit)
        waitForIdle()

        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertDoesNotExist()
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(3) }
    }

    @Test
    fun `a run that gains its end keeps the charts drawn until the answer for its final window arrives`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        val start = NOW - 3_600
        val end = NOW - 3_000
        var shown by mutableStateOf(targetOf(UsageWindow.Run(start, null)))
        showChanging(source) { shown }
        assertEquals(4, source.calls.size)

        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        shown = shown.copy(defaultWindow = UsageWindow.Run(start, end))
        waitForIdle()

        assertEquals(8, source.calls.size)
        assertEquals(QueryWindow.spanning(start, end), source.cpuWindows.last())
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }

        gate.complete(Unit)
        waitForIdle()

        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }
    }

    @Test
    fun `choosing the range that the default window has become fetches it instead of waiting on the dropped answer`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        // The Job loses the start time its run window came from, so its default falls back to the last 6 hours.
        var shown by mutableStateOf(targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))
        showChanging(source) { shown }
        shown = shown.copy(defaultWindow = UsageWindow.Sliding(HistoryRange.H6))
        waitForIdle()
        assertEquals(HistoryRange.H6.window(NOW), source.cpuWindows.last())

        // The button names the window shown, the fallback range.
        onNodeWithText("6h ▾").performClick()
        onNodeWithText("6h").performClick()
        waitForIdle()

        onNodeWithText("6h ▾").assertExists()
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }
    }

    @Test
    fun `choosing another window or mode shows the loading text while its answer is out`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        showChanging(source) { targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)) }

        source.gate = CompletableDeferred()
        onNodeWithText("Total").performClick()
        waitForIdle()

        // Three: this target has no live pod, so the throttling chart is an up-front note, not a skeleton.
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(3)
    }

    // ── Driver + executors ──────────────────────────────────────────────────

    private fun sparkTarget(window: UsageWindow = UsageWindow.Run(NOW - 7_200, NOW - 3_600)) = targetOf(
        window = window,
        grouping = UsageGrouping.DriverExecutors(DriverRef, ExecutorsRef),
        podSet = SparkPodSet,
        // The driver carries a CPU limit, so the throttling chart applies.
        currentPods = listOf(pod(DRIVER)),
    )

    @Test
    fun `a Spark application draws a driver line and an executors line, throttling the executors' ratio`() = runComposeUiTest {
        val source = FakeSource(listOf(DRIVER, EXECUTOR_1, EXECUTOR_2))
        show(source, sparkTarget())

        onNodeWithText("Driver + executors").assertExists()
        onNodeWithText("Per pod").assertDoesNotExist()
        // CPU, memory and throttling each list the two lines; the executors of throttling are the group's ratio, like the others' sum.
        onAllNodesWithText("Driver").assertCountEquals(3)
        onAllNodesWithText("Executors").assertCountEquals(3)
        onAllNodesWithText("Executors (mean)").assertCountEquals(0)
        listOf(DRIVER, EXECUTOR_1, EXECUTOR_2).forEach { onAllNodesWithText(it).assertCountEquals(0) }
        // No per-pod machinery: no grey lines, no request and limit guides, no uncounted pods.
        onAllNodes(earlierPodsEntry).assertCountEquals(0)
        onAllNodesWithText("request", substring = true).assertCountEquals(0)
        onAllNodesWithText("not drawn", substring = true).assertCountEquals(0)
        // Prometheus sums each group: two Total queries per kind, and network is one Total over the whole set.
        assertEquals(
            setOf(
                PodSeriesKind.Cpu to PodSetMode.Total,
                PodSeriesKind.Memory to PodSetMode.Total,
                PodSeriesKind.Throttling to PodSetMode.Total,
                PodSeriesKind.NetworkRx to PodSetMode.Total,
                PodSeriesKind.NetworkTx to PodSetMode.Total,
            ),
            source.calls.map { it.pair }.toSet(),
        )
        assertEquals(setOf(DriverRef, ExecutorsRef, SparkPodSet), source.calls.map { it.pods }.toSet())
        assertEquals(8, source.calls.size)
        assertEquals(setOf(SparkPodSet), source.calls.filter { it.kind == PodSeriesKind.NetworkRx || it.kind == PodSeriesKind.NetworkTx }.map { it.pods }.toSet())
    }

    @Test
    fun `the Total toggle of a Spark application asks for one summed line`() = runComposeUiTest {
        val source = FakeSource(listOf(DRIVER, EXECUTOR_1, EXECUTOR_2))
        show(source, sparkTarget())

        onNodeWithText("Total").performClick()
        waitForIdle()

        assertEquals(13, source.calls.size)
        assertTrue(source.calls.drop(8).all { it.mode == PodSetMode.Total })
        assertEquals(setOf(SparkPodSet), source.calls.drop(8).map { it.pods }.toSet())
        onAllNodesWithText("Driver").assertCountEquals(0)
        onAllNodesWithText("Executors").assertCountEquals(0)
        // A chart with a single coloured line prints its last value in the title row.
        onNodeWithText("2.00 cores").assertExists()

        onNodeWithText("Driver + executors").performClick()
        waitForIdle()

        onAllNodesWithText("Driver").assertCountEquals(3)
    }

    @Test
    fun `the two named series become the Driver and Executors lines, in that order`() {
        fun series(name: String, vararg points: Pair<Long, Double>) = Series(name, points.map { (t, v) -> SeriesPoint(t, v) })
        val result = SeriesResult.Ok(
            listOf(
                series(EXECUTORS_SERIES_NAME, 10L to 0.5, 20L to 4.0, 30L to 1.0),
                series(DRIVER_SERIES_NAME, 10L to 1.0, 20L to 2.0),
                // Another name belongs to neither line.
                series(EXECUTOR_1, 10L to 9.0),
            ),
        )

        val lines = driverExecutorsChartSeries(result)

        assertEquals(listOf(DRIVER_SERIES_NAME, EXECUTORS_SERIES_NAME), lines.map { it.name })
        assertEquals(listOf(SeriesPoint(10, 1.0), SeriesPoint(20, 2.0)), lines[0].points)
        assertEquals(listOf(SeriesPoint(10, 0.5), SeriesPoint(20, 4.0), SeriesPoint(30, 1.0)), lines[1].points)
        assertEquals(listOf(false, false), lines.map { it.muted })
        assertEquals(listOf(0, 1).map { ClusterColor(hue = podSetHue(it)).composeColor }, lines.map { it.color })
    }

    @Test
    fun `a group without series draws no line, and a failed answer draws none at all`() {
        val driverOnly = SeriesResult.Ok(listOf(Series(DRIVER_SERIES_NAME, listOf(SeriesPoint(10, 1.0)))))
        val executorsOnly = SeriesResult.Ok(listOf(Series(EXECUTORS_SERIES_NAME, listOf(SeriesPoint(10, 1.0)))))
        val executorsWithoutPoints = SeriesResult.Ok(listOf(Series(DRIVER_SERIES_NAME, listOf(SeriesPoint(10, 1.0))), Series(EXECUTORS_SERIES_NAME, emptyList())))

        assertEquals(listOf(DRIVER_SERIES_NAME), driverExecutorsChartSeries(driverOnly).map { it.name })
        assertEquals(listOf(EXECUTORS_SERIES_NAME), driverExecutorsChartSeries(executorsOnly).map { it.name })
        assertEquals(listOf(DRIVER_SERIES_NAME), driverExecutorsChartSeries(executorsWithoutPoints).map { it.name })
        assertEquals(emptyList(), driverExecutorsChartSeries(SeriesResult.Ok(emptyList())))
        assertEquals(emptyList(), driverExecutorsChartSeries(SeriesResult.Failed(HistoryFailure.Unavailable)))
        assertEquals(emptyList(), driverExecutorsChartSeries(null))
    }

    @Test
    fun `while the first fetch is out the four charts show as skeletons with their loading text and the static note`() = runComposeUiTest {
        val source = FakeSource(JobPods)
        source.gate = CompletableDeferred()
        // A live pod with a CPU limit, so throttling applies and no up-front note takes its chart's place.
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), currentPods = listOf(pod("$JOB-x7k2p"))))

        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(4)
        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onNodeWithText(it).assertExists() }
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(4)
        // The static trailing note shows from the first frame, so the line under the grid never changes.
        onNodeWithText(GMP_STALENESS_NOTE).assertExists()
    }

    @Test
    fun `a transient failure keeps the charts without a skeleton, and Retry asks again`() = runComposeUiTest {
        val source = FakeSource(JobPods, failure = SeriesResult.Failed(HistoryFailure.Unavailable, "timeout"))
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))

        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onNodeWithText(it).assertExists() }
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText("Retry").assertExists()
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        val asked = source.calls.size

        // The source recovers: Retry fetches once more and the charts fill in.
        source.failure = null
        onNodeWithText("Retry").performClick()
        waitForIdle()

        assertEquals(asked * 2, source.calls.size, "Retry asks every kind again, once")
        onNodeWithText("Retry").assertDoesNotExist()
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }
    }

    @Test
    fun `a structural failure collapses the charts to the reason with no Retry`() = runComposeUiTest {
        val source = FakeSource(JobPods, failure = SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"))
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))

        onNodeWithText("No access to Managed Prometheus", substring = true).assertExists()
        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onNodeWithText(it).assertDoesNotExist() }
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("6h ▾").assertDoesNotExist()
        onNodeWithText("Run ▾").assertExists()
    }

    @Test
    fun `a structural first answer swaps the loading charts for the reason, and a recovery swaps the charts back`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(JobPods, failure = SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"))
        show(source, targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)), REFRESH_MS)
        // Past the fade-through (90 ms out, 220 ms in), so only the new content is left.
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        onNodeWithText("No access to Managed Prometheus", substring = true).assertExists()
        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onAllNodesWithText(it).assertCountEquals(0) }
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onAllNodesWithText(CHART_LOADING_TEXT).assertCountEquals(0)
        onNodeWithText("Retry").assertDoesNotExist()

        source.failure = null
        refresh()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        onNodeWithText("No access to Managed Prometheus", substring = true).assertDoesNotExist()
        listOf("CPU", "Memory", "Network").forEach { onAllNodesWithText(it).assertCountEquals(1) }
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }
    }

    @Test
    fun `a failed executors query fails the kind, not the network`() = runComposeUiTest {
        // CPU stays answered: a section whose CPU and memory both failed shows one message instead of the charts.
        val source = FakeSource(failFor = ExecutorsRef, failForKinds = setOf(PodSeriesKind.Memory, PodSeriesKind.Throttling))
        show(source, sparkTarget())

        onAllNodesWithText("Not answering (ref)").assertCountEquals(2)
        // CPU draws both lines, the failed kinds none; network asks the whole set and draws.
        onAllNodesWithText("Driver").assertCountEquals(1)
        onAllNodesWithText("Executors").assertCountEquals(1)
        onAllNodesWithText("Received").assertCountEquals(1)
        onAllNodesWithText("Sent").assertCountEquals(1)
    }

    @Test
    fun `a failed executors query on CPU and memory keeps the charts and puts the failure message under them, like any transient failure`() = runComposeUiTest {
        val source = FakeSource(failFor = ExecutorsRef)
        show(source, sparkTarget())

        onNodeWithText("Managed Prometheus didn't answer (ref). Retrying every 30 s.").assertExists()
        onAllNodesWithText("Driver").assertCountEquals(0)
        onAllNodesWithText("CPU throttling").assertCountEquals(1)
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun `a null executor ref asks for the driver only`() = runComposeUiTest {
        val source = FakeSource()
        show(source, sparkTarget().copy(grouping = UsageGrouping.DriverExecutors(DriverRef, null)))

        assertEquals(5, source.calls.size)
        assertEquals(setOf(DriverRef, SparkPodSet), source.calls.map { it.pods }.toSet())
        // A chart with one line has no legend: the driver's value stands in the title row instead.
        onAllNodesWithText("Driver").assertCountEquals(0)
        onAllNodesWithText("Executors").assertCountEquals(0)
        onNodeWithText("2.00 cores").assertExists()
    }

    // ── Per pod: which pods are current ─────────────────────────────────────

    @Test
    fun `when every pod is current each one is coloured and none is grey, and all of them are counted`() = runComposeUiTest {
        val names = (1..8).map { "$JOB-pod$it" }
        show(FakeSource(names), targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), grouping = UsageGrouping.PerPod(null)))

        onAllNodes(earlierPodsEntry).assertCountEquals(0)
        onNodeWithText("2 more pods are not drawn — switch to Total.").assertExists()
        // The two lowest peaks are the ones left out of CPU and memory.
        names.take(2).forEach { onAllNodesWithText(it).assertCountEquals(0) }
        names.drop(2).forEach { onAllNodesWithText(it).assertCountEquals(2) }
    }

    @Test
    fun `when no prefix is current every pod is a grey line`() = runComposeUiTest {
        show(FakeSource(JobPods), targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), grouping = UsageGrouping.PerPod(emptySet())))

        onAllNodesWithText("3 earlier pods").assertCountEquals(2)
        JobPods.forEach { onAllNodesWithText(it).assertCountEquals(0) }
        onAllNodesWithText("not drawn", substring = true).assertCountEquals(0)
    }

    @Test
    fun `pods of the current prefixes are coloured and the rest are grey`() = runComposeUiTest {
        show(FakeSource(JobPods), targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), grouping = UsageGrouping.PerPod(setOf("$JOB-x7k2p", "$JOB-92f6w"))))

        listOf("$JOB-x7k2p", "$JOB-92f6w").forEach { onAllNodesWithText(it).assertCountEquals(2) }
        onAllNodesWithText("1 earlier pod").assertCountEquals(2)
    }

    @Test
    fun `the request and limit lines come from the first current pod in Per pod only`() = runComposeUiTest {
        show(FakeSource(JobPods), targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), currentPods = listOf(pod("$JOB-x7k2p"))))

        onNodeWithText("request 100m", substring = true).assertExists()
        onNodeWithText("limit 500m", substring = true).assertExists()

        onNodeWithText("Total").performClick()
        waitForIdle()

        onAllNodesWithText("request", substring = true).assertCountEquals(0)
    }

    @Test
    fun `current pods are matched by name prefix, all of them for null`() {
        assertTrue(UsageGrouping.PerPod(null).isCurrent("anything-x7k2p"))
        assertTrue(UsageGrouping.PerPod(setOf("a-", "b-")).isCurrent("b-x7k2p"))
        assertFalse(UsageGrouping.PerPod(setOf("a-", "b-")).isCurrent("c-x7k2p"))
        assertFalse(UsageGrouping.PerPod(emptySet()).isCurrent("a-x7k2p"))
    }

    @Test
    fun `the lambda forms of the pod helpers agree with the prefix forms`() {
        fun series(name: String, peak: Double) = Series(name, listOf(SeriesPoint(0, 0.0), SeriesPoint(30, peak)))
        val result = SeriesResult.Ok(listOf(series("a-aaaaa", 1.0), series("a-bbbbb", 5.0), series("b-ccccc", 9.0)))
        val colorIndex = mapOf("a-aaaaa" to 0, "a-bbbbb" to 1)

        assertEquals(perPodChartSeries(result, "a-", colorIndex), perPodChartSeries(result, { it.startsWith("a-") }, colorIndex))
        assertEquals(listOf("a-bbbbb", "a-aaaaa", "b-ccccc"), perPodChartSeries(result, "a-", colorIndex).map { it.name })
        assertEquals(listOf(false, false, true), perPodChartSeries(result, "a-", colorIndex).map { it.muted })
        val results = mapOf(PodSeriesKind.Cpu to result)
        assertEquals(notDrawnCurrentPods(results, "a-"), notDrawnCurrentPods(results, { it.startsWith("a-") }))
        assertEquals(0, notDrawnCurrentPods(results, { true }))
    }

    // ── the short-run note ──────────────────────────────────────────────────

    @Test
    fun `a run shorter than the window CPU is averaged over says so under the charts`() = runComposeUiTest {
        show(FakeSource(JobPods), targetOf(UsageWindow.Run(NOW - 400, NOW - 300)))

        onNodeWithText(GMP_SHORT_RUN_NOTE).assertExists()

        // The note is about the run, so a wider window keeps it.
        onNodeWithText("Run ▾").performClick()
        onNodeWithText("1h").performClick()
        waitForIdle()

        onNodeWithText("1h ▾").assertExists()
        onNodeWithText(GMP_SHORT_RUN_NOTE).assertExists()
    }

    @Test
    fun `a run longer than the averaging window has no short-run note`() = runComposeUiTest {
        show(FakeSource(JobPods), targetOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600)))
        onAllNodesWithText("shorter than", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a sliding default has no short-run note`() = runComposeUiTest {
        show(FakeSource(JobPods), targetOf(UsageWindow.Sliding(HistoryRange.M15), runLabel = ""))
        onAllNodesWithText("shorter than", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the note's window is the Prometheus flavour's, in whole minutes rounded up`() {
        val short = UsageWindow.Run(NOW - 100, NOW)

        assertEquals(GMP_SHORT_RUN_NOTE, shortRunNote(GmpChoice, short, NOW, stepSec = 30))
        assertEquals(
            "This run is shorter than the 4-minute window CPU is averaged over, so its CPU line is spread out and below the real peak.",
            shortRunNote(InClusterChoice, short, NOW, stepSec = 30),
        )
        // A coarser step widens the rate window: 330 s is 6 minutes rounded up.
        assertEquals(
            "This run is shorter than the 6-minute window CPU is averaged over, so its CPU line is spread out and below the real peak.",
            shortRunNote(GmpChoice, short, NOW, stepSec = 330),
        )
    }

    @Test
    fun `a run exactly as long as the window is long enough, one second less is not`() {
        assertNull(shortRunNote(GmpChoice, UsageWindow.Run(NOW - 300, NOW), NOW, stepSec = 30))
        assertEquals(GMP_SHORT_RUN_NOTE, shortRunNote(GmpChoice, UsageWindow.Run(NOW - 299, NOW), NOW, stepSec = 30))
        assertNull(shortRunNote(InClusterChoice, UsageWindow.Run(NOW - 240, NOW), NOW, stepSec = 30))
        assertTrue(shortRunNote(InClusterChoice, UsageWindow.Run(NOW - 239, NOW), NOW, stepSec = 30) != null)
    }

    @Test
    fun `a run that is still going is measured to the present`() {
        assertEquals(GMP_SHORT_RUN_NOTE, shortRunNote(GmpChoice, UsageWindow.Run(NOW - 100, null), NOW, stepSec = 30))
        assertNull(shortRunNote(GmpChoice, UsageWindow.Run(NOW - 100, null), NOW + 400, stepSec = 30))
    }

    @Test
    fun `only a run window on a Prometheus has the note`() {
        val start = NOW - 100
        assertNull(shortRunNote(GmpChoice, UsageWindow.Runs(start), NOW, stepSec = 30))
        assertNull(shortRunNote(GmpChoice, UsageWindow.Sliding(HistoryRange.M15), NOW, stepSec = 30))
        assertNull(shortRunNote(SourceChoice.Detecting, UsageWindow.Run(start, NOW), NOW, stepSec = 30))
        assertNull(shortRunNote(SourceChoice.None(NoSourceReason.Disabled), UsageWindow.Run(start, NOW), NOW, stepSec = 30))
    }

    // ── what is shown under the charts, in order ────────────────────────────

    @Test
    fun `the notes under the charts keep their order, the target's between the pod count and the data's`() = runComposeUiTest {
        val names = (1..7).map { "$JOB-pod$it" }
        val notes = listOf("First target note.", "Second target note.")
        show(FakeSource(names), targetOf(UsageWindow.Run(NOW - 400, NOW - 300), notes = notes))

        fun top(text: String) = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        val tops = listOf(
            "1 more pod is not drawn — switch to Total.",
            "First target note.",
            "Second target note.",
            GMP_SHORT_RUN_NOTE,
            GMP_STALENESS_NOTE,
        ).map(::top)

        assertEquals(tops.sorted(), tops, "the notes are out of order: $tops")
        assertEquals(tops.size, tops.toSet().size, "the notes share a line: $tops")
    }

    // ── the pure pieces ─────────────────────────────────────────────────────

    @Test
    fun `a target that says nothing else keeps the Deployment's notes and charts no throttling without live pods`() {
        val target = targetOf(UsageWindow.Sliding(HistoryRange.H6))

        assertEquals(NO_CURRENT_CPU_LIMIT_NOTE, target.noCpuLimitNote)
        assertEquals(GMP_STALENESS_NOTE, target.stalenessNote)
        assertFalse(target.throttlingWithoutLivePods)
    }

    @Test
    fun `a target without live pods charts every kind only when it asks for throttling without them`() {
        val everyKind = listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx, PodSeriesKind.Throttling)
        val limited = pod("$JOB-x7k2p")
        val unlimited = pod("$JOB-92f6w", resources = emptyList())

        assertEquals(everyKind, usageKinds(emptyList(), throttlingWithoutLivePods = true))
        assertEquals(deploymentKinds(emptyList()), usageKinds(emptyList(), throttlingWithoutLivePods = false))
        // Live pods decide, whichever way the flag is set.
        assertEquals(deploymentKinds(listOf(unlimited)), usageKinds(listOf(unlimited), throttlingWithoutLivePods = true))
        assertFalse(PodSeriesKind.Throttling in usageKinds(listOf(unlimited), throttlingWithoutLivePods = true))
        assertEquals(everyKind, usageKinds(listOf(limited), throttlingWithoutLivePods = true))
        assertEquals(deploymentKinds(listOf(limited, unlimited.copy(hostNetwork = true))), usageKinds(listOf(limited, unlimited.copy(hostNetwork = true)), throttlingWithoutLivePods = true))
    }

    @Test
    fun `a sliding window is the range's, and a run window is the run's with a margin`() {
        assertEquals(HistoryRange.H6.window(NOW), queryWindowOf(UsageWindow.Sliding(HistoryRange.H6), NOW))
        // Ended an hour ago: nothing reaches past the present, so the window is the run's, unclamped.
        assertEquals(QueryWindow.spanning(NOW - 7_200, NOW - 3_600), queryWindowOf(UsageWindow.Run(NOW - 7_200, NOW - 3_600), NOW))
    }

    @Test
    fun `a window that reaches past the present ends there, aligned down to its step`() {
        val run = queryWindowOf(UsageWindow.Run(NOW - 600, null), NOW)
        val runs = queryWindowOf(UsageWindow.Runs(NOW - 600), NOW)

        assertEquals(runs, run)
        assertEquals(NOW - NOW % run.stepSec, run.endEpochSec)
        assertEquals(QueryWindow.spanning(NOW - 600, NOW).startEpochSec, run.startEpochSec)
        // A run that ended 10 s ago still has most of its 60 s margin ahead.
        val ended = queryWindowOf(UsageWindow.Run(NOW - 1_000, NOW - 10), NOW)
        assertEquals(NOW - NOW % ended.stepSec, ended.endEpochSec)
        // The window moves with the clock until the margin is past.
        assertEquals(ended.endEpochSec + ended.stepSec, queryWindowOf(UsageWindow.Run(NOW - 1_000, NOW - 10), NOW + ended.stepSec).endEpochSec)
        assertEquals(
            QueryWindow.spanning(NOW - 1_000, NOW - 10),
            queryWindowOf(UsageWindow.Run(NOW - 1_000, NOW - 10), QueryWindow.spanning(NOW - 1_000, NOW - 10).endEpochSec),
        )
    }

    @Test
    fun `a run that starts ahead of the clock keeps a window that ends after it starts`() {
        val window = queryWindowOf(UsageWindow.Run(NOW + 1_000, null), NOW)

        assertTrue(window.endEpochSec > window.startEpochSec)
        assertEquals(QueryWindow.spanning(NOW + 1_000, NOW + 1_000), window)
    }

    @Test
    fun `a run that is still going after two months is windowed to its last week`() {
        val day = 86_400L
        val week = HistoryRange.D7.seconds

        val window = queryWindowOf(UsageWindow.Run(NOW - 60 * day, null), NOW)

        assertEquals(queryWindowOf(UsageWindow.Run(NOW - week, null), NOW), window)
        assertTrue(window.endEpochSec - window.startEpochSec <= week + 2 * (week / 20), "${window.endEpochSec - window.startEpochSec} s")
        assertTrue(window.endEpochSec <= NOW)
        assertEquals(0L, window.startEpochSec % window.stepSec)
        assertEquals(0L, window.endEpochSec % window.stepSec)
        assertNull(shortRunNote(GmpChoice, UsageWindow.Run(NOW - 60 * day, null), NOW, stepSec = window.stepSec), "the note measures the run, not the window")
    }

    @Test
    fun `a finished run of ten days shows its last week up to its end, whenever it is looked at`() {
        val day = 86_400L
        val week = HistoryRange.D7.seconds
        val start = NOW - 12 * day
        val end = NOW - 2 * day

        val window = queryWindowOf(UsageWindow.Run(start, end), NOW)

        assertEquals(QueryWindow.spanning(end - week, end), window)
        assertTrue(window.startEpochSec > start, "the first three days are left out")
        // Both margins, each aligned outwards to the step.
        assertTrue(window.endEpochSec - window.startEpochSec <= week + 2 * (week / 20) + 2 * window.stepSec)
        assertTrue(window.endEpochSec <= NOW)
        assertEquals(window, queryWindowOf(UsageWindow.Run(start, end), NOW + 3_600))
        assertEquals(window, queryWindowOf(UsageWindow.Run(start, end), NOW + 40 * day))
    }

    @Test
    fun `a run of exactly a week is not cut, and the runs of a CronJob from a month ago are windowed to a week`() {
        val day = 86_400L
        val week = HistoryRange.D7.seconds

        // Ended two days ago, so its margin is past and nothing is clamped.
        val ended = NOW - 2 * day
        assertEquals(QueryWindow.spanning(ended - week, ended), queryWindowOf(UsageWindow.Run(ended - week, ended), NOW))

        val runs = queryWindowOf(UsageWindow.Runs(NOW - 30 * day), NOW)
        assertEquals(queryWindowOf(UsageWindow.Run(NOW - week, null), NOW), runs)
        assertTrue(runs.endEpochSec - runs.startEpochSec <= week + 2 * (week / 20))
        assertTrue(runs.endEpochSec <= NOW)
    }

    @Test
    fun `a final window keeps an empty answer, and failures are asked for again either way`() {
        val window = QueryWindow(0, 60, 30)
        val points = SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(0, 1.0)))))
        val empty = SeriesResult.Ok(emptyList())
        val failed = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")

        fun loaded(cpu: SeriesResult, memory: SeriesResult, at: QueryWindow = window) = LoadedHistory(at, mapOf(PodSeriesKind.Cpu to cpu, PodSeriesKind.Memory to memory))
        assertFalse(needsFetch(loaded(points, points), window, finalWhenAnswered = true))
        assertFalse(needsFetch(loaded(points, points), window, finalWhenAnswered = false))
        assertFalse(needsFetch(loaded(empty, empty), window, finalWhenAnswered = true))
        assertTrue(needsFetch(loaded(empty, empty), window, finalWhenAnswered = false))
        assertTrue(needsFetch(loaded(points, failed), window, finalWhenAnswered = true))
        assertTrue(needsFetch(loaded(failed, failed), window, finalWhenAnswered = true))
        assertTrue(needsFetch(null, window, finalWhenAnswered = true))
        assertTrue(needsFetch(loaded(points, points), window.copy(endEpochSec = 90), finalWhenAnswered = true))
        // The two-argument form is the sliding rule.
        assertEquals(needsFetch(loaded(empty, empty), window, finalWhenAnswered = false), needsFetch(loaded(empty, empty), window))
    }
}
