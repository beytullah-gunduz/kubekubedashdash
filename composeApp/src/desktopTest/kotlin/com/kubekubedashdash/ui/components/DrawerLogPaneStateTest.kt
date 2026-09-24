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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.services.LogStreamId
import com.kubekubedashdash.services.LogStreamOptions
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drawer's log pane keeps its filter, Follow state and scroll position in a
 * [LogPaneViewState] owned outside it, so they survive the pane leaving
 * composition. Composing the pane initialises ThemeManager (its Kd* colours),
 * which opens the preferences store — the Gradle test-data one, guarded below.
 *
 * Deliberately the v1 runComposeUiTest (deprecated): its dispatcher starts
 * LaunchedEffects before the first layout, the ordering the panes' "not laid
 * out yet" guard defends against. Under v2, which queues effects, the second
 * test passes even without the guard.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DrawerLogPaneStateTest {

    @BeforeTest
    fun refuseRealDataDir() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun stream(lines: MutableStateFlow<List<String>>) = ActiveLogStream(
        id = LogStreamId(sessionId = "session-1", podName = "web-0", namespace = "default", container = null),
        displayLabel = "web-0",
        lines = lines,
        droppedLines = MutableStateFlow(0),
        options = LogStreamOptions(),
        containers = MutableStateFlow(emptyList()),
        openedAt = 0L,
    )

    // shown() toggles the pane out of and back into composition — what a
    // log-tab switch, a collapse, or the drawer moving to another tab does.
    private fun ComposeUiTest.host(stream: ActiveLogStream, viewState: LogPaneViewState, shown: () -> Boolean) {
        setContent {
            MaterialTheme {
                if (shown()) {
                    Box(Modifier.size(width = 900.dp, height = 300.dp)) {
                        DrawerLogPane(stream = stream, viewState = viewState)
                    }
                }
            }
        }
    }

    @Test
    fun `filter text typed into the pane survives the pane leaving composition`() = runComposeUiTest {
        val viewState = LogPaneViewState()
        var shown by mutableStateOf(true)
        host(stream(MutableStateFlow((1..50).map { "line $it" })), viewState) { shown }
        onNode(hasSetTextAction()).performTextInput("line 4")
        waitForIdle()
        assertEquals("line 4", viewState.filterText)
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        onNode(hasSetTextAction()).assert(hasText("line 4"))
    }

    @Test
    fun `a pane scrolled away from the bottom comes back where it was`() = runComposeUiTest {
        val viewState = LogPaneViewState()
        var shown by mutableStateOf(true)
        host(stream(MutableStateFlow((1..200).map { "line $it" })), viewState) { shown }
        waitForIdle()
        assertTrue(viewState.follow, "a fresh pane follows")
        onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        waitForIdle()
        assertFalse(viewState.follow, "scrolling up switches Follow off")
        assertEquals(20, viewState.scrollIndex)
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertFalse(viewState.follow, "re-entering composition must not switch Follow back on")
        assertEquals(20, viewState.scrollIndex)
        onNodeWithText("line 21").assertIsDisplayed()
        onNodeWithText("line 200").assertDoesNotExist()
    }

    @Test
    fun `a following pane comes back on the newest line and keeps following`() = runComposeUiTest {
        val viewState = LogPaneViewState()
        val lines = MutableStateFlow((1..200).map { "line $it" })
        var shown by mutableStateOf(true)
        host(stream(lines), viewState) { shown }
        waitForIdle()
        shown = false
        waitForIdle()
        lines.value = lines.value + "line 201"
        shown = true
        waitForIdle()
        onNodeWithText("line 201").assertIsDisplayed()
        assertTrue(viewState.follow)
        lines.value = lines.value + "line 202"
        waitForIdle()
        onNodeWithText("line 202").assertIsDisplayed()
    }

    @Test
    fun `Follow switched off on the last line stays off after re-entering composition`() = runComposeUiTest {
        val viewState = LogPaneViewState()
        var shown by mutableStateOf(true)
        host(stream(MutableStateFlow((1..200).map { "line $it" })), viewState) { shown }
        waitForIdle()
        onNodeWithText("Follow").performClick()
        waitForIdle()
        assertFalse(viewState.follow, "the chip switches Follow off")
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertFalse(viewState.follow, "re-entering on the last line must not switch Follow back on")
        onNodeWithText("line 200").assertIsDisplayed()
    }

    @Test
    fun `crossing the one-row width keeps the filter field focused and each control once`() = runComposeUiTest {
        val viewState = LogPaneViewState()
        var width by mutableStateOf(900.dp)
        val stream = stream(MutableStateFlow((1..50).map { "line $it" }))
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = width, height = 300.dp)) {
                    DrawerLogPane(stream = stream, viewState = viewState)
                }
            }
        }
        onNode(hasSetTextAction()).performClick()
        onNode(hasSetTextAction()).performTextInput("abc")
        waitForIdle()
        for (w in listOf(500.dp, 900.dp)) {
            width = w
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
            onNode(hasSetTextAction()).assert(hasText("abc"))
            onAllNodesWithText("Follow").assertCountEquals(1)
            onAllNodesWithText("Wrap").assertCountEquals(1)
        }
    }
}
