package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GKE_CONTEXT = "gke_example-project_us-central1_example-cluster"
private const val RENAMED_CONTEXT = "example-context"

private val FOUND = InClusterTarget("monitoring", "prometheus-server", "80", https = false, pathPrefix = "")

private val noDetection = emptyMap<String, DetectionState>()

private fun detected(state: DetectionState) = mapOf(RENAMED_CONTEXT to state)

private val GKE_SETTING = MetricsSourceSetting(
    mode = MetricsSourceMode.GMP,
    project = "example-project",
    location = "us-central1",
    cluster = "example-cluster",
)

/** What a row's second line says (D15), for every way a context can resolve. */
class MetricsHistorySettingsTest {

    @Test
    fun `a gke context with no setting says Auto and names the resolved target`() {
        val status = metricsSourceStatus(GKE_CONTEXT, emptyMap(), noDetection)
        assertTrue(status.startsWith("Auto → Google Managed Prometheus · example-project"), status)
        assertEquals("Auto → Google Managed Prometheus · example-project · us-central1 · example-cluster", status)
    }

    @Test
    fun `a renamed context that has not been searched says when Auto will look`() {
        assertEquals(
            "Auto → looks for a Prometheus service when a pod's charts open.",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), noDetection),
        )
    }

    @Test
    fun `a search under way says so`() {
        assertEquals(
            "Auto → searching this cluster for a Prometheus service…",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), detected(DetectionState.Searching)),
        )
    }

    @Test
    fun `a found Prometheus is named with its namespace, service, port and prefix`() {
        assertEquals(
            "Auto → in-cluster Prometheus · monitoring/prometheus-server:80",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), detected(DetectionState.Found(FOUND))),
        )
        assertEquals(
            "Auto → in-cluster Prometheus · monitoring/prometheus-server:80/prom",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), detected(DetectionState.Found(FOUND.copy(pathPrefix = "/prom")))),
        )
    }

    @Test
    fun `an empty search result says why, and how to enter a Prometheus by hand`() {
        assertEquals(
            "Auto → none: no Prometheus service found. Pick In-cluster to enter one.",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), detected(DetectionState.NothingFound(1))),
        )
        assertEquals(
            "Auto → none: no permission to list services across namespaces. Pick In-cluster to enter one.",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), detected(DetectionState.CannotSearch(1))),
        )
    }

    @Test
    fun `a failed search says a pod's charts search again after a minute`() {
        assertEquals(
            "Auto → the search for a Prometheus service failed; a pod's charts search again after a minute.",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), detected(DetectionState.SearchFailed(1))),
        )
    }

    @Test
    fun `a typed in-cluster setting ignores what the search found and adds HTTPS when it is on`() {
        val typed = MetricsSourceSetting(mode = MetricsSourceMode.IN_CLUSTER, namespace = "monitoring", service = "prometheus-operated", port = "web", https = true)
        assertEquals(
            "In-cluster Prometheus · monitoring/prometheus-operated:web · HTTPS",
            metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to typed), detected(DetectionState.Found(FOUND))),
        )
        assertEquals(
            "In-cluster Prometheus · monitoring/prometheus-operated:web",
            metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to typed.copy(https = false)), noDetection),
        )
    }

    @Test
    fun `Off and Managed Prometheus ignore what the search found`() {
        val found = detected(DetectionState.Found(FOUND))
        assertEquals(
            "Off: pod charts show live metrics-server data only.",
            metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.OFF)), found),
        )
        assertEquals(
            "Google Managed Prometheus · example-project · us-central1 · example-cluster",
            metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to GKE_SETTING), found),
        )
    }

    @Test
    fun `a gke context stays on Managed Prometheus whatever the search found`() {
        assertEquals(
            "Auto → Google Managed Prometheus · example-project · us-central1 · example-cluster",
            metricsSourceStatus(GKE_CONTEXT, emptyMap(), mapOf(GKE_CONTEXT to DetectionState.Found(FOUND))),
        )
    }

    @Test
    fun `what the search found for another cluster is not shown on this one`() {
        assertEquals(
            "Auto → looks for a Prometheus service when a pod's charts open.",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap(), mapOf("other-context" to DetectionState.Found(FOUND))),
        )
    }

    @Test
    fun `an in-cluster setting names its target and drops the Auto prefix`() {
        val setting = MetricsSourceSetting(
            mode = MetricsSourceMode.IN_CLUSTER,
            namespace = "monitoring",
            service = "prometheus-server",
            port = "80",
            pathPrefix = "/prom/",
        )
        assertEquals(
            "In-cluster Prometheus · monitoring/prometheus-server:80/prom",
            metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to setting), noDetection),
        )
    }

    @Test
    fun `an incomplete in-cluster setting says what is missing and never echoes a bad value`() {
        val bad = MetricsSourceSetting(mode = MetricsSourceMode.IN_CLUSTER, namespace = "x/../y\"}", service = "prometheus-server", port = "80")
        val status = metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to bad), noDetection)
        assertEquals("Incomplete: enter a valid namespace, service and port.", status)
        assertFalse(status.contains("x/../y"), status)
    }

    @Test
    fun `a valid Managed Prometheus setting drops the Auto prefix`() {
        val status = metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to GKE_SETTING), noDetection)
        assertFalse(status.startsWith("Auto → "), status)
        assertEquals("Google Managed Prometheus · example-project · us-central1 · example-cluster", status)
    }

    @Test
    fun `Off says pod charts fall back to live data`() {
        val settings = mapOf(GKE_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.OFF))
        assertEquals("Off: pod charts show live metrics-server data only.", metricsSourceStatus(GKE_CONTEXT, settings, noDetection))
    }

    @Test
    fun `an incomplete Managed Prometheus setting says what is missing`() {
        val blank = mapOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.GMP))
        assertEquals("Incomplete: enter a valid project, location and cluster.", metricsSourceStatus(RENAMED_CONTEXT, blank, noDetection))
    }

    @Test
    fun `a stored value that is not a valid name is never echoed as a target`() {
        val untrusted = mapOf(RENAMED_CONTEXT to GKE_SETTING.copy(project = "x/../y\"}"))
        val status = metricsSourceStatus(RENAMED_CONTEXT, untrusted, noDetection)
        assertEquals("Incomplete: enter a valid project, location and cluster.", status)
        assertFalse(status.contains("x/../y"), status)
    }

    @Test
    fun `the demo context is an ordinary Auto row that follows the search`() {
        val demo = "demo-cluster (mock)"
        val operated = FOUND.copy(service = "prometheus-operated", port = "web")
        assertEquals(
            "Auto → looks for a Prometheus service when a pod's charts open.",
            metricsSourceStatus(demo, emptyMap(), noDetection),
        )
        assertEquals(
            "Auto → searching this cluster for a Prometheus service…",
            metricsSourceStatus(demo, emptyMap(), mapOf(demo to DetectionState.Searching)),
        )
        assertEquals(
            "Auto → in-cluster Prometheus · monitoring/prometheus-operated:web",
            metricsSourceStatus(demo, emptyMap(), mapOf(demo to DetectionState.Found(operated))),
        )
    }

    @Test
    fun `Off wins on the demo context too, and a minted demo instance reads its shared row`() {
        val demo = "demo-cluster (mock)"
        val settings = mapOf(demo to MetricsSourceSetting(mode = MetricsSourceMode.OFF))
        val off = "Off: pod charts show live metrics-server data only."
        assertEquals(off, metricsSourceStatus(demo, settings, noDetection))
        assertEquals(off, metricsSourceStatus("$demo #2", settings, noDetection))
        assertEquals(
            "Auto → in-cluster Prometheus · monitoring/prometheus-server:80",
            metricsSourceStatus("$demo #2", emptyMap(), mapOf(demo to DetectionState.Found(FOUND))),
        )
    }
}

/**
 * The Settings section driven through the real composable with a fake `onSet`: nothing
 * here reaches the view model, the stored preferences or gcloud. Wraps content in a bare
 * `MaterialTheme` over [kdTypography], never `KubeDashTheme` (its startup effects re-sync
 * [ThemeManager] from the store), and restores the theme afterwards.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class MetricsHistorySettingsUiTest {

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
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    /**
     * A stand-in for the view model and the detection: records every write and feeds it back like the
     * preference flow does, and forgets a context's search result when asked to search it again.
     */
    private class Harness(
        initial: Map<String, MetricsSourceSetting> = emptyMap(),
        initialDetection: Map<String, DetectionState> = emptyMap(),
    ) {
        var settings by mutableStateOf(initial)
        var detection by mutableStateOf(initialDetection)
        val calls = mutableListOf<Pair<String, MetricsSourceSetting>>()
        val searchAgains = mutableListOf<String>()

        fun onSet(context: String, setting: MetricsSourceSetting) {
            calls += context to setting
            settings = settings + (DemoContext.preferenceKey(context) to setting)
        }

        fun onSearchAgain(context: String) {
            searchAgains += context
            detection = detection - DemoContext.preferenceKey(context)
        }
    }

    private fun ComposeUiTest.render(harness: Harness, vararg contexts: String, paneWidth: Int = 560) {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(paneWidth.dp)) {
                    MetricsHistorySettings(contexts.toList(), harness.settings, harness::onSet, harness.detection, harness::onSearchAgain)
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `choosing Managed Prometheus on a gke context stores the mode with the triple prefilled`() = runComposeUiTest {
        val harness = Harness()
        render(harness, GKE_CONTEXT)

        onNodeWithText("Managed Prometheus").performClick()
        waitForIdle()

        assertEquals(listOf(GKE_CONTEXT to GKE_SETTING), harness.calls)
        // The fields appear, holding the prefilled values, and the status drops "Auto →".
        onNodeWithText("example-project").assertExists()
        onNodeWithText("us-central1").assertExists()
        onNodeWithText("example-cluster").assertExists()
        onNodeWithText("Google Managed Prometheus · example-project · us-central1 · example-cluster").assertExists()
    }

    @Test
    fun `choosing Off stores only the mode`() = runComposeUiTest {
        val harness = Harness()
        render(harness, GKE_CONTEXT)

        onNodeWithText("Off").performClick()
        waitForIdle()

        assertEquals(listOf(GKE_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.OFF)), harness.calls)
        onNodeWithText("Off: pod charts show live metrics-server data only.").assertExists()
    }

    @Test
    fun `choosing Managed Prometheus on a renamed context prefills nothing`() = runComposeUiTest {
        val harness = Harness()
        render(harness, RENAMED_CONTEXT)

        onNodeWithText("Managed Prometheus").performClick()
        waitForIdle()

        assertEquals(listOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.GMP)), harness.calls)
        onNodeWithText("Incomplete: enter a valid project, location and cluster.").assertExists()
    }

    @Test
    fun `prefill never overwrites a field the user already filled`() = runComposeUiTest {
        val kept = MetricsSourceSetting(mode = MetricsSourceMode.OFF, project = "example-project-7")
        val harness = Harness(mapOf(GKE_CONTEXT to kept))
        render(harness, GKE_CONTEXT)

        onNodeWithText("Managed Prometheus").performClick()
        waitForIdle()

        assertEquals(
            listOf(GKE_CONTEXT to GKE_SETTING.copy(project = "example-project-7")),
            harness.calls,
        )
    }

    @Test
    fun `choosing Auto keeps the typed fields for later`() = runComposeUiTest {
        val harness = Harness(mapOf(GKE_CONTEXT to GKE_SETTING))
        render(harness, GKE_CONTEXT)

        onNodeWithText("Auto").performClick()
        waitForIdle()

        assertEquals(listOf(GKE_CONTEXT to GKE_SETTING.copy(mode = MetricsSourceMode.AUTO)), harness.calls)
    }

    @Test
    fun `a field commits trimmed on blur, and only when its text really changed`() = runComposeUiTest {
        val harness = Harness(mapOf(GKE_CONTEXT to GKE_SETTING))
        render(harness, GKE_CONTEXT)

        // Walking through the fields without typing writes nothing.
        onNodeWithText("example-project").performClick()
        onNodeWithText("us-central1").performClick()
        onNodeWithText("example-cluster").performClick()
        onNodeWithText("example-project").performClick()
        waitForIdle()
        assertTrue(harness.calls.isEmpty(), "untouched fields wrote ${harness.calls}")

        onNodeWithText("example-project").performTextReplacement("  example-project-7 ")
        onNodeWithText("us-central1").performClick()
        waitForIdle()

        assertEquals(listOf(GKE_CONTEXT to GKE_SETTING.copy(project = "example-project-7")), harness.calls)
    }

    @Test
    fun `every context gets its own row with the shared intro, and none shows fields until Managed Prometheus`() = runComposeUiTest {
        val harness = Harness()
        render(harness, GKE_CONTEXT, RENAMED_CONTEXT)

        onNodeWithText(GKE_CONTEXT).assertExists()
        onNodeWithText(RENAMED_CONTEXT).assertExists()
        onNodeWithText("Auto → Google Managed Prometheus · example-project · us-central1 · example-cluster").assertExists()
        onNodeWithText("Auto → looks for a Prometheus service when a pod's charts open.").assertExists()
        onNodeWithText("project").assertDoesNotExist()
        onNodeWithText("Namespace").assertDoesNotExist()
    }

    @Test
    fun `no contexts shows the explanation instead of rows`() = runComposeUiTest {
        render(Harness())

        onNodeWithText("No cluster contexts found. Add a kubeconfig context to choose where its metrics history comes from.")
            .assertExists()
        onNodeWithText("Managed Prometheus").assertDoesNotExist()
    }

    // ── In-cluster ──────────────────────────────────────────────────────────────

    private val inClusterSetting = MetricsSourceSetting(
        mode = MetricsSourceMode.IN_CLUSTER,
        namespace = "monitoring",
        service = "prometheus-server",
        port = "80",
    )

    /** The In-cluster row's four text fields, in the order they are drawn: namespace, service, port, path prefix. */
    private fun ComposeUiTest.field(index: Int) = onAllNodes(hasSetTextAction())[index]

    @Test
    fun `choosing In-cluster with a search result prefills the blank fields from it`() = runComposeUiTest {
        val harness = Harness(initialDetection = detected(DetectionState.Found(FOUND.copy(pathPrefix = "/prom"))))
        render(harness, RENAMED_CONTEXT)

        onNodeWithText("In-cluster").performClick()
        waitForIdle()

        val expected = MetricsSourceSetting(
            mode = MetricsSourceMode.IN_CLUSTER,
            namespace = "monitoring",
            service = "prometheus-server",
            port = "80",
            pathPrefix = "/prom",
        )
        assertEquals(listOf(RENAMED_CONTEXT to expected), harness.calls)
        onNodeWithText("monitoring").assertExists()
        onNodeWithText("prometheus-server").assertExists()
        onNodeWithText("80").assertExists()
        onNodeWithText("/prom").assertExists()
        onNodeWithText("In-cluster Prometheus · monitoring/prometheus-server:80/prom").assertExists()
    }

    private fun assertInClusterPrefillsNothing(detection: Map<String, DetectionState>) = runComposeUiTest {
        val harness = Harness(initialDetection = detection)
        render(harness, RENAMED_CONTEXT)

        onNodeWithText("In-cluster").performClick()
        waitForIdle()

        assertEquals(listOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.IN_CLUSTER)), harness.calls, "$detection")
        onNodeWithText("Incomplete: enter a valid namespace, service and port.").assertExists()
    }

    @Test
    fun `choosing In-cluster before any search prefills nothing`() = assertInClusterPrefillsNothing(emptyMap())

    @Test
    fun `choosing In-cluster after a search that found nothing prefills nothing`() = assertInClusterPrefillsNothing(detected(DetectionState.NothingFound(1)))

    @Test
    fun `choosing In-cluster during a search prefills nothing`() = assertInClusterPrefillsNothing(detected(DetectionState.Searching))

    @Test
    fun `the In-cluster prefill never overwrites a field the user already filled, nor the HTTPS switch`() = runComposeUiTest {
        val kept = MetricsSourceSetting(mode = MetricsSourceMode.OFF, namespace = "team-a", https = true)
        val harness = Harness(mapOf(RENAMED_CONTEXT to kept), detected(DetectionState.Found(FOUND)))
        render(harness, RENAMED_CONTEXT)

        onNodeWithText("In-cluster").performClick()
        waitForIdle()

        assertEquals(
            listOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.IN_CLUSTER, namespace = "team-a", service = "prometheus-server", port = "80", https = true)),
            harness.calls,
        )
    }

    @Test
    fun `an In-cluster prefill leaves the Managed Prometheus fields alone`() = runComposeUiTest {
        val harness = Harness(mapOf(RENAMED_CONTEXT to GKE_SETTING.copy(mode = MetricsSourceMode.AUTO)), detected(DetectionState.Found(FOUND)))
        render(harness, RENAMED_CONTEXT)

        onNodeWithText("In-cluster").performClick()
        waitForIdle()

        val call = harness.calls.single().second
        assertEquals(GKE_SETTING.copy(mode = MetricsSourceMode.IN_CLUSTER, namespace = "monitoring", service = "prometheus-server", port = "80"), call)
    }

    @Test
    fun `the In-cluster fields show only in that mode, with a label each`() = runComposeUiTest {
        val harness = Harness(mapOf(RENAMED_CONTEXT to inClusterSetting.copy(mode = MetricsSourceMode.AUTO)))
        render(harness, RENAMED_CONTEXT)
        onNodeWithText("Namespace").assertDoesNotExist()

        onNodeWithText("In-cluster").performClick()
        waitForIdle()

        listOf("Namespace", "Service", "Port", "Path prefix (optional)").forEach { onNodeWithText(it).assertExists() }
        onAllNodes(hasSetTextAction()).assertCountEquals(4)
        onAllNodes(isToggleable()).assertCountEquals(1)
        onNodeWithText("project").assertDoesNotExist()
    }

    @Test
    fun `the In-cluster fields commit trimmed on blur, and only when their text really changed`() = runComposeUiTest {
        val harness = Harness(mapOf(RENAMED_CONTEXT to inClusterSetting))
        render(harness, RENAMED_CONTEXT)

        // Walking through the fields without typing writes nothing.
        (0..3).forEach { field(it).performClick() }
        field(0).performClick()
        waitForIdle()
        assertTrue(harness.calls.isEmpty(), "untouched fields wrote ${harness.calls}")

        field(2).performTextReplacement("  9090 ")
        field(3).performClick()
        waitForIdle()
        assertEquals(listOf(RENAMED_CONTEXT to inClusterSetting.copy(port = "9090")), harness.calls)

        field(3).performTextReplacement("/prometheus")
        field(0).performClick()
        waitForIdle()
        assertEquals(inClusterSetting.copy(port = "9090", pathPrefix = "/prometheus"), harness.calls.last().second)
        onNodeWithText("In-cluster Prometheus · monitoring/prometheus-server:9090/prometheus").assertExists()
    }

    @Test
    fun `the HTTPS switch commits as soon as it is toggled`() = runComposeUiTest {
        val harness = Harness(mapOf(RENAMED_CONTEXT to inClusterSetting))
        render(harness, RENAMED_CONTEXT)

        onNode(isToggleable()).performClick()
        waitForIdle()

        assertEquals(listOf(RENAMED_CONTEXT to inClusterSetting.copy(https = true)), harness.calls)
        onNodeWithText("In-cluster Prometheus · monitoring/prometheus-server:80 · HTTPS").assertExists()

        onNode(isToggleable()).performClick()
        waitForIdle()
        assertEquals(inClusterSetting, harness.calls.last().second)
    }

    @Test
    fun `a valid In-cluster setting shows no field error`() = runComposeUiTest {
        render(Harness(mapOf(RENAMED_CONTEXT to inClusterSetting.copy(port = "web", pathPrefix = "/prom"))), RENAMED_CONTEXT)

        listOf("Not a valid namespace name", "Not a valid service name", "Port: a number 1-65535 or a port name", "Path: like /prometheus")
            .forEach { onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun `a blank In-cluster field is not an error, only an incomplete setting, and the optional prefix is never either`() = runComposeUiTest {
        render(Harness(mapOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.IN_CLUSTER, namespace = "monitoring"))), RENAMED_CONTEXT)

        listOf("Not a valid namespace name", "Not a valid service name", "Port: a number 1-65535 or a port name", "Path: like /prometheus")
            .forEach { onNodeWithText(it).assertDoesNotExist() }
        onNodeWithText("Incomplete: enter a valid namespace, service and port.").assertExists()
    }

    @Test
    fun `each committed In-cluster field that fails validation says so without echoing what was typed`() = runComposeUiTest {
        val bad = MetricsSourceSetting(
            mode = MetricsSourceMode.IN_CLUSTER,
            namespace = "Bad_NS-42!",
            service = "Bad_SVC-42!",
            port = "70000",
            pathPrefix = "no-slash-42",
        )
        render(Harness(mapOf(RENAMED_CONTEXT to bad)), RENAMED_CONTEXT)

        onNodeWithText("Not a valid namespace name").assertExists()
        onNodeWithText("Not a valid service name").assertExists()
        onNodeWithText("Port: a number 1-65535 or a port name").assertExists()
        onNodeWithText("Path: like /prometheus").assertExists()
        onNodeWithText("Incomplete: enter a valid namespace, service and port.").assertExists()
        // The only places the typed text shows are the fields themselves: the port holds no "-42".
        onAllNodesWithText("-42", substring = true).assertCountEquals(3)
    }

    @Test
    fun `typing an invalid value shows its error once it is committed, and fixing it clears the error`() = runComposeUiTest {
        val harness = Harness(mapOf(RENAMED_CONTEXT to inClusterSetting))
        render(harness, RENAMED_CONTEXT)

        field(0).performTextReplacement("Not A Namespace")
        field(1).performClick()
        waitForIdle()
        assertEquals(inClusterSetting.copy(namespace = "Not A Namespace"), harness.calls.last().second)
        onNodeWithText("Not a valid namespace name").assertExists()

        field(0).performTextReplacement("monitoring")
        field(1).performClick()
        waitForIdle()
        onNodeWithText("Not a valid namespace name").assertDoesNotExist()
        onNodeWithText("In-cluster Prometheus · monitoring/prometheus-server:80").assertExists()
    }

    @Test
    fun `the In-cluster fields wrap inside the smallest dialog instead of overflowing it`() = runComposeUiTest {
        render(Harness(mapOf(RENAMED_CONTEXT to inClusterSetting)), RENAMED_CONTEXT, paneWidth = 485)

        val bounds = (0..3).map { field(it).getBoundsInRoot() }
        bounds.forEachIndexed { index, rect ->
            assertTrue(rect.left >= 0.dp && rect.right <= 485.dp, "field $index (${rect.left}..${rect.right}) leaves the 485 dp pane")
        }
        assertTrue(bounds[3].top > bounds[0].top, "four fields cannot share one 485 dp line, so the last one must wrap")
        val toggle = onNode(isToggleable()).getBoundsInRoot()
        assertTrue(toggle.right <= 485.dp, "the HTTPS switch leaves the pane")
    }

    // ── Search again ────────────────────────────────────────────────────────────

    @Test
    fun `an Auto row that is not on Managed Prometheus offers Search again, and pressing it forgets the result`() = runComposeUiTest {
        val harness = Harness(initialDetection = detected(DetectionState.NothingFound(1)))
        render(harness, RENAMED_CONTEXT)
        onNodeWithText("Auto → none: no Prometheus service found. Pick In-cluster to enter one.").assertExists()

        onNodeWithText("Search again").performClick()
        waitForIdle()

        assertEquals(listOf(RENAMED_CONTEXT), harness.searchAgains)
        onNodeWithText("Auto → looks for a Prometheus service when a pod's charts open.").assertExists()
        assertTrue(harness.calls.isEmpty(), "Search again must not change the setting: ${harness.calls}")
    }

    @Test
    fun `Search again shows for every outcome of the search`() = runComposeUiTest {
        val outcomes = listOf(
            emptyMap(),
            detected(DetectionState.Searching),
            detected(DetectionState.Found(FOUND)),
            detected(DetectionState.NothingFound(1)),
            detected(DetectionState.CannotSearch(1)),
            detected(DetectionState.SearchFailed(1)),
        )
        val harness = Harness()
        render(harness, RENAMED_CONTEXT)
        outcomes.forEach { outcome ->
            harness.detection = outcome
            waitForIdle()
            onNodeWithText("Search again").assertExists()
        }
    }

    @Test
    fun `Search again is not offered where Auto does not search`() = runComposeUiTest {
        val settings = mapOf(
            "off-context" to MetricsSourceSetting(mode = MetricsSourceMode.OFF),
            "gmp-context" to GKE_SETTING,
            "in-cluster-context" to inClusterSetting,
        )
        render(Harness(settings), GKE_CONTEXT, "off-context", "gmp-context", "in-cluster-context")

        onNodeWithText("Search again").assertDoesNotExist()
    }

    @Test
    fun `Search again is offered on the demo row, which Auto searches like any context`() = runComposeUiTest {
        val demo = DemoContext.MOCK_CONTEXT_NAME
        render(Harness(), demo)

        onNodeWithText("Search again").assertExists()
        onNodeWithText("Auto → looks for a Prometheus service when a pod's charts open.").assertExists()
    }

    @Test
    fun `Search again is offered on a gke context whose name does not give a valid project, but not on one that does`() = runComposeUiTest {
        // A one-letter project id is not a valid one, so Auto falls back to searching the cluster.
        val unusable = "gke_x_us-central1_example-cluster"
        assertEquals(
            "Auto → looks for a Prometheus service when a pod's charts open.",
            metricsSourceStatus(unusable, emptyMap(), noDetection),
        )
        render(Harness(), unusable)
        onNodeWithText("Search again").assertExists()
    }

    @Test
    fun `the help text mentions the service proxy and the edit role`() = runComposeUiTest {
        render(Harness(), RENAMED_CONTEXT)

        onNodeWithText("services/proxy", substring = true).assertExists()
        onNodeWithText("the edit role has it", substring = true).assertExists()
    }

    private fun settingSelecting(mode: MetricsSourceMode) = when (mode) {
        MetricsSourceMode.GMP -> GKE_SETTING
        MetricsSourceMode.IN_CLUSTER -> MetricsSourceSetting(mode = mode, namespace = "monitoring", service = "prometheus-server", port = "80")
        else -> MetricsSourceSetting(mode = mode)
    }

    // The segment is selected, so the check mark shares it with the label. M3 places the label after
    // the icon slot, so a label can have its full natural width and still run past the button: the
    // check is on bounds, not on the text's own width.
    private fun assertLabelInsideItsButton(label: String, mode: MetricsSourceMode, style: ThemeStyle, paneWidth: Int, maxLines: Int) = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(style)
        render(Harness(mapOf(GKE_CONTEXT to settingSelecting(mode))), GKE_CONTEXT, paneWidth = paneWidth)

        val node = onNodeWithText(label, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.first()
        val text = node.getBoundsInRoot()
        val button = onNode(hasText(label) and hasClickAction()).getBoundsInRoot()
        // didOverflowWidth can be set even when every glyph fits, so check the lines themselves.
        assertFalse(layout.didOverflowHeight, "'$label' needs a third line in the $style style at a $paneWidth dp pane")
        val widestLine = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        assertTrue(widestLine <= layout.size.width + 0.5f, "a line of '$label' is cut in the $style style at a $paneWidth dp pane")
        assertTrue(layout.lineCount <= maxLines, "'$label' takes ${layout.lineCount} lines in the $style style at a $paneWidth dp pane")

        assertTrue(
            text.left >= button.left && text.right <= button.right,
            "'$label' (${text.left}..${text.right}) runs past its button (${button.left}..${button.right}) in the $style style at a $paneWidth dp pane",
        )

        // A wrapped label makes its button taller; the others follow, so the row reads as one band.
        val heights = modeLabels.map { (_, other) ->
            onNode(hasText(other) and hasClickAction()).getBoundsInRoot().let { (it.bottom - it.top).value }
        }
        assertTrue(heights.all { abs(it - heights.first()) < 0.5f }, "the four buttons differ in height $heights in the $style style at a $paneWidth dp pane")
    }

    private val modeLabels = listOf(
        MetricsSourceMode.AUTO to "Auto",
        MetricsSourceMode.GMP to "Managed Prometheus",
        MetricsSourceMode.IN_CLUSTER to "In-cluster",
        MetricsSourceMode.OFF to "Off",
    )

    // Every label in its worst case, which is when its own segment is selected. 485 dp is the content
    // width of the smallest Settings dialog: 750 dp wide, less the 160 dp rail, its divider, the 32 dp
    // pane padding and the 20 dp section padding on both sides.
    private fun assertEveryLabelInsideItsButton(style: ThemeStyle, paneWidth: Int, maxLines: Int) = modeLabels.forEach { (mode, label) ->
        assertLabelInsideItsButton(label, mode, style, paneWidth, maxLines)
    }

    @Test
    fun `all four mode labels stay inside their buttons in the Default style at the smallest dialog`() = assertEveryLabelInsideItsButton(ThemeStyle.DEFAULT, paneWidth = 485, maxLines = 2)

    @Test
    fun `all four mode labels stay inside their buttons in the Retro style at the smallest dialog`() = assertEveryLabelInsideItsButton(ThemeStyle.RETRO, paneWidth = 485, maxLines = 2)

    @Test
    fun `all four mode labels stay inside their buttons in the Default style at full width`() = assertEveryLabelInsideItsButton(ThemeStyle.DEFAULT, paneWidth = 700, maxLines = 2)

    @Test
    fun `all four mode labels stay inside their buttons in the Retro style at full width`() = assertEveryLabelInsideItsButton(ThemeStyle.RETRO, paneWidth = 700, maxLines = 2)
}
