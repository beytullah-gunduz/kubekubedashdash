package com.kubekubedashdash.ui.screens.generic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.SparkRun
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SparkGrouping
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.ui.components.CHART_SKELETON_TAG
import com.kubekubedashdash.ui.components.LOADING_HISTORY_NOTE
import com.kubekubedashdash.ui.screens.pods.formatCoresValue
import com.kubekubedashdash.ui.screens.workloads.DRIVER_SERIES_NAME
import com.kubekubedashdash.ui.screens.workloads.EXECUTORS_SERIES_NAME
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

private const val NOW = 86_400L * 100

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

private val OffSetting = MetricsSourceSetting(MetricsSourceMode.OFF)

/** One query the fake source answered. */
private data class Call(val kind: ScopeKind, val scope: ScopeRef, val grouping: SparkGrouping, val window: QueryWindow)

/**
 * Answers a Spark query with fixed series of two points: per pod for [SparkGrouping.ByPod] (two listed apps' pods
 * and one unlisted pod), per role for [SparkGrouping.ByRole]; records what it was asked. A set [failure] answers
 * every query with it instead.
 */
private class FakeSparkSource(@Volatile var failure: SeriesResult.Failed? = null) : MetricsHistorySource {
    override val label = "Fake source"
    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    override suspend fun sparkSeries(kind: ScopeKind, scope: ScopeRef, grouping: SparkGrouping, window: QueryWindow): SeriesResult {
        calls += Call(kind, scope, grouping, window)
        failure?.let { return it }

        fun series(name: String, scale: Double) = Series(name, listOf(SeriesPoint(window.startEpochSec, scale), SeriesPoint(window.endEpochSec, scale * 2)))
        return SeriesResult.Ok(
            if (grouping == SparkGrouping.ByPod) {
                listOf(series("team-a/etl-driver", 4.0), series("team-a/report-driver", 2.0), series("team-a/deleted-driver", 1.0))
            } else {
                listOf(series(PromQl.SPARK_DRIVER_CONTAINER, 1.0), series(PromQl.SPARK_EXECUTOR_CONTAINER, 3.0))
            },
        )
    }
}

private fun app(name: String) = GenericResourceInfo(
    uid = "uid-$name",
    name = name,
    namespace = "team-a",
    status = null,
    age = "1h",
    labels = emptyMap(),
    annotations = emptyMap(),
    spark = SparkRun("$name-driver", emptyList(), "", "", "RUNNING"),
)

/**
 * [SparkUsageHistoryCard] over a fake source: the Settings flow is a plain [MutableStateFlow] handed to the
 * service's internal constructor, so nothing here reads the preference store, the network or gcloud. The card
 * takes its expanded state as a parameter, so no test writes the stored panel preference.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SparkUsageHistoryCardUiTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = AtomicLong(1_000_000)

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
            shutdownCleanly(detectionScope, label = "SparkUsageHistoryCardUiTest")
        } finally {
            ThemeManager.syncStyleFromPreferences(originalStyle)
            ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
            ThemeManager.syncFromPreferences(originalMode)
        }
    }

    private fun serviceOver(setting: MetricsSourceSetting, source: MetricsHistorySource) = MetricsHistoryService(
        currentContext = { CONTEXT },
        settingsFlow = { MutableStateFlow(mapOf(DemoContext.preferenceKey(CONTEXT) to setting)) },
        gmpSourceFor = { source },
        detection = InClusterDetection(scope = detectionScope, nowMs = clock::get, detect = { _: KubernetesClient -> DetectionState.NothingFound(clock.get()) }),
        clientFor = { dummyClient() },
        nowMs = clock::get,
    )

    private fun ComposeUiTest.showCard(
        service: MetricsHistoryService,
        apps: () -> List<GenericResourceInfo> = { listOf(app("etl"), app("report")) },
        expanded: () -> Boolean = { true },
        width: Dp = 900.dp,
    ) = setContent {
        MaterialTheme(typography = kdTypography()) {
            Column(Modifier.width(width)) {
                SparkUsageHistoryCard(service, NamespaceScope.single("team-a"), apps(), expanded(), {}, { NOW }, Long.MAX_VALUE)
            }
        }
    }

    @Test
    fun `the card draws both charts, the largest applications, Other and the notes`() = runComposeUiTest {
        val source = FakeSparkSource()
        showCard(serviceOver(GmpSetting, source))
        waitForIdle()

        onNodeWithText(SPARK_HISTORY_TITLE).assertExists()
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("Applications ▾").assertExists()
        onNodeWithText("6h ▾").assertExists()
        listOf("CPU", "Memory").forEach { onNodeWithText(it).assertExists() }
        // One legend entry per chart.
        listOf("etl", "report", SPARK_OTHER_SERIES_NAME).forEach { onAllNodesWithText(it).assertCountEquals(2) }
        onNodeWithText(SPARK_HISTORY_NOTE).assertExists()
        onNodeWithText("From Fake source.").assertExists()

        assertEquals(setOf(ScopeKind.CpuCores, ScopeKind.MemoryBytes), source.calls.map { it.kind }.toSet())
        assertEquals(setOf(SparkGrouping.ByPod), source.calls.map { it.grouping }.toSet())
        assertEquals(setOf(ScopeRef(setOf("team-a"))), source.calls.map { it.scope }.toSet())
    }

    @Test
    fun `while the first fetch is out two skeleton charts show under a loading line`() = runComposeUiTest {
        val hanging = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = awaitCancellation()

            override suspend fun sparkSeries(kind: ScopeKind, scope: ScopeRef, grouping: SparkGrouping, window: QueryWindow): SeriesResult = awaitCancellation()
        }
        showCard(serviceOver(GmpSetting, hanging))
        waitForIdle()

        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        listOf("CPU", "Memory").forEach { onNodeWithText(it).assertExists() }
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(2)
        onNodeWithText(SPARK_HISTORY_NOTE).assertDoesNotExist()
        onNodeWithText("Applications ▾").assertExists()
    }

    @Test
    fun `Drivers and executors asks per role and Total draws one line`() = runComposeUiTest {
        val source = FakeSparkSource()
        showCard(serviceOver(GmpSetting, source))
        waitForIdle()

        onNodeWithText("Applications ▾").performClick()
        onNodeWithText("Drivers / executors").performClick()
        waitForIdle()

        onNodeWithText("Drivers / executors ▾").assertExists()
        listOf(DRIVER_SERIES_NAME, EXECUTORS_SERIES_NAME).forEach { onAllNodesWithText(it).assertCountEquals(2) }
        onNodeWithText("etl").assertDoesNotExist()
        assertEquals(SparkGrouping.ByRole, source.calls.last().grouping)
        assertEquals(setOf(ScopeKind.CpuCores, ScopeKind.MemoryBytes), source.calls.takeLast(2).map { it.kind }.toSet())

        onNodeWithText("Drivers / executors ▾").performClick()
        onNodeWithText("Total").performClick()
        waitForIdle()

        // A lone line has no legend: the sum of both roles' last values stands beside the chart's title.
        onNodeWithText(formatCoresValue(8.0)).assertExists()
        onNodeWithText(DRIVER_SERIES_NAME).assertDoesNotExist()
        assertEquals(SparkGrouping.ByRole, source.calls.last().grouping)
    }

    @Test
    fun `a changing application list redraws the lines without asking again`() = runComposeUiTest {
        val source = FakeSparkSource()
        var apps by mutableStateOf(listOf(app("etl")))
        showCard(serviceOver(GmpSetting, source), apps = { apps })
        waitForIdle()
        // Only "etl" is listed: "report" and the deleted pod are Other.
        onAllNodesWithText("report").assertCountEquals(0)
        val asked = source.calls.size
        assertEquals(2, asked)

        apps = listOf(app("etl"), app("report"))
        waitForIdle()

        onAllNodesWithText("report").assertCountEquals(2)
        assertEquals(asked, source.calls.size, "the list re-emits on every cluster tick and must not refetch")
    }

    @Test
    fun `a collapsed card asks nothing and draws no chart`() = runComposeUiTest {
        val source = FakeSparkSource()
        showCard(serviceOver(GmpSetting, source), expanded = { false })
        waitForIdle()

        onNodeWithText(SPARK_HISTORY_TITLE).assertExists()
        onNodeWithText("CPU").assertDoesNotExist()
        onNodeWithText(SPARK_HISTORY_NOTE).assertDoesNotExist()
        assertTrue(source.calls.isEmpty(), "a collapsed card must not ask: ${source.calls}")
    }

    @Test
    fun `without a history source there is no card`() = runComposeUiTest {
        val source = FakeSparkSource()
        showCard(serviceOver(OffSetting, source))
        waitForIdle()

        onNodeWithText(SPARK_HISTORY_TITLE).assertDoesNotExist()
        assertTrue(source.calls.isEmpty(), "no source may be asked: ${source.calls}")
    }

    @Test
    fun `a structural failure collapses the charts to the reason with no Retry`() = runComposeUiTest {
        val source = FakeSparkSource(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"))
        showCard(serviceOver(GmpSetting, source))
        waitForIdle()

        onNodeWithText("No access to Managed Prometheus", substring = true).assertExists()
        onAllNodesWithText("CPU").assertCountEquals(0)
        onAllNodesWithText("Memory").assertCountEquals(0)
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText(SPARK_HISTORY_NOTE).assertDoesNotExist()
        // The header stays, so a range that keeps failing can still be changed.
        onNodeWithText("6h ▾").assertExists()
    }
}
