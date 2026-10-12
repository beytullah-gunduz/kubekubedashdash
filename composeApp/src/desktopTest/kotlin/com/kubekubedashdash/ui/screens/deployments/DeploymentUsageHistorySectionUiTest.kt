package com.kubekubedashdash.ui.screens.deployments

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.OwnerRefInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.ClusterStub
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.GmpTarget
import com.kubekubedashdash.services.metricshistory.HistoryFailure
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
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.deploymentPodSet
import com.kubekubedashdash.services.metricshistory.dummyClient
import com.kubekubedashdash.services.metricshistory.jsonResponse
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.components.MAX_CHART_SERIES
import com.kubekubedashdash.ui.components.podSetHue
import com.kubekubedashdash.ui.screens.pods.LOADING_HISTORY_NOTE
import com.kubekubedashdash.ui.screens.pods.LoadedHistory
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CONTEXT = "example-context"

private const val NOW = 86_400L * 100

private const val NAMESPACE = "production"

private const val DEPLOYMENT_UID = "uid-deploy-1"

private const val REVISION_ANNOTATION = "deployment.kubernetes.io/revision"

private const val CURRENT = "redis-cache-7c8d9e0f1"
private const val PREVIOUS = "redis-cache-6d9f8c7b5"
private const val OLDEST = "redis-cache-5f7a3b1d0"

private val GmpSetting = MetricsSourceSetting(MetricsSourceMode.GMP, "example-project", "us-central1", "example-cluster")

private val InClusterSetting = MetricsSourceSetting(MetricsSourceMode.IN_CLUSTER, namespace = "monitoring", service = "frontend", port = "80")

private val Limited = listOf(
    ContainerResources("redis", cpuRequestMillis = 100, cpuLimitMillis = 500, memoryRequestBytes = 64L shl 20, memoryLimitBytes = 128L shl 20),
    ContainerResources("sidecar", cpuRequestMillis = 50, cpuLimitMillis = 100, memoryRequestBytes = 32L shl 20, memoryLimitBytes = 64L shl 20),
)

private val Unlimited = listOf(ContainerResources("redis"), ContainerResources("sidecar"))

private val AllKinds = PodSeriesKind.entries.toSet()

private fun podName(replicaSet: String, suffix: String) = "$replicaSet-$suffix"

/** A chart legend entry that counts the grey lines ("3 earlier pods"), unlike prose that happens to say it. */
private val earlierPodsEntry = SemanticsMatcher("is an earlier-pods legend entry") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("""\d+ earlier pods?""").matches(it.text) }
}

/** One query the fake source answered. */
private data class Call(val kind: PodSeriesKind, val mode: PodSetMode, val pods: PodSetRef, val window: QueryWindow)

/**
 * Answers a per-pod query with one series for each of [podNames], the later ones with the higher peak,
 * and a total with one unnamed series; records what it was asked. [failure] answers every kind (or
 * [failKinds]) with that failure instead, and [empty] answers every kind with no series.
 */
private class FakeSource(
    private val podNames: List<String> = emptyList(),
    private val failure: SeriesResult.Failed? = null,
    private val failKinds: Set<PodSeriesKind> = emptySet(),
    private val empty: Boolean = false,
    override val label: String = "Fake source",
) : MetricsHistorySource {
    val calls = CopyOnWriteArrayList<Call>()

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

    override suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult {
        calls += Call(kind, mode, pods, window)
        if (failure != null && (failKinds.isEmpty() || kind in failKinds)) return failure
        if (empty) return SeriesResult.Ok(emptyList())

        fun points(peak: Double) = listOf(SeriesPoint(window.startEpochSec, 1.0), SeriesPoint(window.endEpochSec, peak))
        return SeriesResult.Ok(
            when (mode) {
                PodSetMode.Total -> listOf(Series("", points(2.0)))
                PodSetMode.PerPod -> podNames.mapIndexed { index, name -> Series(name, points(2.0 + index)) }
            },
        )
    }
}

/**
 * [DeploymentUsageHistorySection] over fake sources: the Settings flow is a plain [MutableStateFlow]
 * handed to the service's internal constructor, so nothing here reads the preference store, the
 * network or gcloud. `refreshMs = Long.MAX_VALUE` keeps the refresh loop from ever scheduling, except
 * in the case that drives it with the test clock.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DeploymentUsageHistorySectionUiTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    // Unconfined: a search whose lambda does not suspend finishes inside the call that starts it, so
    // the tests need no sleeping to see its answer.
    private val detectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = AtomicLong(1_000_000)

    // Only the test that goes through the real in-cluster source needs the loopback API server.
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
            shutdownCleanly(detectionScope, label = "DeploymentUsageHistorySectionUiTest")
            if (stubDelegate.isInitialized()) stub.close("DeploymentUsageHistorySectionUiTest")
        } finally {
            ThemeManager.syncStyleFromPreferences(originalStyle)
            // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
            // mode put it, so restore the flag first through the matching explicit mode.
            ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
            ThemeManager.syncFromPreferences(originalMode)
        }
    }

    private fun settingsOf(setting: MetricsSourceSetting) = MutableStateFlow(mapOf(DemoContext.preferenceKey(CONTEXT) to setting))

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

    private val deployment = DeploymentInfo(
        uid = DEPLOYMENT_UID,
        name = "redis-cache",
        namespace = NAMESPACE,
        ready = "2/2",
        upToDate = 2,
        available = 2,
        age = "3d",
        strategy = "RollingUpdate",
        labels = emptyMap(),
        annotations = emptyMap(),
        conditions = emptyList(),
    )

    private fun replicaSet(
        name: String,
        revision: Long,
        createdEpochSec: Long?,
        owner: String = DEPLOYMENT_UID,
        namespace: String = NAMESPACE,
    ) = GenericResourceInfo(
        uid = "uid-rs-$name",
        name = name,
        namespace = namespace,
        status = "1/1",
        age = "1h",
        labels = emptyMap(),
        annotations = mapOf(REVISION_ANNOTATION to "$revision"),
        owners = listOf(OwnerRefInfo(kind = "Deployment", name = "redis-cache", uid = owner, controller = true)),
        creationTimestamp = createdEpochSec?.let { Instant.ofEpochSecond(it).toString() }.orEmpty(),
    )

    /** Revisions 3 (current) and 2 started inside the 6 h window; revision 1 three days before it. */
    private val replicaSets = listOf(
        replicaSet(CURRENT, revision = 3, createdEpochSec = NOW - 3_600),
        replicaSet(PREVIOUS, revision = 2, createdEpochSec = NOW - 4 * 3_600),
        replicaSet(OLDEST, revision = 1, createdEpochSec = NOW - 3 * 86_400),
    )

    private fun pod(replicaSet: String, suffix: String, resources: List<ContainerResources> = Limited, namespace: String = NAMESPACE) = PodInfo(
        uid = "uid-$replicaSet-$suffix",
        name = podName(replicaSet, suffix),
        namespace = namespace,
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
    )

    private val pods = listOf(
        pod(CURRENT, "x7k2p"),
        pod(CURRENT, "92f6w"),
        pod(PREVIOUS, "h4m8q", Unlimited),
    )

    /** Two pods of the current ReplicaSet, one each of the two before it. */
    private val seriesNames = listOf(
        podName(CURRENT, "x7k2p"),
        podName(CURRENT, "92f6w"),
        podName(PREVIOUS, "h4m8q"),
        podName(OLDEST, "c3n5v"),
    )

    private fun ComposeUiTest.show(
        service: MetricsHistoryService,
        replicaSets: List<GenericResourceInfo> = this@DeploymentUsageHistorySectionUiTest.replicaSets,
        pods: List<PodInfo> = this@DeploymentUsageHistorySectionUiTest.pods,
        refreshMs: Long = Long.MAX_VALUE,
        width: Dp = 640.dp,
    ) {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(width)) { DeploymentUsageHistorySection(deployment, service, replicaSets, pods, { NOW }, refreshMs) }
            }
        }
        waitForIdle()
    }

    private val Call.pair get() = kind to mode

    // ── Per pod ─────────────────────────────────────────────────────────────

    private fun ComposeUiTest.assertPerPodCharts() {
        val source = FakeSource(seriesNames)
        show(serviceOver(settingsOf(GmpSetting), source))

        onNodeWithText("Fake source").assertExists()
        onNodeWithText("6h ▾").assertExists()
        listOf("CPU", "Memory", "Network", "CPU throttling").forEach { onNodeWithText(it).assertExists() }
        // CPU, memory and throttling each list the two current pods in colour; the older ones share one entry.
        listOf(podName(CURRENT, "x7k2p"), podName(CURRENT, "92f6w")).forEach { onAllNodesWithText(it).assertCountEquals(3) }
        listOf(podName(PREVIOUS, "h4m8q"), podName(OLDEST, "c3n5v")).forEach { onAllNodesWithText(it).assertCountEquals(0) }
        onAllNodesWithText("2 earlier pods").assertCountEquals(3)
        onNodeWithText("Received").assertExists()
        onNodeWithText("Sent").assertExists()
        // Both rollouts inside the window are listed in every chart; the one three days back is not.
        onAllNodesWithText("rev 3").assertCountEquals(4)
        onAllNodesWithText("rev 2").assertCountEquals(4)
        onAllNodesWithText("rev 1").assertCountEquals(0)
        assertEquals(AllKinds, source.calls.map { it.kind }.toSet())
        // Short ReplicaSet names fit whole in their pods' names: the pattern is exact.
        onNodeWithText(CUT_POD_NAMES_NOTE).assertDoesNotExist()
    }

    @Test
    fun `ReplicaSet names cut short in their pods' names are called out under the charts`() = runComposeUiTest {
        // A 57-character Deployment name: both revisions' pods are named from the same 58 characters.
        val longName = "redis-cache-" + "x".repeat(45)
        val cut = listOf(
            replicaSet("$longName-5f7a3b1d0", revision = 2, createdEpochSec = NOW - 3_600),
            replicaSet("$longName-6d9f8c7b5", revision = 1, createdEpochSec = NOW - 4 * 3_600),
        )
        show(serviceOver(settingsOf(GmpSetting), FakeSource(seriesNames)), replicaSets = cut)

        onNodeWithText("CPU").assertExists()
        onNodeWithText(CUT_POD_NAMES_NOTE).assertExists()
    }

    @Test
    fun `Per pod colours the current pods and mutes the older ones in the default style`() = runComposeUiTest {
        assertPerPodCharts()
    }

    @Test
    fun `Per pod draws the same charts in the Retro style`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertPerPodCharts()
    }

    @Test
    fun `in a narrow Retro panel a long source label gives way, and the toggles and the range menu stay whole`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        val source = FakeSource(seriesNames, label = "Prometheus · monitoring/prometheus-operated")
        show(serviceOver(settingsOf(GmpSetting), source), width = 460.dp)

        // Squeezed out, a control is laid out with no width at all and cannot be clicked.
        for (control in listOf("Per pod", "Total", "6h ▾")) {
            val bounds = onNodeWithText(control).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 24f, "$control is squeezed to $bounds")
        }
        onNodeWithText("Total").performClick()
        waitForIdle()
        assertTrue(source.calls.any { it.mode == PodSetMode.Total && it.kind == PodSeriesKind.Cpu }, "the Total toggle is reachable")
    }

    @Test
    fun `the first round asks every kind over the whole ReplicaSet list, over six hours, network as a total`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        show(serviceOver(settingsOf(GmpSetting), source))

        assertEquals(5, source.calls.size)
        assertEquals(
            setOf(
                PodSeriesKind.Cpu to PodSetMode.PerPod,
                PodSeriesKind.Memory to PodSetMode.PerPod,
                PodSeriesKind.Throttling to PodSetMode.PerPod,
                PodSeriesKind.NetworkRx to PodSetMode.Total,
                PodSeriesKind.NetworkTx to PodSetMode.Total,
            ),
            source.calls.map { it.pair }.toSet(),
        )
        assertEquals(setOf(PodSetRef(NAMESPACE, listOf(OLDEST, PREVIOUS, CURRENT).sorted())), source.calls.map { it.pods }.toSet())
        assertEquals(setOf(21_600L to 180L), source.calls.map { it.window.endEpochSec - it.window.startEpochSec to it.window.stepSec }.toSet())
    }

    @Test
    fun `request and limit lines are the first current pod's sums over its containers, in Per pod only`() = runComposeUiTest {
        show(serviceOver(settingsOf(GmpSetting), FakeSource(seriesNames)))

        // 100m + 50m and 500m + 100m; 64 MiB + 32 MiB and 128 MiB + 64 MiB.
        onNodeWithText("request 150m", substring = true).assertExists()
        onNodeWithText("limit 600m", substring = true).assertExists()
        onNodeWithText("request ${formatBytesValue(96.0 * (1 shl 20))}", substring = true).assertExists()
        onNodeWithText("limit ${formatBytesValue(192.0 * (1 shl 20))}", substring = true).assertExists()

        onNodeWithText("Total").performClick()
        waitForIdle()

        onAllNodesWithText("request", substring = true).assertCountEquals(0)
        onAllNodesWithText("limit", substring = true).assertCountEquals(0)
    }

    @Test
    fun `more than six current pods are counted in a line under the charts`() = runComposeUiTest {
        val names = listOf("x7k2p", "92f6w", "a1b2c", "d3e4f", "g5h6i", "j7k8l", "m9n0o", "p1q2r").map { podName(CURRENT, it) }
        show(serviceOver(settingsOf(GmpSetting), FakeSource(names)))

        onNodeWithText("2 more pods are not drawn — switch to Total.").assertExists()
        // The chart's own count stays as it was, and the two lowest peaks are the ones left out.
        onAllNodesWithText("+2 more").assertCountEquals(3)
        names.take(2).forEach { onAllNodesWithText(it).assertCountEquals(0) }
        names.drop(2).forEach { onAllNodesWithText(it).assertCountEquals(3) }
        onAllNodes(earlierPodsEntry).assertCountEquals(0)

        onNodeWithText("Total").performClick()
        waitForIdle()

        onAllNodesWithText("not drawn", substring = true).assertCountEquals(0)
    }

    @Test
    fun `one pod too many is worded in the singular`() = runComposeUiTest {
        val seven = listOf("x7k2p", "92f6w", "a1b2c", "d3e4f", "g5h6i", "j7k8l", "m9n0o").map { podName(CURRENT, it) }
        assertEquals(MAX_CHART_SERIES + 1, seven.size)
        show(serviceOver(settingsOf(GmpSetting), FakeSource(seven)))

        onNodeWithText("1 more pod is not drawn — switch to Total.").assertExists()
    }

    @Test
    fun `six current pods or fewer say nothing`() = runComposeUiTest {
        val six = listOf("x7k2p", "92f6w", "a1b2c", "d3e4f", "g5h6i", "j7k8l").map { podName(CURRENT, it) }
        assertEquals(MAX_CHART_SERIES, six.size)
        show(serviceOver(settingsOf(GmpSetting), FakeSource(six)))

        onAllNodesWithText("not drawn", substring = true).assertCountEquals(0)
        onAllNodesWithText("more", substring = true).assertCountEquals(0)
        six.forEach { onAllNodesWithText(it).assertCountEquals(3) }
    }

    // ── Total ───────────────────────────────────────────────────────────────

    @Test
    fun `Total gives one line per chart and keeps the rollout markers`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        show(serviceOver(settingsOf(GmpSetting), source))
        onNodeWithText("2.00 cores").assertDoesNotExist()

        onNodeWithText("Total").performClick()
        waitForIdle()

        // A chart with a single coloured line prints its last value in the title row, which only the CPU chart does here.
        onNodeWithText("2.00 cores").assertExists()
        seriesNames.forEach { onAllNodesWithText(it).assertCountEquals(0) }
        onAllNodes(earlierPodsEntry).assertCountEquals(0)
        onAllNodesWithText("rev 3").assertCountEquals(4)
        onAllNodesWithText("rev 2").assertCountEquals(4)
        onNodeWithText("Received").assertExists()
        // The markers give CPU, memory and throttling a legend, where the sum is listed by name.
        onAllNodesWithText(TOTAL_SERIES_NAME).assertCountEquals(3)
    }

    @Test
    fun `switching the mode asks all five kinds again with that mode, network always as a total`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        show(serviceOver(settingsOf(GmpSetting), source))
        assertEquals(5, source.calls.size)

        onNodeWithText("Total").performClick()
        waitForIdle()

        assertEquals(10, source.calls.size)
        assertEquals(
            setOf(
                PodSeriesKind.Cpu to PodSetMode.Total,
                PodSeriesKind.Memory to PodSetMode.Total,
                PodSeriesKind.Throttling to PodSetMode.Total,
                PodSeriesKind.NetworkRx to PodSetMode.Total,
                PodSeriesKind.NetworkTx to PodSetMode.Total,
            ),
            source.calls.drop(5).map { it.pair }.toSet(),
        )

        onNodeWithText("Per pod").performClick()
        waitForIdle()

        assertEquals(15, source.calls.size)
        assertEquals(
            setOf(
                PodSeriesKind.Cpu to PodSetMode.PerPod,
                PodSeriesKind.Memory to PodSetMode.PerPod,
                PodSeriesKind.Throttling to PodSetMode.PerPod,
                PodSeriesKind.NetworkRx to PodSetMode.Total,
                PodSeriesKind.NetworkTx to PodSetMode.Total,
            ),
            source.calls.drop(10).map { it.pair }.toSet(),
        )
    }

    @Test
    fun `choosing another range asks again with that window`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        show(serviceOver(settingsOf(GmpSetting), source))

        onNodeWithText("6h ▾").performClick()
        onNodeWithText("24h").performClick()
        waitForIdle()

        val last = source.calls.last().window
        assertEquals(86_400L to 720L, last.endEpochSec - last.startEpochSec to last.stepSec)
        onNodeWithText("24h ▾").assertExists()
    }

    // ── what the section asks ───────────────────────────────────────────────

    @Test
    fun `throttling is asked for once a current pod has a CPU limit, and its chart says why it is empty before that`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        val service = serviceOver(settingsOf(GmpSetting), source)
        var informerPods by mutableStateOf(listOf(pod(PREVIOUS, "h4m8q", Limited), pod(CURRENT, "x7k2p", Unlimited)))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) {
                    DeploymentUsageHistorySection(deployment, service, replicaSets, informerPods, { NOW }, Long.MAX_VALUE)
                }
            }
        }
        waitForIdle()

        // Only an older pod has a limit: the current ones decide.
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertExists()
        assertTrue(source.calls.none { it.kind == PodSeriesKind.Throttling }, "throttling must not be queried without a limit on a current pod")
        assertEquals(4, source.calls.size)

        informerPods = pods
        waitForIdle()

        assertTrue(source.calls.any { it.kind == PodSeriesKind.Throttling }, "a current pod carries a CPU limit")
        onNodeWithText(NO_CURRENT_CPU_LIMIT_NOTE).assertDoesNotExist()
    }

    @Test
    fun `a refresh of an identical, fully answered window asks nothing`() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = FakeSource(seriesNames)
        val service = serviceOver(settingsOf(GmpSetting), source)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(640.dp)) { DeploymentUsageHistorySection(deployment, service, replicaSets, pods, { NOW }, 30_000L) }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(5, source.calls.size)

        mainClock.advanceTimeBy(30_001)
        waitForIdle()

        assertEquals(5, source.calls.size, "the same window, all answered: nothing to refetch")
        onNodeWithText("CPU throttling").assertExists()
    }

    // ── states ──────────────────────────────────────────────────────────────

    @Test
    fun `without a ReplicaSet of the Deployment the section says so and asks nothing`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        val strangers = listOf(replicaSet("frontend-7c8d9e0f1", 1, NOW - 60, owner = "uid-deploy-2"), replicaSet(CURRENT, 3, NOW - 60, namespace = "default"))
        show(serviceOver(settingsOf(GmpSetting), source), replicaSets = strangers)

        onNodeWithText(NO_REPLICASETS_NOTE).assertExists()
        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("CPU").assertDoesNotExist()
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
        assertTrue(source.calls.isEmpty(), "no query without a pod set: ${source.calls}")
    }

    @Test
    fun `with no history source the section renders nothing`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        show(serviceOver(settingsOf(MetricsSourceSetting(MetricsSourceMode.OFF)), source))

        onNodeWithText("Resource usage", ignoreCase = true).assertDoesNotExist()
        onNodeWithText("6h ▾").assertDoesNotExist()
        onNodeWithText("Per pod").assertDoesNotExist()
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `while Auto is still searching the header shows over the loading line and nothing is asked`() = runComposeUiTest {
        val source = FakeSource(seriesNames)
        val gate = CompletableDeferred<DetectionState>()
        show(serviceOver(MutableStateFlow(emptyMap()), source, detectionOf { gate.await() }))

        onNodeWithText("Resource usage", ignoreCase = true).assertExists()
        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        onNodeWithText("6h ▾").assertExists()
        assertTrue(source.calls.isEmpty(), "no source is asked while Auto searches: ${source.calls}")
    }

    @Test
    fun `while the first fetch is out a loading line shows and the range can still be changed`() = runComposeUiTest {
        val hanging = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = awaitCancellation()

            override suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult = awaitCancellation()
        }
        show(serviceOver(settingsOf(GmpSetting), hanging))

        onNodeWithText(LOADING_HISTORY_NOTE).assertExists()
        onNodeWithText("6h ▾").assertExists()
        onNodeWithText("Per pod").assertExists()
        onNodeWithText("CPU throttling").assertDoesNotExist()
    }

    @Test
    fun `when CPU and memory both fail the reason replaces the charts and never mentions live data`() = runComposeUiTest {
        val source = FakeSource(
            failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"),
            failKinds = setOf(PodSeriesKind.Cpu, PodSeriesKind.Memory),
        )
        show(serviceOver(settingsOf(GmpSetting), source))

        onNodeWithText("Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s.").assertExists()
        onAllNodesWithText("metrics-server", substring = true).assertCountEquals(0)
        onNodeWithText("CPU throttling").assertDoesNotExist()
        // The header stays, so a range or a mode that keeps failing can still be changed.
        onNodeWithText("Fake source").assertExists()
        onNodeWithText("6h ▾").assertExists()
        onNodeWithText("Total").assertExists()
    }

    @Test
    fun `when only the network fails its own chart says so and the others still draw`() = runComposeUiTest {
        val source = FakeSource(
            podNames = seriesNames,
            failure = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"),
            failKinds = setOf(PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx),
        )
        show(serviceOver(settingsOf(GmpSetting), source))

        onNodeWithText("Not answering (HTTP 503)").assertExists()
        onAllNodesWithText(podName(CURRENT, "x7k2p")).assertCountEquals(3)
    }

    @Test
    fun `a source that throws fails its chart without ending the section or showing the message`() = runComposeUiTest {
        val source = object : MetricsHistorySource {
            override val label = "Fake source"

            override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = SeriesResult.Failed(HistoryFailure.Unavailable, "unsupported")

            override suspend fun podSetSeries(kind: PodSeriesKind, pods: PodSetRef, mode: PodSetMode, window: QueryWindow): SeriesResult {
                if (kind == PodSeriesKind.Cpu) throw IllegalArgumentException("invalid header value: \"Bearer fake-token-1\"")
                return SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(window.startEpochSec, 1.0), SeriesPoint(window.endEpochSec, 2.0)))))
            }
        }
        show(serviceOver(settingsOf(GmpSetting), source))

        onNodeWithText("Not answering (error)").assertExists()
        onNodeWithText("Memory").assertExists()
        onAllNodesWithText("fake-token", substring = true).assertCountEquals(0)
    }

    // ── Managed Prometheus staleness ────────────────────────────────────────

    @Test
    fun `the staleness note shows under the charts for Managed Prometheus in both modes`() = runComposeUiTest {
        show(serviceOver(settingsOf(GmpSetting), FakeSource(seriesNames)))

        onNodeWithText(GMP_STALENESS_NOTE).assertExists()

        onNodeWithText("Total").performClick()
        waitForIdle()

        onNodeWithText(GMP_STALENESS_NOTE).assertExists()
    }

    @Test
    fun `the staleness note is absent for an in-cluster Prometheus, which marks vanished series stale`() = runComposeUiTest {
        val body = """{"status":"success","data":{"resultType":"matrix","result":[""" +
            """{"metric":{"pod":"${podName(CURRENT, "x7k2p")}"},"values":[[${NOW - 60},"1"],[$NOW,"2"]]}]}}"""
        stub.proxy = { jsonResponse(200, body) }
        show(serviceOver(settingsOf(InClusterSetting), FakeSource(), clientFor = { stubClient }))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("CPU throttling").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Prometheus · monitoring/frontend").assertExists()
        onAllNodesWithText(podName(CURRENT, "x7k2p")).assertCountEquals(3)
        onAllNodesWithText("Managed Prometheus", substring = true).assertCountEquals(0)
        onNodeWithText(GMP_STALENESS_NOTE).assertDoesNotExist()
        onNodeWithText(LOADING_HISTORY_NOTE).assertDoesNotExist()
    }

    // ── pure pieces ─────────────────────────────────────────────────────────

    @Test
    fun `current pods are the newest ReplicaSet's pods in the Deployment's namespace, by name`() {
        val podSet = deploymentPodSet(DEPLOYMENT_UID, NAMESPACE, replicaSets)
        val informer = listOf(
            pod(CURRENT, "x7k2p"),
            pod(CURRENT, "92f6w"),
            pod(PREVIOUS, "h4m8q"),
            pod(CURRENT, "a1b2c", namespace = "default"),
            pod("$CURRENT-extra", "d3e4f"),
        )

        assertEquals(listOf(podName(CURRENT, "92f6w"), podName(CURRENT, "x7k2p")), currentPodsOf(deployment, podSet, informer).map { it.name })
        assertEquals(emptyList(), currentPodsOf(deployment, null, informer))
    }

    @Test
    fun `a ReplicaSet name longer than the generated base is cut where its pods are`() {
        val long = "redis-cache-" + "a".repeat(55)
        val podSet = deploymentPodSet(DEPLOYMENT_UID, NAMESPACE, listOf(replicaSet(long, 1, NOW - 60)))
        val cut = long.take(58) + "x7k2p"
        val informer = listOf(pod(CURRENT, "x7k2p").copy(name = cut), pod(long, "92f6w"))

        assertEquals(listOf(cut), currentPodsOf(deployment, podSet, informer).map { it.name })
    }

    @Test
    fun `throttling joins the kinds only when a current pod has a CPU limit`() {
        val network = listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx)

        assertEquals(network, deploymentKinds(emptyList()))
        assertEquals(network, deploymentKinds(listOf(pod(CURRENT, "x7k2p", Unlimited))))
        assertEquals(network + PodSeriesKind.Throttling, deploymentKinds(listOf(pod(CURRENT, "x7k2p", Unlimited), pod(CURRENT, "92f6w", Limited))))
    }

    @Test
    fun `a host-network current pod leaves network out, as its counters are the node's`() {
        assertEquals(
            listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory),
            deploymentKinds(listOf(pod(CURRENT, "x7k2p", Unlimited), pod(CURRENT, "92f6w", Unlimited).copy(hostNetwork = true))),
        )
    }

    @Test
    fun `a pod's references are sums over its containers, omitted when any container lacks the value`() {
        val partial = pod(CURRENT, "x7k2p", listOf(ContainerResources("redis", cpuRequestMillis = 100, cpuLimitMillis = 500), ContainerResources("sidecar", cpuRequestMillis = 50)))

        assertEquals(
            listOf("request" to 0.15, "limit" to 0.6),
            podSumReferences(pod(CURRENT, "x7k2p"), PodSeriesKind.Cpu).map { it.label to it.value },
        )
        assertEquals(
            listOf(true, false),
            podSumReferences(pod(CURRENT, "x7k2p"), PodSeriesKind.Memory).map { it.dashed },
        )
        assertEquals(listOf("request" to 0.15), podSumReferences(partial, PodSeriesKind.Cpu).map { it.label to it.value })
        assertEquals(emptyList(), podSumReferences(partial, PodSeriesKind.Memory))
        assertEquals(emptyList(), podSumReferences(pod(CURRENT, "x7k2p", emptyList()), PodSeriesKind.Cpu))
        assertEquals(emptyList(), podSumReferences(null, PodSeriesKind.Cpu))
        assertEquals(emptyList(), podSumReferences(pod(CURRENT, "x7k2p"), PodSeriesKind.NetworkRx))
    }

    @Test
    fun `current series are coloured by sorted name and ordered by peak, the rest are grey and muted`() {
        fun series(name: String, peak: Double) = Series(name, listOf(SeriesPoint(0, 0.0), SeriesPoint(30, peak)))
        val prefix = "$CURRENT-"
        val a = podName(CURRENT, "aaaaa")
        val b = podName(CURRENT, "bbbbb")
        val c = podName(CURRENT, "ccccc")
        val old = podName(PREVIOUS, "h4m8q")
        val result = SeriesResult.Ok(listOf(series(a, 1.0), series(b, 5.0), series(old, 9.0), series(c, 3.0)))
        val colorIndex = mapOf(a to 0, b to 1, c to 2)

        val drawn = perPodChartSeries(result, prefix, colorIndex)

        assertEquals(listOf(b, c, a, old), drawn.map { it.name })
        assertEquals(listOf(false, false, false, true), drawn.map { it.muted })
        assertEquals(
            listOf(1, 2, 0).map { ClusterColor(hue = podSetHue(it)).composeColor },
            drawn.take(3).map { it.color },
        )
        assertEquals(KdTextSecondary.copy(alpha = 0.5f), drawn.last().color)
        // No current ReplicaSet: every line is background.
        assertTrue(perPodChartSeries(result, null, colorIndex).all { it.muted })
        assertEquals(emptyList(), perPodChartSeries(SeriesResult.Failed(HistoryFailure.Unavailable), prefix, colorIndex))
    }

    @Test
    fun `a total is one line in the primary colour, named for the legend the markers bring`() {
        val result = SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(0, 1.0)))))

        val drawn = totalChartSeries(result).single()

        assertEquals(TOTAL_SERIES_NAME, drawn.name)
        assertEquals(KdPrimary, drawn.color)
        assertEquals(false, drawn.muted)
        assertEquals(emptyList(), totalChartSeries(SeriesResult.Ok(emptyList())))
        assertEquals(emptyList(), totalChartSeries(null))
    }

    @Test
    fun `pods beyond the six drawn are counted over CPU and memory together`() {
        fun ok(vararg names: String) = SeriesResult.Ok(names.map { Series(it, emptyList()) })
        val prefix = "$CURRENT-"
        fun names(vararg suffix: String) = suffix.map { podName(CURRENT, it) }.toTypedArray()

        val six = names("aaaaa", "bbbbb", "ccccc", "ddddd", "eeeee", "fffff")
        assertEquals(0, notDrawnCurrentPods(mapOf(PodSeriesKind.Cpu to ok(*six), PodSeriesKind.Memory to ok(*six)), prefix))
        // CPU has six, memory has a seventh: the union is seven.
        val seven = names("aaaaa", "bbbbb", "ccccc", "ddddd", "eeeee", "ggggg")
        assertEquals(1, notDrawnCurrentPods(mapOf(PodSeriesKind.Cpu to ok(*six), PodSeriesKind.Memory to ok(*seven)), prefix))
        // Older pods are not counted, and neither is a failed answer.
        assertEquals(0, notDrawnCurrentPods(mapOf(PodSeriesKind.Cpu to ok(*six, podName(PREVIOUS, "h4m8q")), PodSeriesKind.Memory to SeriesResult.Failed(HistoryFailure.Unavailable)), prefix))
        assertEquals(0, notDrawnCurrentPods(emptyMap(), null))
    }

    @Test
    fun `the failure texts never point at live metrics-server data and keep the retry hint`() {
        val gmp = SourceChoice.Gmp(GmpTarget("example-project", "us-central1", "example-cluster"), auto = false)
        val inCluster = listOf(true, false).map { SourceChoice.InCluster(InClusterTarget("monitoring", "frontend", "80", https = false, pathPrefix = ""), auto = it) }
        val choices = listOf(gmp) + inCluster + SourceChoice.Detecting + SourceChoice.None(NoSourceReason.Disabled)

        for (choice in choices) {
            for (failure in HistoryFailure.entries) {
                val text = deploymentFailureMessage(choice, SeriesResult.Failed(failure, "HTTP 500"))
                assertTrue(text.isNotBlank(), "$choice $failure")
                assertTrue("metrics-server" !in text && !text.contains("showing live", ignoreCase = true), "$choice $failure: $text")
            }
        }
        assertEquals(
            "Managed Prometheus didn't answer (HTTP 503). Retrying every 30 s.",
            deploymentFailureMessage(gmp, SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")),
        )
        assertEquals(
            "Prometheus didn't answer (HTTP 503). Retrying every 30 s.",
            deploymentFailureMessage(inCluster.first(), SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")),
        )
        assertEquals(
            "Service monitoring/frontend no longer exists. Searching again.",
            deploymentFailureMessage(inCluster.first(), SeriesResult.Failed(HistoryFailure.ServiceGone)),
        )
        assertEquals(
            "Service monitoring/frontend no longer exists. Check Settings → Metrics history.",
            deploymentFailureMessage(inCluster.last(), SeriesResult.Failed(HistoryFailure.ServiceGone)),
        )
    }

    @Test
    fun `the headline failure is CPU's first, and only when neither chart has a point`() {
        val cpu = SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503")
        val memory = SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403")
        val window = QueryWindow(0, 60, 30)
        val point = SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(0, 1.0)))))

        assertEquals(cpu, headlineFailure(loaded(window, PodSeriesKind.Cpu to cpu, PodSeriesKind.Memory to memory)))
        assertEquals(memory, headlineFailure(loaded(window, PodSeriesKind.Cpu to SeriesResult.Ok(emptyList()), PodSeriesKind.Memory to memory)))
        assertNull(headlineFailure(loaded(window, PodSeriesKind.Cpu to cpu, PodSeriesKind.Memory to point)))
        assertNull(headlineFailure(loaded(window, PodSeriesKind.Cpu to SeriesResult.Ok(emptyList()), PodSeriesKind.Memory to SeriesResult.Ok(emptyList()))))
    }

    private fun loaded(window: QueryWindow, vararg results: Pair<PodSeriesKind, SeriesResult>) = LoadedHistory(window, results.toMap())
}
