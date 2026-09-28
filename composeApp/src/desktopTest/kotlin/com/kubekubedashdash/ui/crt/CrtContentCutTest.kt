package com.kubekubedashdash.ui.crt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val HostSize = Size(300f, 200f)
private val ColorA = Color(0xFFAA3355)
private val ColorB = Color(0xFF33AA55)
private val ColorC = Color(0xFF3355AA)

/**
 * Exercises the WS11 row-to-row cut (D25): [crtContentCut] plays a
 * [CrtPanelTiming.TAB_CUT_MS] enter-only aperture whenever its key changes, never on the first
 * composition or on a same-key live update, and restarts closed when the key changes again
 * inside a running cut. Runs only against the Gradle test-data store; the manager's prior mode,
 * style and dark flag are restored after each case (CrtTabCutTest's pattern).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrtContentCutTest {

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

    @Test
    fun `case 1 - Retro first composition shows the key's colour with no aperture`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var key by mutableStateOf<Any?>("a")
            var color by mutableStateOf(ColorA)
            setContent {
                Box(Modifier.fillMaxSize().crtContentCut(key)) {
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
            waitForIdle()

            assertEquals(
                ColorA,
                captureToImage().toPixelMap()[150, 50],
                "the first composition must never play the cut (D25's first-composition rule)",
            )
        }
    }

    @Test
    fun `case 2 - a key change opens through the aperture and settles on the new colour`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var key by mutableStateOf<Any?>("a")
            var color by mutableStateOf(ColorA)
            setContent {
                Box(Modifier.fillMaxSize().crtContentCut(key)) {
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
            waitForIdle()

            key = "b"
            color = ColorB
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertNotEquals(
                ColorB,
                captureToImage().toPixelMap()[150, 50],
                "the new content must be drawn closed on the frame the key changes",
            )

            mainClock.advanceTimeBy(150)
            waitForIdle()
            assertEquals(
                ColorB,
                captureToImage().toPixelMap()[150, 50],
                "the aperture must be fully open once the 110 ms cut settles",
            )
        }
    }

    @Test
    fun `case 3 - a live update with the same key never plays the cut`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var key by mutableStateOf<Any?>("a")
            var color by mutableStateOf(ColorA)
            setContent {
                Box(Modifier.fillMaxSize().crtContentCut(key)) {
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
            waitForIdle()

            color = ColorC
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(
                ColorC,
                captureToImage().toPixelMap()[150, 50],
                "a live update that keeps the same key must show the new colour at once, with no aperture",
            )
        }
    }

    @Test
    fun `case 4 - a second key change inside 110 ms restarts the aperture closed on the newest key`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            var key by mutableStateOf<Any?>("a")
            var color by mutableStateOf(ColorA)
            setContent {
                Box(Modifier.fillMaxSize().crtContentCut(key)) {
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
            waitForIdle()

            key = "b"
            color = ColorB
            mainClock.advanceTimeByFrame()
            waitForIdle()
            mainClock.advanceTimeBy(50) // mid-aperture on key "b"
            waitForIdle()

            key = "c"
            color = ColorC
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertNotEquals(
                ColorC,
                captureToImage().toPixelMap()[150, 50],
                "the second key change must restart the aperture closed, not carry the first one's progress",
            )

            mainClock.advanceTimeBy(150)
            waitForIdle()
            assertEquals(ColorC, captureToImage().toPixelMap()[150, 50])
        }
    }

    @Test
    fun `case 5 - Default shows the new colour on the very next frame`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            mainClock.autoAdvance = false
            var key by mutableStateOf<Any?>("a")
            var color by mutableStateOf(ColorA)
            setContent {
                Box(Modifier.fillMaxSize().crtContentCut(key)) {
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
            waitForIdle()

            key = "b"
            color = ColorB
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(
                ColorB,
                captureToImage().toPixelMap()[150, 50],
                "Default never plays the cut (D3)",
            )
        }
    }
}
