package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val Backdrop = Color.White

/**
 * [HalfCircularUsageIndicator] and its segment count, drawn at 1x density over a white backdrop. The
 * gauge canvas is 96 x 56 px at the top-left of a 96 x 100 px host; the arc centre is (48, 48) and the
 * stroke centre radius 44.5 px, so segment `i` spans `[181.5 + 10i, 188.5 + 10i]` degrees (0 degrees at
 * 3 o'clock, clockwise). Default keeps one continuous arc; Retro draws 18 separate segments. Every switch
 * goes through `sync*`, never a `set*`, so nothing here ever persists; the manager's prior mode, style
 * and palette are restored after each case and the test runs only against the Gradle test-data store
 * (harness copied from [ItemProgressBarTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class HalfGaugeTest {

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

    private fun PixelMap.assertColor(x: Int, y: Int, expected: Color) {
        val actual = this[x, y]
        val close = abs(actual.red - expected.red) < 0.05f &&
            abs(actual.green - expected.green) < 0.05f &&
            abs(actual.blue - expected.blue) < 0.05f &&
            abs(actual.alpha - expected.alpha) < 0.05f
        assertTrue(close, "pixel ($x, $y) should be $expected but is $actual")
    }

    @Test
    fun `gaugeSegments lights any non-zero value and caps at eighteen`() {
        assertEquals(0, gaugeSegments(0f))
        assertEquals(1, gaugeSegments(0.01f))
        assertEquals(9, gaugeSegments(0.5f))
        assertEquals(18, gaugeSegments(1f))
        assertEquals(18, gaugeSegments(1.2f))
    }

    @Test
    fun `Retro lights half the segments at 50 percent`() = runSkikoComposeUiTest(size = Size(96f, 100f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        val lit = usageLevel(0.5f).color()
        val track = KdSurfaceVariant
        setContent {
            Box(Modifier.size(96.dp, 100.dp).background(Backdrop)) {
                HalfCircularUsageIndicator(0.5f, "CPU", "1", "2")
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        map.assertColor(3, 44, lit)
        map.assertColor(44, 3, lit)
        map.assertColor(51, 3, track)
        map.assertColor(92, 44, track)
    }

    @Test
    fun `Default keeps a continuous arc`() = runSkikoComposeUiTest(size = Size(96f, 100f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        val lit = usageLevel(0.5f).color()
        val track = KdSurfaceVariant
        setContent {
            Box(Modifier.size(96.dp, 100.dp).background(Backdrop)) {
                HalfCircularUsageIndicator(0.5f, "CPU", "1", "2")
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        map.assertColor(3, 44, lit)
        // (4, 40) sits at the 190 degree angle, inside a Retro gap but covered by Default's arc.
        map.assertColor(4, 40, lit)
        map.assertColor(44, 3, lit)
        map.assertColor(58, 5, track)
    }
}
