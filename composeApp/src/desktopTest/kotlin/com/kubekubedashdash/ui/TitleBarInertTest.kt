package com.kubekubedashdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.arrow_back_filled
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The title bar's controls go inert while an in-app modal is open, and the modal starts under
 * the bar. Pointer input is injected (not a semantic click), because the shield only matters
 * for real pointer events.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class TitleBarInertTest {

    @BeforeTest
    fun guard() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @Composable
    private fun Host(inert: Boolean, onControlClick: () -> Unit, onBarPress: () -> Unit) {
        Row(
            Modifier.size(300.dp, 40.dp).pointerInput(Unit) {
                // Stands in for TitleBar's drag handler: it acts only on an unconsumed press.
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Main)
                        if (e.type == PointerEventType.Press && e.changes.none { it.isConsumed }) onBarPress()
                    }
                }
            },
        ) {
            TitleBarControls(inert = inert) {
                Box(Modifier.size(28.dp).testTag("control").consumeTitleBarPress().clickable { onControlClick() })
            }
        }
    }

    @Test
    fun `an enabled control takes the click and the bar sees no press`() {
        runSkikoComposeUiTest(size = Size(400f, 120f), density = Density(1f)) {
            var controlClicks = 0
            var barPresses = 0
            setContent { Host(inert = false, onControlClick = { controlClicks++ }, onBarPress = { barPresses++ }) }
            onNodeWithTag("control").performMouseInput { click(center) }
            waitForIdle()

            assertEquals(1, controlClicks, "an enabled control must take the click")
            assertEquals(0, barPresses, "the control eats the press, so the bar must not start a drag")
        }
    }

    @Test
    fun `an inert control takes no click and the press reaches the bar`() {
        runSkikoComposeUiTest(size = Size(400f, 120f), density = Density(1f)) {
            var controlClicks = 0
            var barPresses = 0
            setContent { Host(inert = true, onControlClick = { controlClicks++ }, onBarPress = { barPresses++ }) }
            onNodeWithTag("control").performMouseInput { click(center) }
            waitForIdle()

            assertEquals(0, controlClicks, "an inert control must take no click")
            assertEquals(1, barPresses, "the shield leaves the press unconsumed, so the bar can drag the window")
        }
    }

    @Test
    fun `BelowTitleBar starts its content under the title bar`() {
        runSkikoComposeUiTest(size = Size(400f, 300f), density = Density(1f)) {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    BelowTitleBar { Box(Modifier.fillMaxSize().testTag("modal")) }
                }
            }
            waitForIdle()

            val bounds = onNodeWithTag("modal").getUnclippedBoundsInRoot()
            assertEquals(titleBarHeight(), bounds.top, "the modal must start where the title bar ends")
            assertEquals(300.dp, bounds.bottom, "the modal must reach the bottom of the window")
        }
    }

    // An arrow takes a click only when it has somewhere to go AND the title bar is not inert.
    // A first version let the inert flag stand in for the arrow's own state, so an arrow with no
    // history was clickable whenever no modal was open.
    @Test
    fun `a history arrow is clickable only with somewhere to go and an active bar`() {
        for ((enabled, clickable) in listOf(true to true, true to false, false to true, false to false)) {
            runSkikoComposeUiTest(size = Size(100f, 100f), density = Density(1f)) {
                var clicks = 0
                setContent {
                    HistoryButton(Res.drawable.arrow_back_filled, "Back", enabled, { clicks++ }, clickable = clickable)
                }
                val arrow = onNodeWithContentDescription("Back")
                arrow.performMouseInput { click(center) }
                waitForIdle()

                val expected = enabled && clickable
                if (expected) arrow.assertIsEnabled() else arrow.assertIsNotEnabled()
                assertEquals(if (expected) 1 else 0, clicks, "enabled=$enabled clickable=$clickable")
            }
        }
    }
}
