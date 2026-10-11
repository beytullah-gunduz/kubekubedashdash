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
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GKE_CONTEXT = "gke_example-project_us-central1_example-cluster"
private const val RENAMED_CONTEXT = "example-context"

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
        val status = metricsSourceStatus(GKE_CONTEXT, emptyMap())
        assertTrue(status.startsWith("Auto → Google Managed Prometheus · example-project"), status)
        assertEquals("Auto → Google Managed Prometheus · example-project · us-central1 · example-cluster", status)
    }

    @Test
    fun `a renamed context says Auto found nothing and how to fix it`() {
        assertEquals(
            "Auto → none: not a gke_… context. Pick Managed Prometheus to set it up.",
            metricsSourceStatus(RENAMED_CONTEXT, emptyMap()),
        )
    }

    @Test
    fun `a valid Managed Prometheus setting drops the Auto prefix`() {
        val status = metricsSourceStatus(RENAMED_CONTEXT, mapOf(RENAMED_CONTEXT to GKE_SETTING))
        assertFalse(status.startsWith("Auto → "), status)
        assertEquals("Google Managed Prometheus · example-project · us-central1 · example-cluster", status)
    }

    @Test
    fun `Off says pod charts fall back to live data`() {
        val settings = mapOf(GKE_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.OFF))
        assertEquals("Off: pod charts show live metrics-server data only.", metricsSourceStatus(GKE_CONTEXT, settings))
    }

    @Test
    fun `an incomplete Managed Prometheus setting says what is missing`() {
        val blank = mapOf(RENAMED_CONTEXT to MetricsSourceSetting(mode = MetricsSourceMode.GMP))
        assertEquals("Incomplete: enter a valid project, location and cluster.", metricsSourceStatus(RENAMED_CONTEXT, blank))
    }

    @Test
    fun `a stored value that is not a valid name is never echoed as a target`() {
        val untrusted = mapOf(RENAMED_CONTEXT to GKE_SETTING.copy(project = "x/../y\"}"))
        val status = metricsSourceStatus(RENAMED_CONTEXT, untrusted)
        assertEquals("Incomplete: enter a valid project, location and cluster.", status)
        assertFalse(status.contains("x/../y"), status)
    }

    @Test
    fun `the demo context is Demo data`() {
        assertEquals("Demo data", metricsSourceStatus("demo-cluster (mock)", emptyMap()))
    }

    @Test
    fun `Off wins on the demo context too, and a minted demo instance reads its shared row`() {
        val settings = mapOf("demo-cluster (mock)" to MetricsSourceSetting(mode = MetricsSourceMode.OFF))
        val off = "Off: pod charts show live metrics-server data only."
        assertEquals(off, metricsSourceStatus("demo-cluster (mock)", settings))
        assertEquals(off, metricsSourceStatus("demo-cluster (mock) #2", settings))
        assertEquals("Demo data", metricsSourceStatus("demo-cluster (mock) #2", emptyMap()))
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

    /** A stand-in for the view model: records every write and feeds it back like the preference flow does. */
    private class Harness(initial: Map<String, MetricsSourceSetting> = emptyMap()) {
        var settings by mutableStateOf(initial)
        val calls = mutableListOf<Pair<String, MetricsSourceSetting>>()

        fun onSet(context: String, setting: MetricsSourceSetting) {
            calls += context to setting
            settings = settings + (DemoContext.preferenceKey(context) to setting)
        }
    }

    private fun ComposeUiTest.render(harness: Harness, vararg contexts: String, paneWidth: Int = 560) {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Column(Modifier.width(paneWidth.dp)) {
                    MetricsHistorySettings(contexts.toList(), harness.settings, harness::onSet)
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
        onNodeWithText("Auto → none: not a gke_… context. Pick Managed Prometheus to set it up.").assertExists()
        onNodeWithText("project").assertDoesNotExist()
    }

    @Test
    fun `no contexts shows the explanation instead of rows`() = runComposeUiTest {
        render(Harness())

        onNodeWithText("No cluster contexts found. Add a kubeconfig context to choose where its metrics history comes from.")
            .assertExists()
        onNodeWithText("Managed Prometheus").assertDoesNotExist()
    }

    // Managed Prometheus is selected, so the check mark shares the segment with the label. M3 places
    // the label after the icon slot, so a label can have its full natural width and still run past
    // the button: the check is on bounds, not on the text's own width.
    private fun assertWidestLabelInsideItsButton(style: ThemeStyle, paneWidth: Int, oneLine: Boolean) = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(style)
        render(Harness(mapOf(GKE_CONTEXT to GKE_SETTING)), GKE_CONTEXT, paneWidth = paneWidth)

        val label = onNodeWithText("Managed Prometheus", useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        label.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.first()
        // didOverflowWidth can be set even when every glyph fits, so check the lines themselves.
        assertFalse(layout.didOverflowHeight, "the label needs a third line in the $style style at a $paneWidth dp pane")
        val widestLine = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
        assertTrue(widestLine <= layout.size.width + 0.5f, "a line of the label is cut in the $style style at a $paneWidth dp pane")
        if (oneLine) assertEquals(1, layout.lineCount, "the label wraps in the $style style at a $paneWidth dp pane")

        val text = label.getBoundsInRoot()
        val button = onNode(hasText("Managed Prometheus") and hasClickAction()).getBoundsInRoot()
        assertTrue(
            text.left >= button.left && text.right <= button.right,
            "the label (${text.left}..${text.right}) runs past its button (${button.left}..${button.right}) in the $style style at a $paneWidth dp pane",
        )
    }

    // 485 dp is the content width of the smallest Settings dialog: 750 dp wide, less the 160 dp
    // rail, its divider, the 32 dp pane padding and the 20 dp section padding on both sides.
    @Test
    fun `the widest mode label stays inside its button in the Default style at the smallest dialog`() = assertWidestLabelInsideItsButton(ThemeStyle.DEFAULT, paneWidth = 485, oneLine = false)

    @Test
    fun `the widest mode label stays inside its button in the Retro style at the smallest dialog`() = assertWidestLabelInsideItsButton(ThemeStyle.RETRO, paneWidth = 485, oneLine = false)

    // Wide enough for the row's cap: one line in both styles.
    @Test
    fun `the widest mode label is one line in the Default style at full width`() = assertWidestLabelInsideItsButton(ThemeStyle.DEFAULT, paneWidth = 700, oneLine = true)

    @Test
    fun `the widest mode label is one line in the Retro style at full width`() = assertWidestLabelInsideItsButton(ThemeStyle.RETRO, paneWidth = 700, oneLine = true)
}
