package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val Backdrop = Color.White
private val Ink = Color.Red

/**
 * [ItemProgressBar] and its pure helpers: the segment count, the lit share, the clamped fraction, the
 * whole-pixel segment edges, and the drawn result at 1x density over a white backdrop in a red ink.
 * Default keeps Material's bar; Retro draws one square-ended segment per item. Every switch goes
 * through `sync*`, never a `set*`, so nothing here ever persists; the manager's prior mode, style and
 * palette are restored after each case and the test runs only against the Gradle test-data store
 * (harness copied from [BusyScannerTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ItemProgressBarTest {

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
    fun `segmentCount is one per item up to the cap`() {
        assertEquals(40, segmentCount(0))
        assertEquals(40, segmentCount(-3))
        assertEquals(1, segmentCount(1))
        assertEquals(12, segmentCount(12))
        assertEquals(40, segmentCount(40))
        assertEquals(40, segmentCount(41))
    }

    @Test
    fun `litSegments is one per finished item then a floored share`() {
        assertEquals(0, litSegments(0, 0))
        assertEquals(5, litSegments(5, 12))
        assertEquals(12, litSegments(13, 12))
        assertEquals(0, litSegments(-1, 12))
        assertEquals(10, litSegments(50, 200))
        assertEquals(39, litSegments(199, 200))
        assertEquals(40, litSegments(200, 200))
    }

    @Test
    fun `itemFraction is clamped and zero without a total`() {
        assertEquals(0.5f, itemFraction(6, 12))
        assertEquals(0f, itemFraction(1, 0))
        assertEquals(1f, itemFraction(13, 12))
        assertEquals(0f, itemFraction(-2, 12))
    }

    @Test
    fun `itemSegments are whole pixels with exact gaps`() {
        assertEquals(
            listOf(0f to 23f, 25f to 49f, 51f to 74f, 76f to 100f),
            itemSegments(100f, 4, 2f),
        )

        val forty = itemSegments(400f, 200, 2f)
        assertEquals(40, forty.size)
        forty.forEach { (left, right) ->
            assertEquals(floor(left), left, "left of ($left, $right)")
            assertEquals(floor(right), right, "right of ($left, $right)")
        }
        for (i in 0 until forty.size - 1) {
            assertEquals(2f, forty[i + 1].first - forty[i].second, "gap after segment $i")
        }
        assertEquals(0f, forty.first().first)
        assertEquals(400f, forty.last().second)

        val unknown = itemSegments(100f, 0, 2f)
        assertEquals(40, unknown.size)
        for (i in 0 until unknown.size - 1) {
            assertEquals(unknown[i].second, unknown[i + 1].first, "no gap after segment $i")
        }

        assertTrue(itemSegments(30f, 40, 2f).isEmpty())
        assertTrue(itemSegments(0f, 4, 2f).isEmpty())
        assertTrue(itemSegments(Float.NaN, 4, 2f).isEmpty())
        assertTrue(itemSegments(Float.POSITIVE_INFINITY, 4, 2f).isEmpty())
    }

    @Test
    fun `Default exposes the same progress as Material`() = runSkikoComposeUiTest(size = Size(200f, 20f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        setContent { ItemProgressBar(6, 12, Modifier.width(200.dp)) }
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertExists()
    }

    @Test
    fun `Retro exposes the same progress and is 8 dp tall`() = runSkikoComposeUiTest(size = Size(200f, 20f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        setContent { ItemProgressBar(6, 12, Modifier.width(200.dp)) }
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)))
            .assertExists()
            .assertHeightIsEqualTo(8.dp)
            .assertWidthIsEqualTo(200.dp)
    }

    @Test
    fun `Retro lights one segment per finished item`() = runSkikoComposeUiTest(size = Size(100f, 8f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        val track = KdBorder
        setContent {
            Box(Modifier.size(100.dp, 8.dp).background(Backdrop)) {
                ItemProgressBar(2, 4, Modifier.width(100.dp), color = Ink)
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        map.assertColor(10, 4, Ink)
        map.assertColor(35, 4, Ink)
        map.assertColor(24, 4, Backdrop)
        // Pending segments 2 [51, 74) and 3 [76, 100) are 1 px outlines with a hollow centre.
        map.assertColor(60, 4, Backdrop)
        map.assertColor(51, 4, track)
        map.assertColor(60, 0, track)
        map.assertColor(60, 7, track)
        map.assertColor(73, 4, track)
        map.assertColor(90, 4, Backdrop)
        map.assertColor(76, 4, track)
    }

    @Test
    fun `Retro shares 40 segments above 40 items`() = runSkikoComposeUiTest(size = Size(400f, 8f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        val track = KdBorder
        setContent {
            Box(Modifier.size(400.dp, 8.dp).background(Backdrop)) {
                ItemProgressBar(50, 200, Modifier.width(400.dp), color = Ink)
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        // Segment 9 spans [90, 98) and is the last lit one; segment 10 spans [100, 108) and is not.
        map.assertColor(94, 4, Ink)
        map.assertColor(104, 4, Backdrop)
        map.assertColor(100, 4, track)
        map.assertColor(99, 4, Backdrop)
    }

    @Test
    fun `Retro before the total is known is an empty 40-segment track`() = runSkikoComposeUiTest(size = Size(400f, 8f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        val track = KdBorder
        setContent {
            Box(Modifier.size(400.dp, 8.dp).background(Backdrop)) {
                ItemProgressBar(0, 0, Modifier.width(400.dp), color = Ink)
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        // Segment 0 spans [0, 8) and segment 39 spans [391, 400): both hollow.
        map.assertColor(0, 4, track)
        map.assertColor(4, 4, Backdrop)
        map.assertColor(391, 4, track)
        map.assertColor(395, 4, Backdrop)
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f))).assertExists()
    }

    @Test
    fun `Retro fills from the right in RTL`() = runSkikoComposeUiTest(size = Size(100f, 8f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        val track = KdBorder
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(100.dp, 8.dp).background(Backdrop)) {
                    ItemProgressBar(1, 4, Modifier.width(100.dp), color = Ink)
                }
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        map.assertColor(90, 4, Ink)
        map.assertColor(10, 4, Backdrop)
        map.assertColor(0, 4, track)
    }

    @Test
    fun `Retro redraws when done or the colour change`() = runSkikoComposeUiTest(size = Size(100f, 8f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        var done by mutableStateOf(0)
        var ink by mutableStateOf(Ink)
        setContent {
            Box(Modifier.size(100.dp, 8.dp).background(Backdrop)) {
                ItemProgressBar(done, 4, Modifier.width(100.dp), color = ink)
            }
        }
        waitForIdle()
        captureToImage().toPixelMap().assertColor(10, 4, Backdrop)
        done = 2
        waitForIdle()
        captureToImage().toPixelMap().run {
            assertColor(10, 4, Ink)
            assertColor(35, 4, Ink)
            assertColor(60, 4, Backdrop)
        }
        ink = Color.Blue
        waitForIdle()
        captureToImage().toPixelMap().assertColor(10, 4, Color.Blue)
        done = 0
        waitForIdle()
        captureToImage().toPixelMap().assertColor(10, 4, Backdrop)
    }

    @Test
    fun `every palette tells finished from pending by shape`() {
        for (palette in ThemePalette.entries) {
            for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                runSkikoComposeUiTest(size = Size(100f, 8f), density = Density(1f)) {
                    ThemeManager.syncFromPreferences(mode)
                    ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
                    ThemeManager.syncPaletteFromPreferences(palette)
                    val track = KdBorder
                    setContent {
                        Box(Modifier.size(100.dp, 8.dp).background(Backdrop)) {
                            ItemProgressBar(2, 4, Modifier.width(100.dp), color = Ink)
                        }
                    }
                    waitForIdle()
                    val map = captureToImage().toPixelMap()
                    // Finished: filled to the centre. Pending: outline in the track colour, hollow centre.
                    map.assertColor(10, 4, Ink)
                    map.assertColor(51, 4, track)
                    map.assertColor(60, 4, Backdrop)
                }
            }
        }
    }
}
