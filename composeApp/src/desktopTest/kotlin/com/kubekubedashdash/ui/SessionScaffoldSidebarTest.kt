package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sidebar stays in the window at any width and any UI zoom. The scaffold
 * used to take its pane count from the window size class, and the navigator's
 * scaffold state only moved to a new pane layout when it navigated — so a
 * window that opened below 840 dp had no sidebar, and still had none after it
 * was widened.
 *
 * The window is a fake [LocalWindowInfo] whose size the test changes, which is
 * what `currentWindowAdaptiveInfoV2` reads; the scaffold's own box is sized to
 * match so its layout sees the same width.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class SessionScaffoldSidebarTest {

    private class ResizableWindow(width: Int, height: Int) : WindowInfo {
        override val isWindowFocused = true
        override var containerSize by mutableStateOf(IntSize(width, height))
    }

    @Composable
    private fun Harness(window: ResizableWindow, density: Float = 1f, sidebarCollapsed: Boolean = false) {
        CompositionLocalProvider(LocalDensity provides Density(density), LocalWindowInfo provides window) {
            MaterialTheme {
                val size = window.containerSize
                val (width, height) = with(LocalDensity.current) { size.width.toDp() to size.height.toDp() }
                Box(Modifier.size(width, height)) {
                    SessionScaffold(
                        currentScreen = Screen.Main.Connecting,
                        sidebarCollapsed = sidebarCollapsed,
                        sidebar = { Box(Modifier.fillMaxSize().testTag(SIDEBAR)) { Text("sidebar") } },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text("content")
                    }
                }
            }
        }
    }

    @Test
    fun `widening a window that opened narrow shows the sidebar, and shrinking it keeps it`() = runDesktopComposeUiTest(width = 1600, height = 700) {
        val window = ResizableWindow(800, 600)
        setContent { Harness(window) }
        waitForIdle()

        window.containerSize = IntSize(1440, 600)
        waitForIdle()
        onNodeWithTag(SIDEBAR).assertIsDisplayed()

        window.containerSize = IntSize(500, 600)
        waitForIdle()
        onNodeWithTag(SIDEBAR).assertIsDisplayed()
    }

    @Test
    fun `the sidebar is there at every width the window opens at`() {
        for (width in listOf(400, 700, 900, 1300, 1600)) {
            runDesktopComposeUiTest(width = 1600, height = 700) {
                setContent { Harness(ResizableWindow(width, 600)) }
                waitForIdle()
                onNodeWithTag(SIDEBAR).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `zoom does not hide the sidebar`() = runDesktopComposeUiTest(width = 1600, height = 700) {
        // A 1440 dp window at 200 % reads as 720 dp to the size class.
        setContent { Harness(ResizableWindow(1440, 600), density = 2f) }
        waitForIdle()
        onNodeWithTag(SIDEBAR).assertIsDisplayed()
    }

    @Test
    fun `a wide window keeps the sidebar widths it always had`() {
        // Expanded and collapsed anchors (280 / 56 dp) less half the 24 dp
        // partition spacer the size class gave from 840 dp up.
        for ((collapsed, expected) in listOf(false to 268.dp, true to 44.dp)) {
            runDesktopComposeUiTest(width = 1600, height = 700) {
                setContent { Harness(ResizableWindow(1440, 600), sidebarCollapsed = collapsed) }
                waitForIdle()
                val bounds = onNodeWithTag(SIDEBAR).getBoundsInRoot()
                assertEquals(expected, bounds.right, "sidebar right edge, collapsed=$collapsed")
            }
        }
    }

    private companion object {
        const val SIDEBAR = "sidebar"
    }
}
