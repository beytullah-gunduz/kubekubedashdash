package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.services.metricshistory.HistoryFailure
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
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

private const val NOW = 86_400L * 100

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

/** One query the fake source answered. */
private data class Call(val kind: PodSeriesKind, val pod: String, val window: QueryWindow)

/** Answers every kind with two points; records what it was asked. [failure] answers every kind with that failure instead. */
private class FakeSource(private val failure: SeriesResult.Failed? = null, private val failKinds: Set<PodSeriesKind> = emptySet()) : MetricsHistorySource {
    override val label = "Fake source"
    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult {
        calls += Call(kind, pod.name, window)
        if (failure != null && (failKinds.isEmpty() || kind in failKinds)) return failure
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
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private fun settingsOf(setting: MetricsSourceSetting) = MutableStateFlow(mapOf(DemoContext.preferenceKey(CONTEXT) to setting))

    private fun serviceOver(settings: MutableStateFlow<Map<String, MetricsSourceSetting>>, source: MetricsHistorySource) = MetricsHistoryService(
        currentContext = { CONTEXT },
        isDemo = { false },
        settingsFlow = { settings },
        gmpSourceFor = { source },
        demoSource = source,
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
}
