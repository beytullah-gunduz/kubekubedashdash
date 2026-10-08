package com.kubekubedashdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.retroPulseAlpha
import com.kubekubedashdash.ui.components.skeletonRowAlpha
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stepped Retro motion of the skeleton rows, the live-data dot and the cluster avatar's
 * connection ring, against the smooth Default motion. The Compose cases freeze the frame clock and
 * count how many distinct images [SkikoComposeUiTest.captureToImage] sees over a run of frames: Retro
 * shows a fixed number of looks, Default glides through more. Every switch goes through `sync*`,
 * never a `set*`, so nothing here ever persists; the manager's prior mode, style and palette are
 * restored after each case and the test runs only against the Gradle test-data store (harness
 * copied from BusyScannerTest).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class RetroMotionTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalPalette: ThemePalette
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalPalette = ThemeManager.palette
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private fun pin(style: ThemeStyle, palette: ThemePalette) {
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncStyleFromPreferences(style)
        ThemeManager.syncPaletteFromPreferences(palette)
    }

    /** Advances [frames] frames on a frozen clock and returns how many distinct images were drawn. */
    private fun SkikoComposeUiTest.distinctFrames(frames: Int): Int {
        val seen = HashSet<Int>()
        repeat(frames) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            seen += captureToImage().toPixelMap().buffer.contentHashCode()
        }
        return seen.size
    }

    @Test
    fun `skeletonRowAlpha steps one bright row down and wraps`() {
        assertEquals(0.8f, skeletonRowAlpha(0, 0, 6))
        assertEquals(0.4f, skeletonRowAlpha(0, 1, 6))
        assertEquals(0.8f, skeletonRowAlpha(7, 1, 6))
        assertEquals(0.8f, skeletonRowAlpha(5, 5, 6))
        assertEquals(0.4f, skeletonRowAlpha(0, 0, 0))
    }

    @Test
    fun `retroPulseAlpha is a square wave`() {
        assertEquals(1f, retroPulseAlpha(0, 0.4f))
        assertEquals(0.4f, retroPulseAlpha(1, 0.4f))
        assertEquals(1f, retroPulseAlpha(2, 0.4f))
        assertEquals(0.35f, retroPulseAlpha(3, 0.35f))
    }

    @Test
    fun `Retro skeleton rows step through three looks`() = runSkikoComposeUiTest(size = Size(300f, 120f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(300.dp, 120.dp).background(Color.White)) {
                SkeletonRows(rowCount = 3)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(3, distinctFrames(40))
    }

    @Test
    fun `Default skeleton rows glide`() = runSkikoComposeUiTest(size = Size(300f, 120f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(300.dp, 120.dp).background(Color.White)) {
                SkeletonRows(rowCount = 3)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(distinctFrames(40) > 3)
    }

    @Test
    fun `Retro live dot toggles between two levels`() = runSkikoComposeUiTest(size = Size(20f, 20f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(20.dp, 20.dp).background(Color.White)) {
                LiveDataDot(isConnected = true, errorMessage = null)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(2, distinctFrames(120))
    }

    @Test
    fun `Default live dot glides`() = runSkikoComposeUiTest(size = Size(20f, 20f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(20.dp, 20.dp).background(Color.White)) {
                LiveDataDot(isConnected = true, errorMessage = null)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(distinctFrames(120) > 2)
    }

    @Test
    fun `Retro connecting ring steps through eight angles`() = runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(40.dp, 40.dp).background(Color.White)) {
                ClusterAvatar(color = Color.Gray, initial = "D", isConnected = null, isConnecting = true)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val n = distinctFrames(80)
        assertEquals(8, n, "one Retro lap should show the eight 45° angles, got $n")
    }

    @Test
    fun `Default connecting ring rotates smoothly`() = runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(40.dp, 40.dp).background(Color.White)) {
                ClusterAvatar(color = Color.Gray, initial = "D", isConnected = null, isConnecting = true)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(distinctFrames(80) > 8)
    }

    @Test
    fun `Retro disconnected ring breathes in two steps`() = runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(40.dp, 40.dp).background(Color.White)) {
                ClusterAvatar(color = Color.Gray, initial = "D", isConnected = false, isConnecting = false)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(2, distinctFrames(200))
    }

    @Test
    fun `Default disconnected ring breathes smoothly`() = runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(40.dp, 40.dp).background(Color.White)) {
                ClusterAvatar(color = Color.Gray, initial = "D", isConnected = false, isConnecting = false)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue(distinctFrames(200) > 2)
    }
}
