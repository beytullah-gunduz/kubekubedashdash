package com.kubekubedashdash.ui.screens.workloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.WorkloadRun
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

// 2026-10-12T13:00:17Z, not a multiple of any chart step.
private const val NOW = 1_791_810_017L

private const val NAMESPACE = "team-a"

private const val CRON_UID = "uid-cron-1"

/** A chart legend entry that counts the grey lines ("3 earlier pods"), unlike prose that happens to say it. */
private val earlierPodsEntry = SemanticsMatcher("is an earlier-pods legend entry") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("""\d+ earlier pods?""").matches(it.text) }
}

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

private val Limited = listOf(ContainerResources("main", cpuRequestMillis = 100, cpuLimitMillis = 500, memoryRequestBytes = 64L shl 20, memoryLimitBytes = 128L shl 20))

private val Larger = listOf(ContainerResources("main", cpuRequestMillis = 250, cpuLimitMillis = 1_000, memoryRequestBytes = 256L shl 20, memoryLimitBytes = 512L shl 20))

/** Answers a per-pod query with one series for each of [podNames] and a total with one unnamed series; records the windows asked. */
private class RecordingSource(private val podNames: List<String>) : MetricsHistorySource {
    override val label = "Fake source"

    val pods = CopyOnWriteArrayList<PodSetRef>()
    val windows = CopyOnWriteArrayList<QueryWindow>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    override suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult {
        this.pods += pods
        windows += window

        fun points(peak: Double) = listOf(SeriesPoint(window.startEpochSec, 1.0), SeriesPoint(window.endEpochSec, peak))
        return SeriesResult.Ok(
            when (mode) {
                PodSetMode.Total -> listOf(Series("", points(2.0)))
                PodSetMode.PerPod -> podNames.mapIndexed { index, name -> Series(name, points(2.0 + index)) }
            },
        )
    }
}

private fun iso(epochSec: Long) = Instant.ofEpochSecond(epochSec).toString()

private fun pod(name: String, resources: List<ContainerResources> = Limited) = PodInfo(
    uid = "uid-$name",
    name = name,
    namespace = NAMESPACE,
    status = "Running",
    ready = "1/1",
    restarts = 0,
    age = "1h",
    node = "",
    ip = "",
    labels = emptyMap(),
    annotations = emptyMap(),
    containers = emptyList(),
    resources = resources,
)

private fun job(name: String, start: Long, end: Long?, owners: List<OwnerRefInfo> = emptyList()) = GenericResourceInfo(
    uid = "uid-$name",
    name = name,
    namespace = NAMESPACE,
    status = null,
    age = "1h",
    labels = emptyMap(),
    annotations = emptyMap(),
    owners = owners,
    run = WorkloadRun(startTime = iso(start), endTime = end?.let(::iso).orEmpty(), indexed = false),
)

/**
 * The Job and CronJob usage sections: [WorkloadUsageHistorySection] over the targets of [jobTarget] and
 * [cronJobTarget] and a fake source. The Settings flow is a plain [MutableStateFlow] handed to the service's
 * internal constructor, so nothing here reads the preference store, the network or gcloud.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class BatchUsageHistorySectionUiTest {

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
            shutdownCleanly(detectionScope, label = "BatchUsageHistorySectionUiTest")
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

    private fun ComposeUiTest.show(source: MetricsHistorySource, target: UsageTarget) {
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { WorkloadUsageHistorySection(target, service, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
    }

    // ── Job ─────────────────────────────────────────────────────────────────

    private val jobPods = listOf("x7k2p", "92f6w", "h4m8q").map { "db-vacuum-$it" }

    @Test
    fun `a finished Job is charted over its run, every pod in colour, with its start and end marked in each chart`() = runComposeUiTest {
        val start = NOW - 7_200
        val end = NOW - 3_600
        val source = RecordingSource(jobPods)
        val target = assertNotNull(jobTarget(job("db-vacuum", start, end), listOf(pod(jobPods.first()))))
        show(source, target)

        assertEquals(setOf(QueryWindow.spanning(start, end)), source.windows.toSet())
        assertEquals(setOf(target.podSet), source.pods.toSet())
        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText("Run ▾").assertExists()
        onNodeWithText("Per pod").assertExists()
        // CPU, memory and throttling list the three pods; the pod's CPU limit brings the throttling chart in.
        jobPods.forEach { onAllNodesWithText(it).assertCountEquals(3) }
        onAllNodes(earlierPodsEntry).assertCountEquals(0)
        onAllNodesWithText("start").assertCountEquals(4)
        onAllNodesWithText("end").assertCountEquals(4)
        onNodeWithText("request 100m", substring = true).assertExists()
        onNodeWithText("limit 500m", substring = true).assertExists()
    }

    @Test
    fun `the Job's menu lists its run first and then the fixed ranges`() = runComposeUiTest {
        val source = RecordingSource(jobPods)
        show(source, assertNotNull(jobTarget(job("db-vacuum", NOW - 7_200, NOW - 3_600), emptyList())))

        onNodeWithText("Run ▾").performClick()
        onNodeWithText("Run").assertExists()
        HistoryRange.entries.forEach { onNodeWithText(it.label).assertExists() }
        onNodeWithText("1h").performClick()
        waitForIdle()

        onNodeWithText("1h ▾").assertExists()
        assertEquals(HistoryRange.H1.window(NOW), source.windows.last())
    }

    @Test
    fun `a Job that is still running ends its window at the present and marks only its start`() = runComposeUiTest {
        val start = NOW - 600
        val source = RecordingSource(jobPods)
        show(source, assertNotNull(jobTarget(job("db-vacuum", start, end = null), emptyList())))

        val run = QueryWindow.spanning(start, NOW)
        assertTrue(run.endEpochSec > NOW, "the margin reaches past the present")
        assertEquals(setOf(run.copy(endEpochSec = NOW - NOW % run.stepSec)), source.windows.toSet())
        onAllNodesWithText("start").assertCountEquals(4)
        onAllNodesWithText("end").assertCountEquals(0)
    }

    @Test
    fun `a Job without a usable name says so and asks nothing`() = runComposeUiTest {
        val source = RecordingSource(jobPods)
        show(source, assertNotNull(jobTarget(job("Db_Vacuum", NOW - 7_200, NOW - 3_600), emptyList())))

        onNodeWithText("This Job has no usable name for a pod pattern.").assertExists()
        assertTrue(source.windows.isEmpty())
    }

    // ── CronJob ─────────────────────────────────────────────────────────────

    private val owners = listOf(OwnerRefInfo(kind = "CronJob", name = "nightly-backup", uid = CRON_UID, controller = true))

    private val cron = GenericResourceInfo(
        uid = CRON_UID,
        name = "nightly-backup",
        namespace = NAMESPACE,
        status = null,
        age = "3d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    // One run an hour for three hours: 10:00, 11:00 and 12:00 UTC.
    private val oldestStart = NOW - 3 * 3_600
    private val jobs = listOf(
        job("nightly-backup-29123400", oldestStart, oldestStart + 300, owners),
        job("nightly-backup-29123460", oldestStart + 3_600, oldestStart + 3_900, owners),
        job("nightly-backup-29123520", oldestStart + 7_200, oldestStart + 7_500, owners),
    )

    private val currentPods = listOf("x7k2p", "92f6w").map { "nightly-backup-29123520-$it" }
    private val earlierPods = listOf("nightly-backup-29123460-h4m8q", "nightly-backup-29123400-c3n5v")

    @Test
    fun `a CronJob is charted from its oldest run to the present, with a marker at each run and earlier runs in grey`() = runComposeUiTest {
        val source = RecordingSource(currentPods + earlierPods)
        val live = listOf(pod(currentPods.first()), pod(earlierPods.first(), Larger))
        val target = assertNotNull(cronJobTarget(cron, jobs, live, ZoneOffset.UTC))
        show(source, target)

        val runs = QueryWindow.spanning(oldestStart, NOW)
        assertTrue(runs.endEpochSec > NOW, "the margin reaches past the present")
        assertEquals(setOf(runs.copy(endEpochSec = NOW - NOW % runs.stepSec)), source.windows.toSet())
        assertEquals(setOf(target.podSet), source.pods.toSet())
        onNodeWithText("Runs ▾").assertExists()
        // The newest run's pods are in colour in CPU, memory and throttling; the earlier runs' share one grey entry.
        currentPods.forEach { onAllNodesWithText(it).assertCountEquals(3) }
        earlierPods.forEach { onAllNodesWithText(it).assertCountEquals(0) }
        onAllNodesWithText("2 earlier pods").assertCountEquals(3)
        listOf("10:00", "11:00", "12:00").forEach { onAllNodesWithText(it).assertCountEquals(4) }
        // The guide lines are the newest run's pod's, not the earlier run's.
        onNodeWithText("request 100m", substring = true).assertExists()
        onAllNodesWithText("request 250m", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the CronJob's menu lists Runs first and then the fixed ranges`() = runComposeUiTest {
        val source = RecordingSource(currentPods)
        show(source, assertNotNull(cronJobTarget(cron, jobs, emptyList(), ZoneOffset.UTC)))

        onNodeWithText("Runs ▾").performClick()
        onNodeWithText("Runs").assertExists()
        onNodeWithText("Run").assertDoesNotExist()
        HistoryRange.entries.forEach { onNodeWithText(it.label).assertExists() }
        onNodeWithText("24h").performClick()
        waitForIdle()

        onNodeWithText("24h ▾").assertExists()
        assertEquals(HistoryRange.H24.window(NOW), source.windows.last())
    }

    @Test
    fun `a CronJob whose name is too long for its Jobs' names and has no loaded Job says so`() = runComposeUiTest {
        val source = RecordingSource(currentPods)
        val longCron = cron.copy(name = "a-cron-job-whose-name-is-long-enough-to-cut-its-jobs-names")
        show(source, assertNotNull(cronJobTarget(longCron, emptyList(), emptyList(), ZoneOffset.UTC)))

        onNodeWithText("No Job of this CronJob is loaded.").assertExists()
        onNodeWithText("24h ▾").assertExists()
        assertTrue(source.windows.isEmpty())
    }
}
