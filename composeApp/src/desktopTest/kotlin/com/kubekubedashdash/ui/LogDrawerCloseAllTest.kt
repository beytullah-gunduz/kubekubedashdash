package com.kubekubedashdash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.services.ActiveAppLog
import com.kubekubedashdash.services.ActiveCaptureTask
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.services.ActivePortForwards
import com.kubekubedashdash.services.LogStreamId
import com.kubekubedashdash.services.LogStreamOptions
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.services.logcapture.CapturePhase
import com.kubekubedashdash.services.logcapture.CaptureState
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureTask
import com.kubekubedashdash.ui.components.LogPaneStateStore
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The drawer's "Hide" and "Close all" controls and the title-bar chip that
 * keeps hidden tabs from being forgotten. The pure helpers first, then the
 * header composed with [LogDrawerState.COLLAPSED] (the header only, never a pane
 * body). Composing initialises ThemeManager, which opens the preferences store —
 * the Gradle test-data one, guarded below.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class LogDrawerCloseAllTest {

    @BeforeTest
    fun refuseRealDataDir() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    // Also cancels the hand-built capture Job: openOrFocusCaptureTab registers it in `jobs`.
    @AfterTest
    fun clearRegistry() {
        LogStreamRegistry.clearAll()
    }

    private fun runningState(ns: String) = CaptureState(namespace = ns, outputDir = "unused", phase = CapturePhase.Running, startedAtMs = 0L)

    private fun runningTask(ns: String) = NamespaceLogCaptureTask(ns, MutableStateFlow(runningState(ns)), Job())

    private fun stream(pod: String) = ActiveLogStream(
        id = LogStreamId(sessionId = "session-1", podName = pod, namespace = "ns-a", container = null),
        displayLabel = pod,
        lines = MutableStateFlow(emptyList()),
        droppedLines = MutableStateFlow(0),
        options = LogStreamOptions(),
        containers = MutableStateFlow(emptyList()),
        openedAt = 0L,
    )

    private fun openStream(session: String, pod: String): String = LogStreamRegistry
        .openOrFocusStream(LogStreamId(session, pod, "ns-a", null), pod) { _, _ -> emptyFlow() }
        .key

    // COLLAPSED composes the header only, never a pane body.
    private fun ComposeUiTest.hostDrawer(
        onStateChange: (LogDrawerState) -> Unit = {},
        onCloseAll: (List<String>) -> Unit = {},
    ) {
        setContent {
            MaterialTheme {
                LogDrawer(
                    state = LogDrawerState.COLLAPSED,
                    onStateChange = onStateChange,
                    paneStates = LogPaneStateStore(),
                    visibleSessionIds = setOf("session-1"),
                    onCloseAll = onCloseAll,
                )
            }
        }
    }

    @Test
    fun `closeAllTargets keeps port forwards only while none run`() {
        val tabs = listOf(stream("web-0"), ActiveAppLog(openedAt = 0L), ActivePortForwards(openedAt = 0L))

        assertEquals(3, closeAllTargets(tabs, runningForwards = 0).size)
        assertEquals(
            tabs.filter { it !is ActivePortForwards },
            closeAllTargets(tabs, runningForwards = 2),
        )
    }

    @Test
    fun `runningCaptureNamespaces names only running captures`() {
        val running = ActiveCaptureTask("session-1", runningTask("ns-a"), 0L)
        val finished = ActiveCaptureTask(
            "session-1",
            NamespaceLogCaptureTask("ns-b", MutableStateFlow(runningState("ns-b")), Job().apply { complete() }),
            0L,
        )

        assertEquals(listOf("ns-a"), runningCaptureNamespaces(listOf(running, finished, stream("web-0"))))
    }

    @Test
    fun `closeAllConfirmBody names one capture or several`() {
        assertEquals(
            "A log capture of namespace \"ns-a\" is still running. Closing its tab cancels it; the files it already wrote stay on disk.",
            closeAllConfirmBody(listOf("ns-a")),
        )
        assertEquals(
            "2 log captures are still running (ns-a, ns-b). Closing their tabs cancels them; the files they already wrote stay on disk.",
            closeAllConfirmBody(listOf("ns-a", "ns-b")),
        )
    }

    @Test
    fun `logTabCount pluralises`() {
        assertEquals("1 log tab", logTabCount(1))
        assertEquals("3 log tabs", logTabCount(3))
    }

    @Test
    fun `the drawer's hide button says hide and hides`() = runComposeUiTest {
        var recorded: LogDrawerState? = null
        hostDrawer(onStateChange = { recorded = it })

        onNodeWithContentDescription("Hide log drawer").assertExists()
        onNodeWithContentDescription("Close log drawer").assertDoesNotExist()
        onNodeWithContentDescription("Hide log drawer").performClick()

        assertEquals(LogDrawerState.HIDDEN, recorded)
    }

    @Test
    fun `close all counts this window's tabs and reports their keys`() = runComposeUiTest {
        val keyA = openStream("session-1", "web-0")
        val keyB = openStream("session-1", "web-1")
        openStream("session-2", "web-2")
        var closedKeys: List<String>? = null
        hostDrawer(onCloseAll = { closedKeys = it })

        onNodeWithText("Close all (2)").performClick()

        assertEquals(setOf(keyA, keyB), closedKeys?.toSet())
    }

    @Test
    fun `close all waits for a confirmation while a capture runs`() = runComposeUiTest {
        openStream("session-1", "web-0")
        LogStreamRegistry.openOrFocusCaptureTab("session-1", "ns-a") { runningTask("ns-a") }
        var closedKeys: List<String>? = null
        hostDrawer(onCloseAll = { closedKeys = it })

        onNodeWithText("Close all (2)").performClick()

        assertNull(closedKeys)
    }

    @Test
    fun `the confirmation keeps the tabs or closes them all`() = runComposeUiTest {
        val streamKey = openStream("session-1", "web-0")
        val captureKey = LogStreamRegistry.openOrFocusCaptureTab("session-1", "ns-a") { runningTask("ns-a") }
        var closedKeys: List<String>? = null
        hostDrawer(onCloseAll = { closedKeys = it })

        onNodeWithText("Close all (2)").performClick()
        onNodeWithText("Close all log tabs?").assertExists()

        onNodeWithText("Keep open").performClick()
        onNodeWithText("Close all log tabs?").assertDoesNotExist()
        assertNull(closedKeys)

        onNodeWithText("Close all (2)").performClick()
        onNodeWithText("Close all").performClick()
        assertEquals(setOf(streamKey, captureKey), closedKeys?.toSet())
    }

    @Test
    fun `the hidden-tabs chip shows the count and opens the drawer`() = runComposeUiTest {
        var clicks by mutableIntStateOf(0)
        setContent { MaterialTheme { HiddenLogTabsChip(count = 3, onClick = { clicks++ }) } }

        onNodeWithText("3 log tabs").assertExists()
        onNodeWithText("3 log tabs").performClick()

        assertEquals(1, clicks)
    }
}
