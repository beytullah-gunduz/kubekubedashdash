package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.services.ActiveNamespaceTail
import com.kubekubedashdash.services.logtail.NamespaceTailTask
import com.kubekubedashdash.services.logtail.TailLine
import com.kubekubedashdash.services.logtail.TailState
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The namespace-tail pane keeps its view state in [LogPaneViewState] too. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DrawerNamespaceTailPaneStateTest {

    @BeforeTest
    fun refuseRealDataDir() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun tail(lines: List<TailLine>) = ActiveNamespaceTail(
        sessionId = "session-1",
        task = NamespaceTailTask(
            namespace = "default",
            state = MutableStateFlow(TailState(namespace = "default", lines = lines, attachedPods = listOf("web-0", "web-1"))),
            job = Job(),
        ),
        openedAt = 0L,
    )

    // shown() toggles the pane out of and back into composition — what a
    // log-tab switch, a collapse, or the drawer moving to another tab does.
    private fun ComposeUiTest.host(tab: ActiveNamespaceTail, viewState: LogPaneViewState, shown: () -> Boolean) {
        setContent {
            MaterialTheme {
                if (shown()) {
                    Box(Modifier.size(width = 900.dp, height = 300.dp)) {
                        DrawerNamespaceTailPane(tab = tab, viewState = viewState)
                    }
                }
            }
        }
    }

    @Test
    fun `muted pods and a scrolled-away position survive the pane leaving composition`() = runComposeUiTest {
        val viewState = LogPaneViewState()
        viewState.mutedPods = setOf("web-1")
        var shown by mutableStateOf(true)
        val lines = (1..200).map { TailLine(podName = if (it % 2 == 0) "web-1" else "web-0", text = "line $it") }
        host(tail(lines), viewState) { shown }
        waitForIdle()
        // Top of the list: a muted pod's lines are hidden, the other pod's shown.
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        waitForIdle()
        onNodeWithText("line 1").assertIsDisplayed()
        onNodeWithText("line 2").assertDoesNotExist()
        onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        waitForIdle()
        assertFalse(viewState.follow)
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertEquals(setOf("web-1"), viewState.mutedPods)
        assertFalse(viewState.follow, "re-entering composition must not switch Follow back on")
        assertEquals(20, viewState.scrollIndex)
        onNodeWithText("line 41").assertIsDisplayed()
    }
}
