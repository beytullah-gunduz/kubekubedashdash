package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.InClusterDetection
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.ui.components.CHART_SKELETON_TAG
import com.kubekubedashdash.ui.components.LOADING_HISTORY_NOTE
import com.kubekubedashdash.ui.screens.pods.formatBytesValue
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

private const val NOW = 86_400L * 100

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

private val OffSetting = MetricsSourceSetting(MetricsSourceMode.OFF)

private const val PEAK_MEMORY_BYTES = 512.0 * 1024 * 1024

/** One query the fake source answered. */
private data class Call(val kind: ScopeKind, val scope: ScopeRef, val window: QueryWindow)

/**
 * Answers a scope query with one unnamed series of two points (CPU ends at half a core, memory at
 * [PEAK_MEMORY_BYTES]); records what it was asked. [failure] answers every kind (or [failKinds]) with
 * that failure instead (it can be cleared between refreshes), and [emptyKinds] answer with no series, as Managed Prometheus does when it has
 * no samples.
 */
private class FakeSource(
    @Volatile var failure: SeriesResult.Failed? = null,
    private val failKinds: Set<ScopeKind> = emptySet(),
    private val emptyKinds: Set<ScopeKind> = emptySet(),
) : MetricsHistorySource {
    override val label = "Fake source"
    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    override suspend fun scopeSeries(kind: ScopeKind, scope: ScopeRef, window: QueryWindow): SeriesResult {
        calls += Call(kind, scope, window)
        failure?.let { if (failKinds.isEmpty() || kind in failKinds) return it }
        if (kind in emptyKinds) return SeriesResult.Ok(emptyList())
        val peak = if (kind == ScopeKind.CpuCores) 0.5 else PEAK_MEMORY_BYTES
        return SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(window.startEpochSec, peak / 2), SeriesPoint(window.endEpochSec, peak)))))
    }
}

/**
 * [ClusterUsageHistoryCard] over fake sources: the Settings flow is a plain [MutableStateFlow] handed to
 * the service's internal constructor, so nothing here reads the preference store, the network or gcloud.
 * The card takes its expanded state as a parameter, so no test writes the stored panel preference.
 * `refreshMs = Long.MAX_VALUE` keeps the refresh loop from ever scheduling, except in the one case that
 * drives it with the test clock.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ClusterUsageHistoryCardUiTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    // Unconfined: a search whose lambda does not suspend finishes inside the call that starts it, so
    // the tests need no sleeping to see its answer.
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
            shutdownCleanly(detectionScope, label = "ClusterUsageHistoryCardUiTest")
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
    ) = MetricsHistoryService(
        currentContext = { CONTEXT },
        settingsFlow = { settings },
        gmpSourceFor = { source },
        detection = detection,
        clientFor = { dummyClient() },
        nowMs = clock::get,
    )

    /** The card inside a tagged host column, so a test can measure what the card leaves behind. */
    private fun ComposeUiTest.showCard(
        service: MetricsHistoryService,
        scope: () -> NamespaceScope = { NamespaceScope.single("team-a") },
        expanded: () -> Boolean = { true },
        onToggle: () -> Unit = {},
        now: () -> Long = { NOW },
        refreshMs: Long = Long.MAX_VALUE,
        width: Dp = 900.dp,
    ) = setContent {
        MaterialTheme(typography = kdTypography()) {
            Column(Modifier.width(width).testTag("host")) { ClusterUsageHistoryCard(service, scope(), expanded(), onToggle, now, refreshMs) }
        }
    }

    private val teamA = ScopeRef(setOf("team-a"))

    private fun ComposeUiTest.assertNoCard() {
        onNodeWithText(USAGE_HISTORY_TITLE).assertDoesNotExist()
        onNodeWithText("Fake source").assertDoesNotExist()
        // Not even the 24 dp above it.
        onNodeWithTag("host").assertHeightIsEqualTo(0.dp)
    }

    @Test
    fun `two charts draw the summed lines under the source and the note`() = runComposeUiTest {
        val source = FakeSource()
        showCard(serviceOver(settingsOf(GmpSetting), source))
        waitForIdle()

        onNodeWithText(USAGE_HISTORY_TITLE).assertExists()
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("6h ▾").assertExists()
        listOf("CPU", "Memory").forEach { onNodeWithText(it).assertExists() }
        // Each chart's last value stands beside its title.
        onNodeWithText("500m").assertExists()
        onNodeWithText(formatBytesValue(PEAK_MEMORY_BYTES)).assertExists()
        onNodeWithText(usageHistoryNote("Fake source")).assertExists()
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()

        assertEquals(setOf(ScopeKind.CpuCores, ScopeKind.MemoryBytes), source.calls.map { it.kind }.toSet())
        assertEquals(setOf(teamA), source.calls.map { it.scope }.toSet())
        // The default range is six hours: 180 s steps.
        assertEquals(setOf(21_600L to 180L), source.calls.map { it.window.endEpochSec - it.window.startEpochSec to it.window.stepSec }.toSet())
    }

    @Test
    fun `a card that starts collapsed asks nothing and draws no chart`() = runComposeUiTest {
        val source = FakeSource()
        showCard(serviceOver(settingsOf(GmpSetting), source), expanded = { false })
        waitForIdle()

        // The header stays, so the card can be opened.
        onNodeWithText(USAGE_HISTORY_TITLE).assertExists()
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("CPU").assertDoesNotExist()
        onNodeWithText(usageHistoryNote("Fake source")).assertDoesNotExist()
        assertTrue(source.calls.isEmpty(), "a collapsed card must not ask: ${source.calls}")
    }

    @Test
    fun `collapsing stops the refresh loop and expanding asks for what moved on`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource()
        val nowSec = AtomicLong(NOW)
        var expanded by mutableStateOf(true)
        showCard(serviceOver(settingsOf(GmpSetting), source), expanded = { expanded }, onToggle = { expanded = !expanded }, now = nowSec::get, refreshMs = 30_000L)
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(2, source.calls.size, "the first round asks for CPU and memory")

        // The six-hour window moves every 180 s, so a later round asks again.
        nowSec.addAndGet(200)
        mainClock.advanceTimeBy(30_001)
        waitForIdle()
        assertEquals(4, source.calls.size)

        onNodeWithText(USAGE_HISTORY_TITLE).performClick()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertFalse(expanded, "the header toggles the card")
        val asked = source.calls.size
        nowSec.addAndGet(200)
        mainClock.advanceTimeBy(120_000)
        waitForIdle()
        assertEquals(asked, source.calls.size, "a collapsed card must not ask, however the window moved")
        onNodeWithText("CPU").assertDoesNotExist()

        onNodeWithText(USAGE_HISTORY_TITLE).performClick()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(source.calls.size > asked, "expanding asks for the window that moved meanwhile")
        onNodeWithText("CPU").assertExists()
    }

    @Test
    fun `a refresh of an identical, fully answered window asks nothing`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource()
        showCard(serviceOver(settingsOf(GmpSetting), source), refreshMs = 30_000L)
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(2, source.calls.size)

        mainClock.advanceTimeBy(30_001)
        waitForIdle()
        assertEquals(2, source.calls.size, "the same window, all answered: nothing to refetch")
    }

    /** Frames enough for a composition, the fetch its effect starts and the recomposition with the answer. */
    private fun ComposeUiTest.settle() {
        repeat(3) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
        }
    }

    @Test
    fun `a structural first answer swaps the loading charts for the reason, and a recovery swaps the charts back`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"))
        showCard(serviceOver(settingsOf(GmpSetting), source), refreshMs = 30_000L)
        settle()
        // Past the fade-through (90 ms out, 220 ms in), so only the new content is left.
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        onNodeWithText("No access to Managed Prometheus", substring = true).assertExists()
        onAllNodesWithText("CPU").assertCountEquals(0)
        onAllNodesWithText("Memory").assertCountEquals(0)
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
        onNodeWithText("Retry").assertDoesNotExist()

        source.failure = null
        mainClock.advanceTimeBy(30_001)
        settle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()

        onAllNodesWithText("CPU").assertCountEquals(1)
        onNodeWithText("500m").assertExists()
        onNodeWithText("No access to Managed Prometheus", substring = true).assertDoesNotExist()
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText(usageHistoryNote("Fake source")).assertExists()
    }

    @Test
    fun `a refresh that keeps the charts updates them in place, with no second copy fading out`() = runComposeUiTest {
        // Proxy for "the charts are not recreated": a swap (a new content key) fades the old content out while the
        // new one fades in, so two "CPU" titles would coexist for a few frames. A same-key refresh must never show
        // two, and never bring a skeleton back.
        mainClock.autoAdvance = false
        val source = FakeSource()
        val nowSec = AtomicLong(NOW)
        showCard(serviceOver(settingsOf(GmpSetting), source), now = nowSec::get, refreshMs = 30_000L)
        settle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onAllNodesWithText("CPU").assertCountEquals(1)
        assertEquals(2, source.calls.size)

        // The six-hour window moves every 180 s, so the next round asks again and answers with a new window.
        nowSec.addAndGet(200)
        mainClock.advanceTimeBy(29_000)
        waitForIdle()
        repeat(150) {
            mainClock.advanceTimeBy(16)
            waitForIdle()
            onAllNodesWithText("CPU").assertCountEquals(1)
            onAllNodesWithText("Memory").assertCountEquals(1)
            onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        }
        assertEquals(4, source.calls.size, "the refresh asked again and its answer landed while the charts were sampled")
        onNodeWithText("500m").assertExists()
    }

    @Test
    fun `a refresh repeats a round that did not fully answer`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"), failKinds = setOf(ScopeKind.MemoryBytes))
        showCard(serviceOver(settingsOf(GmpSetting), source), refreshMs = 30_000L)
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(2, source.calls.size)

        mainClock.advanceTimeBy(30_001)
        waitForIdle()
        assertEquals(4, source.calls.size, "a round with a failure is repeated whole")
    }

    @Test
    fun `a namespace selection change asks again with the new namespaces`() = runComposeUiTest {
        val source = FakeSource()
        var scope by mutableStateOf<NamespaceScope>(NamespaceScope.single("team-a"))
        showCard(serviceOver(settingsOf(GmpSetting), source), scope = { scope })
        waitForIdle()
        assertEquals(setOf(teamA), source.calls.map { it.scope }.toSet())

        scope = NamespaceScope.of(listOf("team-b", "team-a"))
        waitForIdle()
        assertEquals(ScopeRef(setOf("team-a", "team-b")), source.calls.last().scope)
        assertEquals(setOf(ScopeKind.CpuCores, ScopeKind.MemoryBytes), source.calls.takeLast(2).map { it.kind }.toSet())

        // All namespaces is an empty set, not the names that exist today.
        scope = NamespaceScope.All
        waitForIdle()
        assertEquals(ScopeRef(emptySet()), source.calls.last().scope)
        assertEquals(6, source.calls.size, "each selection asked for both kinds once")
        onNodeWithText("CPU").assertExists()
    }

    @Test
    fun `with history off there is no card and no spacing`() = runComposeUiTest {
        val source = FakeSource()
        showCard(serviceOver(settingsOf(OffSetting), source))
        waitForIdle()

        assertNoCard()
        assertTrue(source.calls.isEmpty(), "no source may be asked: ${source.calls}")
    }

    @Test
    fun `a source that appears flips the card in and one that goes takes it away`() = runComposeUiTest {
        val source = FakeSource()
        val settings = settingsOf(OffSetting)
        showCard(serviceOver(settings, source))
        waitForIdle()
        assertNoCard()

        settings.value = mapOf(DemoContext.preferenceKey(CONTEXT) to GmpSetting)
        waitForIdle()
        onNodeWithText(USAGE_HISTORY_TITLE).assertExists()
        onNodeWithText("CPU").assertExists()

        settings.value = mapOf(DemoContext.preferenceKey(CONTEXT) to OffSetting)
        waitForIdle()
        assertNoCard()
    }

    @Test
    fun `while Auto searches there is no card, but the Overview's card starts the search`() = runComposeUiTest {
        // CONTEXT is not a gke_ name and nothing is stored: Auto is still looking for a Prometheus.
        val source = FakeSource()
        val searches = AtomicInteger()
        val gate = CompletableDeferred<DetectionState>()
        val detection = detectionOf {
            searches.incrementAndGet()
            gate.await()
        }
        var scope by mutableStateOf<NamespaceScope>(NamespaceScope.All)
        showCard(serviceOver(MutableStateFlow(emptyMap()), source, detection), scope = { scope })
        waitForIdle()

        assertNoCard()
        assertEquals(1, searches.get(), "the card starts the search")

        // More recompositions while the search is out do not start another.
        scope = NamespaceScope.single("team-a")
        waitForIdle()
        assertNoCard()
        assertEquals(1, searches.get(), "a recomposition must not search again")

        gate.complete(DetectionState.NothingFound(clock.get()))
        waitForIdle()
        assertNoCard()
        assertEquals(1, searches.get())
        assertTrue(source.calls.isEmpty(), "no history source may be asked: ${source.calls}")
    }

    @Test
    fun `Auto that finds nothing leaves the Overview without the card`() = runComposeUiTest {
        val searches = AtomicInteger()
        val source = FakeSource()
        val detection = detectionOf {
            searches.incrementAndGet()
            DetectionState.NothingFound(clock.get())
        }
        showCard(serviceOver(MutableStateFlow(emptyMap()), source, detection))
        waitForIdle()

        assertNoCard()
        assertEquals(1, searches.get())
        assertTrue(source.calls.isEmpty(), "no history source may be asked: ${source.calls}")
    }

    @Test
    fun `while the first fetch is out a loading line stands in for the charts`() = runComposeUiTest {
        val hanging = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = awaitCancellation()

            override suspend fun scopeSeries(kind: ScopeKind, scope: ScopeRef, window: QueryWindow): SeriesResult = awaitCancellation()
        }
        showCard(serviceOver(settingsOf(GmpSetting), hanging))
        waitForIdle()

        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        // The charts are skeletons of their final size, so the titles show.
        onNodeWithText("CPU").assertExists()
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(2)
        onNodeWithText(usageHistoryNote("Fake source")).assertDoesNotExist()
        // The range can still be changed while it loads.
        onNodeWithText("6h ▾").assertExists()
    }

    @Test
    fun `a transient failure keeps the charts without a skeleton, and Retry asks again`() = runComposeUiTest {
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Unavailable, "timeout"))
        showCard(serviceOver(settingsOf(GmpSetting), source))
        waitForIdle()

        onNodeWithText("CPU").assertExists()
        onNodeWithText("Memory").assertExists()
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText("Retry").assertExists()
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
        assertEquals(2, source.calls.size)

        onNodeWithText("Retry").performClick()
        waitForIdle()

        assertEquals(4, source.calls.size, "Retry asks CPU and memory again, once")
        onNodeWithText("CPU").assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun `a structural failure collapses the charts to the reason with no Retry`() = runComposeUiTest {
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"))
        showCard(serviceOver(settingsOf(GmpSetting), source))
        waitForIdle()

        onNodeWithText("No access to Managed Prometheus", substring = true).assertExists()
        onNodeWithText("CPU").assertDoesNotExist()
        onNodeWithText("Memory").assertDoesNotExist()
        onAllNodesWithTag(CHART_SKELETON_TAG).assertCountEquals(0)
        onNodeWithText("Retry").assertDoesNotExist()
        onNodeWithText("6h ▾").assertExists()
    }

    @Test
    fun `when CPU and memory both fail transiently the charts stay and one line under them gives the reason`() = runComposeUiTest {
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"))
        showCard(serviceOver(settingsOf(GmpSetting), source))
        waitForIdle()

        onNodeWithText("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s.").assertExists()
        // A panel without a live fallback never points at metrics-server data.
        onAllNodesWithText("metrics-server", substring = true).assertCountEquals(0)
        // A transient failure keeps the frames: each chart says so in place, and the source note gives way to Retry.
        onNodeWithText("CPU").assertExists()
        onNodeWithText("Memory").assertExists()
        onAllNodesWithText("Not answering (HTTP 503)").assertCountEquals(2)
        onNodeWithText("Retry").assertExists()
        onNodeWithText(usageHistoryNote("Fake source")).assertDoesNotExist()
        // The header stays, so a range that keeps failing can still be changed.
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("6h ▾").assertExists()
    }

    @Test
    fun `when only memory fails its own chart says so and CPU still draws`() = runComposeUiTest {
        val source = FakeSource(failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"), failKinds = setOf(ScopeKind.MemoryBytes))
        showCard(serviceOver(settingsOf(GmpSetting), source))
        waitForIdle()

        onNodeWithText("Not answering (HTTP 503)").assertExists()
        onNodeWithText("500m").assertExists()
        onNodeWithText("Memory").assertExists()
        onNodeWithText(usageHistoryNote("Fake source")).assertExists()
    }

    @Test
    fun `an empty memory answer says so in its own chart`() = runComposeUiTest {
        showCard(serviceOver(settingsOf(GmpSetting), FakeSource(emptyKinds = setOf(ScopeKind.MemoryBytes))))
        waitForIdle()

        onNodeWithText("No data in this range").assertExists()
        onNodeWithText("500m").assertExists()
    }

    @Test
    fun `a source that throws fails its chart without ending the card or showing the message`() = runComposeUiTest {
        val source = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

            override suspend fun scopeSeries(kind: ScopeKind, scope: ScopeRef, window: QueryWindow): SeriesResult {
                if (kind == ScopeKind.MemoryBytes) throw IllegalArgumentException("invalid header value: \"Bearer fake-token-1\"")
                return SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(window.startEpochSec, 0.25), SeriesPoint(window.endEpochSec, 0.5)))))
            }
        }
        showCard(serviceOver(settingsOf(GmpSetting), source))
        waitForIdle()

        onNodeWithText("Not answering (error)").assertExists()
        onNodeWithText("500m").assertExists()
        onAllNodesWithText("fake-token", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the range menu opens without collapsing the card and a choice asks again with that window`() = runComposeUiTest {
        val source = FakeSource()
        var toggles = 0
        showCard(serviceOver(settingsOf(GmpSetting), source), onToggle = { toggles++ })
        waitForIdle()

        onNodeWithText("6h ▾").performClick()
        listOf("15m", "1h", "6h", "24h").forEach { onNodeWithText(it).assertExists() }
        assertEquals(0, toggles, "opening the menu must not toggle the card")
        onNodeWithText("CPU").assertExists()

        onNodeWithText("1h").performClick()
        waitForIdle()
        assertEquals(0, toggles, "choosing a range must not toggle the card")
        onNodeWithText("1h ▾").assertExists()
        val last = source.calls.last().window
        // One hour: 30 s steps.
        assertEquals(3_600L to 30L, last.endEpochSec - last.startEpochSec to last.stepSec)
        onNodeWithText("CPU").assertExists()
    }

    @Test
    fun `clicking the header asks to toggle`() = runComposeUiTest {
        var toggles = 0
        showCard(serviceOver(settingsOf(GmpSetting), FakeSource()), onToggle = { toggles++ })
        waitForIdle()

        onNodeWithText(USAGE_HISTORY_TITLE).performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun `from the split width the charts share a row`() = runComposeUiTest {
        // The card pads its body by 20 dp on each side; the split looks at what is left.
        val width = 700.dp
        assertTrue(usageSectionsSideBySide(width - 40.dp))
        showCard(serviceOver(settingsOf(GmpSetting), FakeSource()), width = width)
        waitForIdle()

        val cpu = onNodeWithText("CPU").fetchSemanticsNode().boundsInRoot
        val memory = onNodeWithText("Memory").fetchSemanticsNode().boundsInRoot
        assertEquals(cpu.top, memory.top, "side by side: the titles share a line")
        assertTrue(memory.left > cpu.right, "side by side: memory sits right of CPU")
    }

    @Test
    fun `below the split width the charts stack`() = runComposeUiTest {
        val width = 500.dp
        assertFalse(usageSectionsSideBySide(width - 40.dp))
        showCard(serviceOver(settingsOf(GmpSetting), FakeSource()), width = width)
        waitForIdle()

        val cpu = onNodeWithText("CPU").fetchSemanticsNode().boundsInRoot
        val memory = onNodeWithText("Memory").fetchSemanticsNode().boundsInRoot
        assertEquals(cpu.left, memory.left, "stacked: the titles share a column")
        assertTrue(memory.top > cpu.bottom, "stacked: memory sits below CPU")
        onNodeWithText(usageHistoryNote("Fake source")).assertExists()
    }
}
