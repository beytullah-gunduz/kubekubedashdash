package com.kubekubedashdash.ui.crt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val CardColor = Color(0xFF336699)
private val HostSize = Size(300f, 200f)

/**
 * Exercises the WS6 card-snapshot collapse (D16): [crtCardReveal] records an in-tree modal card
 * into a [CrtGhost] while it is shown, and [CrtGhostExit] plays that snapshot's power-off
 * collapse for 140 ms after the card leaves composition — Retro only, and only once a snapshot
 * was recorded. Runs only against the Gradle test-data store; the manager's prior mode, style
 * and dark flag are restored after each case (RetroPaletteTest's pattern).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrtGhostTest {

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

    // Host layout shared by every case: a full-size clickable base, a centred 120x80 card while
    // [visible], and the sibling exit ghost at the card's z-position — exactly the shape D16
    // wires into App.kt's six modal sites. [shadow] swaps in content drawn 20 px outside the
    // card's own bounds, for case 4 (review MAJOR-3).
    @Composable
    private fun GhostHost(visible: Boolean, ghost: CrtGhost, onBaseClick: () -> Unit, shadow: Boolean) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().clickable { onBaseClick() }.background(Color.White))
            if (visible) {
                val card = Modifier.align(Alignment.Center).size(120.dp, 80.dp).crtCardReveal(ghost)
                Box(
                    if (shadow) {
                        card
                            .drawBehind {
                                drawRect(
                                    Color.Red,
                                    topLeft = Offset(-20f, -20f),
                                    size = Size(size.width + 40f, size.height + 40f),
                                )
                            }
                            .background(CardColor)
                    } else {
                        card.background(CardColor)
                    },
                )
            }
            CrtGhostExit(visible, ghost, 0.45f)
        }
    }

    // No card, no ghost, no click: the plain base at rest. Captured in its own run so a click
    // injected elsewhere (which leaves the base hovered) can never taint this reference.
    private fun captureBaseAlone(): Color {
        var pixel = Color.Black
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            setContent { Box(Modifier.fillMaxSize().background(Color.White)) }
            waitForIdle()
            pixel = captureToImage().toPixelMap()[150, 100]
        }
        return pixel
    }

    @Test
    fun `case 1 - input is released at once, while the ghost is provably on screen`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(true)
            var baseClicks = 0
            lateinit var ghost: CrtGhost
            setContent {
                ghost = rememberCrtGhost()
                GhostHost(visible = visible, ghost = ghost, onBaseClick = { baseClicks++ }, shadow = false)
            }
            mainClock.advanceTimeBy(200) // past the 180 ms reveal
            waitForIdle()

            visible = false
            mainClock.advanceTimeByFrame()
            waitForIdle()

            // At progress 1 the ghost draws the card snapshot exactly: no wash while glow >= 0.6,
            // and the edge rules sit at cy +/- half, not the centre.
            assertEquals(
                CardColor,
                captureToImage().toPixelMap()[150, 100],
                "the ghost must be on screen, pixel-exact, before input is exercised",
            )

            onRoot().performMouseInput { click(center) }
            assertEquals(1, baseClicks, "input must reach the base immediately, the same frame the card left (D6)")
        }
    }

    @Test
    fun `case 2 - the ghost draws, then clears`() {
        val baseAlone = captureBaseAlone()
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(true)
            lateinit var ghost: CrtGhost
            setContent {
                ghost = rememberCrtGhost()
                GhostHost(visible = visible, ghost = ghost, onBaseClick = {}, shadow = false)
            }
            mainClock.advanceTimeBy(200)
            waitForIdle()

            visible = false
            mainClock.advanceTimeBy(16)
            waitForIdle()
            assertNotEquals(
                baseAlone,
                captureToImage().toPixelMap()[150, 100],
                "the ghost must still be drawing shortly after close",
            )

            mainClock.advanceTimeBy(300)
            waitForIdle()
            assertEquals(
                baseAlone,
                captureToImage().toPixelMap()[150, 100],
                "the ghost must fully clear once its 140 ms collapse ends",
            )
        }
    }

    @Test
    fun `case 3 - Default draws no ghost`() {
        val baseAlone = captureBaseAlone()
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(true)
            lateinit var ghost: CrtGhost
            setContent {
                ghost = rememberCrtGhost()
                GhostHost(visible = visible, ghost = ghost, onBaseClick = {}, shadow = false)
            }
            mainClock.advanceTimeBy(200)
            waitForIdle()

            visible = false
            mainClock.advanceTimeBy(16)
            waitForIdle()
            assertEquals(
                baseAlone,
                captureToImage().toPixelMap()[150, 100],
                "Default must show no ghost and record no snapshot",
            )
            assertFalse(ghost.hasSnapshot, "Default must never record a snapshot (D3)")
        }
    }

    // The real modals are Material Surfaces: their shadow and shape clip are child layers that
    // are released when the card leaves composition. The ghost still has to draw their content.
    @Test
    fun `case 5 - a real Material Surface card still ghosts after it leaves`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var visible by mutableStateOf(true)
            lateinit var ghost: CrtGhost
            setContent {
                ghost = rememberCrtGhost()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                    if (visible) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center).size(120.dp, 80.dp).crtCardReveal(ghost),
                            shape = RoundedCornerShape(8.dp),
                            color = CardColor,
                            shadowElevation = 4.dp,
                        ) {}
                    }
                    CrtGhostExit(visible, ghost, 0.45f)
                }
            }
            mainClock.advanceTimeBy(200)
            waitForIdle()

            visible = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(
                CardColor,
                captureToImage().toPixelMap()[150, 100],
                "the ghost of a Surface card must still draw its body after the card's layers are released",
            )
        }
    }

    @Test
    fun `case 4 - the Surface-shadow class survives the record path`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            lateinit var ghost: CrtGhost
            setContent {
                ghost = rememberCrtGhost()
                GhostHost(visible = true, ghost = ghost, onBaseClick = {}, shadow = true)
            }
            mainClock.advanceTimeBy(200)
            waitForIdle()

            // (80, 100) is 10 px outside the card's left edge (the card spans x in [90, 210],
            // y in [60, 140] on this 300x200 host), inside the drawBehind's 20 dp outset.
            assertEquals(
                Color.Red,
                captureToImage().toPixelMap()[80, 100],
                "content drawn outside the card's own bounds must survive the record path (review MAJOR-3)",
            )
        }
    }
}
