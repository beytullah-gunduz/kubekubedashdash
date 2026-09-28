package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.ui.crt.retroLatched
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PanelColor = Color(0xFF336699)
private val HostSize = Size(1400f, 600f)

/**
 * Exercises the WS8 detail-pane open/close (D21) and its latch (D22): [DetailHost] draws the
 * detail slot behind `crtPanelFrame` in Retro instead of fading it, and [retroLatched] keeps a
 * closing slot's content alive for the duration. At 1400 dp the host is in Split
 * (`detailWidthFor(1400f, null)` = 588, so the panel occupies x in [812, 1400)); (1100, 150) sits
 * inside the panel and off the vertical centre, so the resting scan line never covers it. Runs
 * only against the Gradle test-data store; the manager's prior mode, style and dark flag are
 * restored after each case (CrtGhostTest's pattern).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class DetailHostCrtTest {

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

    // The host shared by every pixel/click case: a full-size white list, and a solid, clickable
    // "panel" as the detail slot — exactly the shape D21 wires into WorkspaceScaffold and
    // GenericResourceScreen. `indication = null` (review MAJOR-2): performClick() leaves the
    // pointer hovering, and the default debug indication would shade the panel under test.
    @Composable
    private fun PanelHost(visible: Boolean, onPanelClick: () -> Unit) {
        DetailHost(
            visible = visible,
            kindKey = null,
            onWidthChange = {},
            expanded = false,
            onExpandedChange = {},
            onClose = {},
            modifier = Modifier.fillMaxSize(),
            list = { Box(Modifier.fillMaxSize().background(Color.White)) },
            detail = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(PanelColor)
                        .testTag("panel")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPanelClick,
                        ),
                )
            },
        )
    }

    @Test
    fun `Retro open is drawn closed on frame one, with input already live`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(false)
            var panelClicks = 0
            setContent { PanelHost(visible = visible, onPanelClick = { panelClicks++ }) }
            waitForIdle()

            visible = true
            mainClock.advanceTimeByFrame()
            waitForIdle()

            assertNotEquals(
                PanelColor,
                captureToImage().toPixelMap()[1100, 150],
                "the panel must be drawn closed on the opening frame",
            )

            onNodeWithTag("panel").performClick()
            waitForIdle()
            assertEquals(1, panelClicks, "input must be live from frame one (D6)")

            mainClock.advanceTimeBy(200)
            waitForIdle()
            assertEquals(
                PanelColor,
                captureToImage().toPixelMap()[1100, 150],
                "the panel must be fully open once the 140 ms aperture settles",
            )
        }
    }

    @Test
    fun `Retro close holds the slot for the collapse, swallowing clicks, then disposes it`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(true)
            var panelClicks = 0
            setContent { PanelHost(visible = visible, onPanelClick = { panelClicks++ }) }
            mainClock.advanceTimeBy(200)
            waitForIdle()

            visible = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithTag("panel").assertExists()

            onNodeWithTag("panel").performClick()
            waitForIdle()
            assertEquals(0, panelClicks, "the closing panel must swallow the click")

            mainClock.advanceTimeBy(150)
            waitForIdle()
            onNodeWithTag("panel").assertDoesNotExist()
        }
    }

    @Test
    fun `Default keeps its 150 ms fade`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(true)
            var panelClicks = 0
            setContent { PanelHost(visible = visible, onPanelClick = { panelClicks++ }) }
            mainClock.advanceTimeBy(200)
            waitForIdle()

            visible = false
            mainClock.advanceTimeBy(100)
            waitForIdle()
            onNodeWithTag("panel").assertExists()
            onNodeWithTag("panel").performClick()
            waitForIdle()
            assertEquals(1, panelClicks, "Default still lets a click through during its fade (pinned today's behaviour)")

            mainClock.advanceTimeBy(200)
            waitForIdle()
            onNodeWithTag("panel").assertDoesNotExist()
        }
    }

    @Test
    fun `retroLatched keeps the last value only in Retro`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            mainClock.autoAdvance = false
            var value by mutableStateOf<String?>("a")
            var latched: String? = null
            setContent { latched = retroLatched(value) }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            value = null
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals("a", latched, "Retro keeps the last non-null value while it is null")

            // A later pane replaces the latch: the next close must collapse the new content.
            value = "b"
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals("b", latched)
            value = null
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals("b", latched, "the latch must follow the most recent pane, not the first")
        }
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            mainClock.autoAdvance = false
            var value by mutableStateOf<String?>("a")
            var latched: String? = null
            setContent { latched = retroLatched(value) }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            value = null
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertNull(latched, "Default never latches (D3)")
        }
    }
}
