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
import com.kubekubedashdash.models.SparkRun
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
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.ui.screens.namespaces.NamespaceUsageSection
import com.kubekubedashdash.ui.screens.pods.formatBytesValue
import com.kubekubedashdash.ui.screens.pods.formatCoresValue
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.QuotaCeilings
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

/** Answers a per-pod query with one series for each of [podNames] and a total with one unnamed series; records the windows and modes asked. */
private class RecordingSource(private val podNames: List<String>) : MetricsHistorySource {
    override val label = "Fake source"

    val pods = CopyOnWriteArrayList<PodSetRef>()
    val windows = CopyOnWriteArrayList<QueryWindow>()
    val modes = CopyOnWriteArrayList<Pair<PodSeriesKind, PodSetMode>>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    override suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult {
        this.pods += pods
        windows += window
        modes += kind to mode

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
 * The usage sections of the Job, CronJob, SparkApplication, ScheduledSparkApplication and Namespace panels:
 * [WorkloadUsageHistorySection] over the targets of [jobTarget], [cronJobTarget], [sparkTarget] and
 * [scheduledSparkTarget] and a fake source, and [NamespaceUsageSection] over the same kind of source. The Settings flow is a plain [MutableStateFlow] handed to the service's
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

    private fun serviceOver(source: MetricsHistorySource, setting: MetricsSourceSetting = GmpSetting) = MetricsHistoryService(
        currentContext = { CONTEXT },
        settingsFlow = { MutableStateFlow(mapOf(DemoContext.preferenceKey(CONTEXT) to setting)) },
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
    fun `a finished Job whose pods are gone still charts CPU throttling, and the Managed Prometheus note speaks of the run`() = runComposeUiTest {
        val source = RecordingSource(jobPods)
        show(source, assertNotNull(jobTarget(job("db-vacuum", NOW - 7_200, NOW - 3_600), emptyList())))

        assertTrue(PodSeriesKind.Throttling in source.modes.map { it.first }, "no throttling query: ${source.modes}")
        // CPU, memory and throttling list the three pods.
        jobPods.forEach { onAllNodesWithText(it).assertCountEquals(3) }
        onNodeWithText(JOB_NO_CPU_LIMIT_NOTE).assertDoesNotExist()
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertDoesNotExist()
        onNodeWithText(BATCH_GMP_STALENESS_NOTE).assertExists()
        onNodeWithText(GMP_STALENESS_NOTE).assertDoesNotExist()
    }

    @Test
    fun `a Job whose live pods have no CPU limit says so in its own words and asks no throttling`() = runComposeUiTest {
        val source = RecordingSource(jobPods)
        show(source, assertNotNull(jobTarget(job("db-vacuum", NOW - 7_200, NOW - 3_600), listOf(pod(jobPods.first(), resources = emptyList())))))

        onNodeWithText(JOB_NO_CPU_LIMIT_NOTE).assertExists()
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertDoesNotExist()
        assertTrue(PodSeriesKind.Throttling !in source.modes.map { it.first }, "a throttling query was made: ${source.modes}")
        jobPods.forEach { onAllNodesWithText(it).assertCountEquals(2) }
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

    // ── SparkApplication ────────────────────────────────────────────────────

    private val sparkDriver = "spark-etl-nightly-driver"
    private val sparkExecutors = listOf("spark-etl-nightly-1a2b3c4d5e6f7890-exec-1", "spark-etl-nightly-1a2b3c4d5e6f7890-exec-2")
    private val sparkPodNames = listOf(sparkDriver) + sparkExecutors

    private fun sparkApp(
        name: String = "spark-etl-nightly",
        state: String = "COMPLETED",
        submitted: Long? = NOW - 7_200,
        terminated: Long? = NOW - 6_000,
        driverPod: String = sparkDriver,
        executorPods: List<String> = sparkExecutors,
        owners: List<OwnerRefInfo> = emptyList(),
        withSpark: Boolean = true,
    ) = GenericResourceInfo(
        uid = "uid-$name",
        name = name,
        namespace = NAMESPACE,
        status = null,
        age = "1h",
        labels = emptyMap(),
        annotations = emptyMap(),
        owners = owners,
        spark = if (withSpark) {
            SparkRun(
                driverPod = driverPod,
                executorPods = executorPods,
                submittedAt = submitted?.let(::iso).orEmpty(),
                terminatedAt = terminated?.let(::iso).orEmpty(),
                state = state,
            )
        } else {
            null
        },
    )

    @Test
    fun `a completed SparkApplication is charted over its run as a driver line and an executors line, with its submission and end marked`() = runComposeUiTest {
        val start = NOW - 7_200
        val end = NOW - 6_000
        val source = RecordingSource(sparkPodNames)
        val target = assertNotNull(sparkTarget(sparkApp(), listOf(pod(sparkDriver))))
        show(source, target)

        assertEquals(setOf(QueryWindow.spanning(start, end)), source.windows.toSet())
        assertEquals(setOf(target.podSet), source.pods.toSet())
        onNodeWithText("Run ▾").assertExists()
        onNodeWithText("Driver + executors").assertExists()
        onNodeWithText("Total").assertExists()
        onNodeWithText("Per pod").assertDoesNotExist()
        // CPU, memory and throttling; the driver pod's CPU limit brings the throttling chart in, which averages the executors.
        onAllNodesWithText("Driver").assertCountEquals(3)
        onAllNodesWithText("Executors").assertCountEquals(2)
        onAllNodesWithText("Executors (mean)").assertCountEquals(1)
        sparkPodNames.forEach { onAllNodesWithText(it).assertCountEquals(0) }
        onAllNodesWithText("submitted").assertCountEquals(4)
        onAllNodesWithText("ended").assertCountEquals(4)
        // A driver and executors differ, so no pod's request or limit stands for the lines.
        onAllNodesWithText("request 100m", substring = true).assertCountEquals(0)
        onAllNodesWithText("limit 500m", substring = true).assertCountEquals(0)
        // The per-pod series are summed on the client: the pods are asked for one by one, the network as a sum.
        assertEquals(
            setOf(PodSetMode.PerPod),
            source.modes.filter { (kind, _) -> kind in setOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.Throttling) }.map { it.second }.toSet(),
        )
        assertEquals(
            setOf(PodSetMode.Total),
            source.modes.filter { (kind, _) -> kind in setOf(PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx) }.map { it.second }.toSet(),
        )
    }

    @Test
    fun `a SparkApplication without a pod that has a CPU limit has no throttling line and no mean`() = runComposeUiTest {
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(), listOf(pod(sparkDriver, resources = emptyList())))))

        onAllNodesWithText("Driver").assertCountEquals(2)
        onAllNodesWithText("Executors").assertCountEquals(2)
        onAllNodesWithText("Executors (mean)").assertCountEquals(0)
    }

    @Test
    fun `a completed SparkApplication whose pods are gone still charts CPU throttling, and the Managed Prometheus note speaks of the run`() = runComposeUiTest {
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(), emptyList())))

        assertTrue(PodSeriesKind.Throttling in source.modes.map { it.first }, "no throttling query: ${source.modes}")
        onAllNodesWithText("Driver").assertCountEquals(3)
        onAllNodesWithText("Executors (mean)").assertCountEquals(1)
        onNodeWithText(SPARK_NO_CPU_LIMIT_NOTE).assertDoesNotExist()
        onNodeWithText(BATCH_GMP_STALENESS_NOTE).assertExists()
        onNodeWithText(GMP_STALENESS_NOTE).assertDoesNotExist()
    }

    @Test
    fun `a SparkApplication whose live pods have no CPU limit says so in its own words`() = runComposeUiTest {
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(), listOf(pod(sparkDriver, resources = emptyList())))))

        onNodeWithText(SPARK_NO_CPU_LIMIT_NOTE).assertExists()
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertDoesNotExist()
        assertTrue(PodSeriesKind.Throttling !in source.modes.map { it.first }, "a throttling query was made: ${source.modes}")
    }

    @Test
    fun `the Total toggle of a SparkApplication asks for the sum and draws one line`() = runComposeUiTest {
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(), listOf(pod(sparkDriver)))))

        onNodeWithText("Total").performClick()
        waitForIdle()

        assertEquals(PodSetMode.Total, source.modes.last().second)
        onAllNodesWithText("Driver").assertCountEquals(0)
        onAllNodesWithText("Executors").assertCountEquals(0)
        onAllNodesWithText("All pods").assertCountEquals(3)
    }

    @Test
    fun `the SparkApplication's menu lists its run first and then the fixed ranges`() = runComposeUiTest {
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(), emptyList())))

        onNodeWithText("Run ▾").performClick()
        onNodeWithText("Run").assertExists()
        HistoryRange.entries.forEach { onNodeWithText(it.label).assertExists() }
        onNodeWithText("6h").performClick()
        waitForIdle()

        onNodeWithText("6h ▾").assertExists()
        assertEquals(HistoryRange.H6.window(NOW), source.windows.last())
    }

    @Test
    fun `a SparkApplication that is still running ends its window at the present and marks only its submission`() = runComposeUiTest {
        val start = NOW - 600
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(state = "RUNNING", submitted = start, terminated = null), emptyList())))

        val run = QueryWindow.spanning(start, NOW)
        assertTrue(run.endEpochSec > NOW, "the margin reaches past the present")
        assertEquals(setOf(run.copy(endEpochSec = NOW - NOW % run.stepSec)), source.windows.toSet())
        onAllNodesWithText("submitted").assertCountEquals(4)
        onAllNodesWithText("ended").assertCountEquals(0)
    }

    @Test
    fun `a SparkApplication whose status names no pods says so and asks nothing`() = runComposeUiTest {
        val source = RecordingSource(sparkPodNames)
        show(source, assertNotNull(sparkTarget(sparkApp(withSpark = false), emptyList())))

        onNodeWithText("This application's status names no pods yet.").assertExists()
        onNodeWithText("6h ▾").assertExists()
        assertTrue(source.windows.isEmpty())
    }

    // ── ScheduledSparkApplication ───────────────────────────────────────────

    private val scheduledUid = "uid-sched-1"

    private val scheduledApp = GenericResourceInfo(
        uid = scheduledUid,
        name = "spark-report-hourly",
        namespace = NAMESPACE,
        status = null,
        age = "3d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    private val scheduledOwners = listOf(OwnerRefInfo(kind = "ScheduledSparkApplication", name = "spark-report-hourly", uid = scheduledUid, controller = true))

    // Two runs, at 11:00 and 12:00 UTC (a few seconds past).
    private val firstRunStart = NOW - 2 * 3_600
    private val scheduledRuns = listOf("1760252400", "1760256000").mapIndexed { index, suffix ->
        sparkApp(
            name = "spark-report-hourly-$suffix",
            submitted = firstRunStart + index * 3_600L,
            terminated = firstRunStart + index * 3_600L + 300,
            driverPod = "spark-report-hourly-$suffix-driver",
            executorPods = listOf("spark-report-hourly-$suffix-1a2b3c4d5e6f7890-exec-1"),
            owners = scheduledOwners,
        )
    }
    private val scheduledPodNames = scheduledRuns.flatMap { run -> listOfNotNull(run.spark?.driverPod) + run.spark?.executorPods.orEmpty() }

    @Test
    fun `a ScheduledSparkApplication is charted from its oldest run to the present as a driver line and an executors line, with a marker at each run`() = runComposeUiTest {
        val source = RecordingSource(scheduledPodNames)
        val target = assertNotNull(scheduledSparkTarget(scheduledApp, scheduledRuns, listOf(pod(scheduledPodNames.first())), ZoneOffset.UTC))
        show(source, target)

        val runs = QueryWindow.spanning(firstRunStart, NOW)
        assertTrue(runs.endEpochSec > NOW, "the margin reaches past the present")
        assertEquals(setOf(runs.copy(endEpochSec = NOW - NOW % runs.stepSec)), source.windows.toSet())
        assertEquals(setOf(target.podSet), source.pods.toSet())
        onNodeWithText("Runs ▾").assertExists()
        onNodeWithText("Driver + executors").assertExists()
        onAllNodesWithText("Driver").assertCountEquals(3)
        onAllNodesWithText("Executors").assertCountEquals(2)
        onAllNodesWithText("Executors (mean)").assertCountEquals(1)
        scheduledPodNames.forEach { onAllNodesWithText(it).assertCountEquals(0) }
        listOf("11:00", "12:00").forEach { onAllNodesWithText(it).assertCountEquals(4) }
        onAllNodesWithText("submitted").assertCountEquals(0)
    }

    @Test
    fun `the ScheduledSparkApplication's menu lists Runs first and then the fixed ranges`() = runComposeUiTest {
        val source = RecordingSource(scheduledPodNames)
        show(source, assertNotNull(scheduledSparkTarget(scheduledApp, scheduledRuns, emptyList(), ZoneOffset.UTC)))

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
    fun `a ScheduledSparkApplication with no run loaded says so and asks nothing`() = runComposeUiTest {
        val source = RecordingSource(scheduledPodNames)
        show(source, assertNotNull(scheduledSparkTarget(scheduledApp, emptyList(), emptyList(), ZoneOffset.UTC)))

        onNodeWithText("No run of this application is loaded.").assertExists()
        onNodeWithText("24h ▾").assertExists()
        assertTrue(source.windows.isEmpty())
    }

    // ── Namespace ───────────────────────────────────────────────────────────

    private fun namespaceRow(name: String = NAMESPACE) = GenericResourceInfo(
        uid = "uid-ns-$name",
        name = name,
        namespace = null,
        status = "Active",
        age = "30d",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    private val quotaCeilings = QuotaCeilings(
        requestsCpu = 4.0,
        limitsCpu = 8.0,
        requestsMemory = 8.0 * 1024 * 1024 * 1024,
        limitsMemory = 16.0 * 1024 * 1024 * 1024,
    )

    private fun ComposeUiTest.showNamespace(
        source: MetricsHistorySource,
        namespace: GenericResourceInfo = namespaceRow(),
        setting: MetricsSourceSetting = GmpSetting,
        refreshMs: Long = Long.MAX_VALUE,
        fetchCeilings: suspend (String) -> QuotaCeilings = { quotaCeilings },
    ) {
        val service = serviceOver(source, setting)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { NamespaceUsageSection(namespace, service, fetchCeilings, refreshMs, { NOW }, Long.MAX_VALUE) }
            }
        }
        waitForIdle()
    }

    private val namespacePods = listOf("web-0", "web-1", "web-2")

    @Test
    fun `a Namespace opens on Total over every pod of the namespace, in the last 6 hours, with all five charts asked`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        showNamespace(source)

        assertEquals(setOf(PromQl.ofNamespace(NAMESPACE)), source.pods.toSet())
        assertEquals(setOf(PodSetMode.Total), source.modes.map { it.second }.toSet())
        assertEquals(PodSeriesKind.entries.toSet(), source.modes.map { it.first }.toSet())
        assertEquals(setOf(HistoryRange.H6.window(NOW)), source.windows.toSet())
        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText("6h ▾").assertExists()
        onNodeWithText("Per pod").assertExists()
        onNodeWithText("Total").assertExists()
        // CPU and memory list the one summed line next to their quota lines; a lone line with no guide has no legend.
        onAllNodesWithText("All pods").assertCountEquals(2)
        namespacePods.forEach { onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test
    fun `the quota requests and limits are guide lines in Total only, with their values in the legend`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        showNamespace(source)

        // CPU and memory each list both lines; whether a line is drawn or marked off scale depends on the usage.
        onAllNodesWithText("quota requests", substring = true).assertCountEquals(2)
        onAllNodesWithText("quota limits", substring = true).assertCountEquals(2)
        onNodeWithText("- - quota requests ${formatCoresValue(4.0)}", substring = true).assertExists()
        onNodeWithText("── quota limits ${formatCoresValue(8.0)}", substring = true).assertExists()
        onNodeWithText("- - quota requests ${formatBytesValue(quotaCeilings.requestsMemory!!)}", substring = true).assertExists()
        onNodeWithText("── quota limits ${formatBytesValue(quotaCeilings.limitsMemory!!)}", substring = true).assertExists()

        onNodeWithText("Per pod").performClick()
        waitForIdle()

        assertEquals(PodSetMode.PerPod, source.modes.last { it.first == PodSeriesKind.Cpu }.second)
        onAllNodesWithText("quota", substring = true).assertCountEquals(0)
        onAllNodesWithText("request", substring = true).assertCountEquals(0)
        onAllNodesWithText("limit", substring = true).assertCountEquals(0)

        onNodeWithText("Total").performClick()
        waitForIdle()

        onAllNodesWithText("quota requests", substring = true).assertCountEquals(2)
    }

    @Test
    fun `a quota far above the usage is listed in the legend as off scale`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        // The fake source peaks at 2.0, so 1 000 cores and 1 TiB are far beyond twice the data.
        showNamespace(source, fetchCeilings = { QuotaCeilings(requestsCpu = 1_000.0, limitsMemory = 1024.0 * 1024 * 1024 * 1024) })

        onNodeWithText("- - quota requests ${formatCoresValue(1_000.0)} (off scale)").assertExists()
        onNodeWithText("── quota limits ${formatBytesValue(1024.0 * 1024 * 1024 * 1024)} (off scale)").assertExists()
        onAllNodesWithText("quota limits", substring = true).assertCountEquals(1)
        onAllNodesWithText("quota requests", substring = true).assertCountEquals(1)
    }

    @Test
    fun `a namespace without a quota draws no guide line`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        showNamespace(source, fetchCeilings = { QuotaCeilings() })

        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText("CPU").assertExists()
        onNodeWithText("Memory").assertExists()
        onAllNodesWithText("quota", substring = true).assertCountEquals(0)
    }

    @Test
    fun `Per pod draws every pod in colour and counts those past the sixth in a line under the charts`() = runComposeUiTest {
        val names = (0 until 8).map { "web-$it" }
        val source = RecordingSource(names)
        showNamespace(source)

        onNodeWithText("Per pod").performClick()
        waitForIdle()

        onNodeWithText("2 more pods are not drawn — switch to Total.").assertExists()
        // The two lowest peaks are the ones left out; the others are listed in CPU, memory and throttling.
        names.take(2).forEach { onAllNodesWithText(it).assertCountEquals(0) }
        names.drop(2).forEach { onAllNodesWithText(it).assertCountEquals(3) }
        onAllNodes(earlierPodsEntry).assertCountEquals(0)

        onNodeWithText("Total").performClick()
        waitForIdle()

        onAllNodesWithText("not drawn", substring = true).assertCountEquals(0)
    }

    @Test
    fun `on Managed Prometheus the namespace note speaks of a replaced pod counted twice in Total`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        showNamespace(source)

        onNodeWithText(NAMESPACE_GMP_STALENESS_NOTE).assertExists()
        onNodeWithText(BATCH_GMP_STALENESS_NOTE).assertDoesNotExist()
        onNodeWithText(GMP_STALENESS_NOTE).assertDoesNotExist()
    }

    @Test
    fun `with no history source the namespace section renders nothing and never lists the quotas`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        val fetched = CopyOnWriteArrayList<String>()
        showNamespace(source, setting = MetricsSourceSetting(MetricsSourceMode.OFF), fetchCeilings = { name ->
            fetched += name
            quotaCeilings
        })

        onNodeWithText("Resource usage", ignoreCase = true).assertDoesNotExist()
        onNodeWithText("6h ▾").assertDoesNotExist()
        onNodeWithText("Per pod").assertDoesNotExist()
        assertTrue(fetched.isEmpty(), "the quotas were listed without a source: $fetched")
        assertTrue(source.windows.isEmpty())
    }

    @Test
    fun `the quotas of the shown namespace are listed, and again every refresh`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = RecordingSource(namespacePods)
        val fetched = CopyOnWriteArrayList<String>()
        val service = serviceOver(source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) {
                    NamespaceUsageSection(
                        namespaceRow(),
                        service,
                        { name ->
                            fetched += name
                            QuotaCeilings(requestsCpu = 2.0 * fetched.size)
                        },
                        60_000L,
                        { NOW },
                        Long.MAX_VALUE,
                    )
                }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()

        assertEquals(listOf(NAMESPACE), fetched.toList())
        onNodeWithText("- - quota requests ${formatCoresValue(2.0)}", substring = true).assertExists()

        mainClock.advanceTimeBy(60_001)
        waitForIdle()

        assertEquals(listOf(NAMESPACE, NAMESPACE), fetched.toList())
        onNodeWithText("- - quota requests ${formatCoresValue(4.0)}", substring = true).assertExists()
    }

    @Test
    fun `another namespace in the same panel starts over on Total with its own quotas`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        val fetched = CopyOnWriteArrayList<String>()
        val service = serviceOver(source)
        var shown by mutableStateOf(namespaceRow("team-a"))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) {
                    NamespaceUsageSection(
                        shown,
                        service,
                        { name ->
                            fetched += name
                            QuotaCeilings(requestsCpu = if (name == "team-b") 3.0 else 4.0)
                        },
                        Long.MAX_VALUE,
                        { NOW },
                        Long.MAX_VALUE,
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("Per pod").performClick()
        waitForIdle()
        assertEquals(PodSetMode.PerPod, source.modes.last { it.first == PodSeriesKind.Cpu }.second)
        onAllNodesWithText("quota", substring = true).assertCountEquals(0)

        shown = namespaceRow("team-b")
        waitForIdle()

        assertEquals(listOf("team-a", "team-b"), fetched.toList())
        assertEquals(PromQl.ofNamespace("team-b"), source.pods.last())
        assertEquals(PodSetMode.Total, source.modes.last { it.first == PodSeriesKind.Cpu }.second)
        onNodeWithText("- - quota requests ${formatCoresValue(3.0)}", substring = true).assertExists()
    }

    @Test
    fun `a namespace name that cannot be queried says so and asks nothing`() = runComposeUiTest {
        val source = RecordingSource(namespacePods)
        showNamespace(source, namespace = namespaceRow("Team-A"))

        onNodeWithText(NAMESPACE_MISSING_NOTE).assertExists()
        onNodeWithText("6h ▾").assertExists()
        assertTrue(source.windows.isEmpty())
    }
}
