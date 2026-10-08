package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdAccent
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

/** Resting scanner cell: [Ink] at 0.14 over [Backdrop]. */
private val Resting = Color(1f, 0.86f, 0.86f)

/**
 * [BusyScanner], [LoadingCaption] and their pure helpers: the shared frame step, the scanner head and
 * trail, the cell geometry, the Retro caption text, and the drawn result at 1x density over a white
 * backdrop in a red ink. Default keeps the ring; Retro draws a row of square cells and a blinking
 * block cursor. Every switch goes through `sync*`, never a `set*`, so nothing here ever persists; the
 * manager's prior mode, style and palette are restored after each case and the test runs only
 * against the Gradle test-data store (harness copied from [BusyIndicatorTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class BusyScannerTest {

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

    private fun PixelMap.isInk(x: Int, y: Int): Boolean {
        val actual = this[x, y]
        return abs(actual.red - Ink.red) < 0.05f &&
            abs(actual.green - Ink.green) < 0.05f &&
            abs(actual.blue - Ink.blue) < 0.05f &&
            abs(actual.alpha - Ink.alpha) < 0.05f
    }

    /** The index of the one fully lit cell of the 147 px scanner row at pixel row [y]; fails unless exactly one is lit. */
    private fun litCell(map: PixelMap, y: Int): Int {
        val lit = (0 until SCANNER_CELLS).filter { index -> map.isInk(index * 15 + 5, y) }
        assertEquals(1, lit.size, "exactly one cell of the scanner at y=$y should be fully lit, but lit cells are $lit")
        return lit.single()
    }

    @Test
    fun `frameStep wraps at the cycle`() {
        assertEquals(0, frameStep(0, 80, 18))
        assertEquals(0, frameStep(79, 80, 18))
        assertEquals(1, frameStep(80, 80, 18))
        assertEquals(17, frameStep(1439, 80, 18))
        assertEquals(0, frameStep(1440, 80, 18))
    }

    @Test
    fun `the head sweeps right then back`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 8, 7, 6, 5, 4, 3, 2, 1),
            (0 until 18).map { scannerHead(it) },
        )
        assertTrue(scannerMovingRight(8))
        assertTrue(!scannerMovingRight(9))
        assertTrue(!scannerMovingRight(17))
        assertTrue(scannerMovingRight(18))
    }

    @Test
    fun `the trail follows the direction`() {
        // Step 3: head 3, moving right.
        assertEquals(1f, scannerCellAlpha(3, 3, highContrast = false))
        assertEquals(0.55f, scannerCellAlpha(3, 2, highContrast = false))
        assertEquals(0.3f, scannerCellAlpha(3, 1, highContrast = false))
        assertEquals(0.14f, scannerCellAlpha(3, 0, highContrast = false))
        assertEquals(0.14f, scannerCellAlpha(3, 4, highContrast = false))

        // Step 12: head 6, moving left.
        assertEquals(1f, scannerCellAlpha(12, 6, highContrast = false))
        assertEquals(0.55f, scannerCellAlpha(12, 7, highContrast = false))
        assertEquals(0.3f, scannerCellAlpha(12, 8, highContrast = false))
        assertEquals(0.14f, scannerCellAlpha(12, 5, highContrast = false))

        assertEquals(0.75f, scannerCellAlpha(3, 2, highContrast = true))
        assertEquals(0.5f, scannerCellAlpha(3, 1, highContrast = true))
        assertEquals(0f, scannerCellAlpha(3, 0, highContrast = true))
    }

    @Test
    fun `scanner cells are whole pixels, centred`() {
        val full = scannerCells(147f, 12f, 10)
        assertEquals(10, full.size)
        assertTrue(full.all { it.side == 12f && it.top == 0f }, "every cell of the 147 x 12 row should be 12 px at the top: $full")
        assertEquals((0 until 10).map { it * 15f }, full.map { it.left })

        val centred = scannerCells(123f, 10f, 10)
        assertEquals(10, centred.size)
        assertTrue(centred.all { it.side == 10f }, "every cell of the 123 x 10 row should be 10 px: $centred")
        assertEquals(2f, centred.first().left)
        assertEquals(110f, centred.last().left)

        val fractional = scannerCells(147.6f, 12.4f, 10)
        assertTrue(fractional.isNotEmpty())
        fractional.forEach { cell ->
            assertEquals(floor(cell.left), cell.left, "left of $cell")
            assertEquals(floor(cell.top), cell.top, "top of $cell")
            assertEquals(floor(cell.side), cell.side, "side of $cell")
        }

        val narrow = scannerCells(50f, 12f, 10)
        assertTrue(narrow.isNotEmpty())
        assertTrue(narrow.all { it.side == 2f }, "a 50 px row should shrink its cells to 2 px: $narrow")

        assertTrue(scannerCells(5f, 12f, 10).isEmpty())
        assertTrue(scannerCells(0f, 0f, 10).isEmpty())
        assertTrue(scannerCells(Float.NaN, Float.NaN, 10).isEmpty())
    }

    @Test
    fun `Retro caption text`() {
        assertEquals("CONNECTING TO CLUSTER", retroCaptionText("Connecting to cluster...", caps = true))
        assertEquals("LOADING CLUSTERS", retroCaptionText("Loading clusters…", caps = true))
        assertEquals("Connecting to my-context", retroCaptionText("Connecting to my-context…", caps = false))
        assertEquals("DONE", retroCaptionText("Done", caps = true))
    }

    @Test
    fun `trailAlpha is the ring's trail`() {
        assertEquals(1f, trailAlpha(0, 2, highContrast = false))
        assertEquals(0.55f, trailAlpha(1, 2, highContrast = false))
        assertEquals(0.3f, trailAlpha(2, 2, highContrast = false))
        assertEquals(0.14f, trailAlpha(2, 1, highContrast = false))
        assertEquals(0.14f, trailAlpha(-1, 2, highContrast = false))
        assertEquals(0.75f, trailAlpha(1, 2, highContrast = true))
        assertEquals(0f, trailAlpha(5, 2, highContrast = true))
    }

    @Test
    fun `Default keeps the ring at ringSize`() = runSkikoComposeUiTest(size = Size(200f, 60f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        setContent { BusyScanner(ringSize = 24.dp) }
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertWidthIsEqualTo(24.dp)
            .assertHeightIsEqualTo(24.dp)
    }

    @Test
    fun `Retro draws a 147 by 12 scanner for a 48 dp ring`() = runSkikoComposeUiTest(size = Size(200f, 60f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        setContent { BusyScanner(ringSize = 48.dp) }
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertWidthIsEqualTo(147.dp)
            .assertHeightIsEqualTo(12.dp)
    }

    @Test
    fun `Retro scanner lights the first cell at step 0`() = runSkikoComposeUiTest(size = Size(147f, 12f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        setContent {
            Box(Modifier.size(147.dp, 12.dp).background(Backdrop)) {
                BusyScanner(ringSize = 48.dp, color = Ink)
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        map.assertColor(5, 5, Ink)
        map.assertColor(20, 5, Resting)
        map.assertColor(13, 5, Backdrop)
        map.assertColor(140, 5, Resting)
    }

    @Test
    fun `High Contrast scanner drops resting cells`() = runSkikoComposeUiTest(size = Size(147f, 12f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.HIGH_CONTRAST)
        setContent {
            Box(Modifier.size(147.dp, 12.dp).background(Backdrop)) {
                BusyScanner(ringSize = 48.dp, color = Ink)
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        map.assertColor(5, 5, Ink)
        map.assertColor(20, 5, Backdrop)
    }

    @Test
    fun `Retro scanners step in phase with the frame clock`() = runSkikoComposeUiTest(size = Size(147f, 28f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Column(Modifier.size(147.dp, 28.dp).background(Backdrop)) {
                BusyScanner(ringSize = 48.dp, color = Ink)
                Spacer(Modifier.height(4.dp))
                BusyScanner(ringSize = 48.dp, color = Ink)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val first = captureToImage().toPixelMap()
        val h0 = litCell(first, 5)
        assertEquals(h0, litCell(first, 21), "the second scanner should be in phase with the first")

        var head = h0
        var map = first
        var frames = 0
        while (frames < 20 && head == h0) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            map = captureToImage().toPixelMap()
            head = litCell(map, 5)
            frames++
        }
        assertTrue(head != h0, "the lit cell should move within 20 frames, but stayed on cell $h0")
        assertEquals(1, abs(head - h0), "the lit cell should move exactly one cell")
        assertEquals(head, litCell(map, 21), "the second scanner should still be in phase after the step")
    }

    @Test
    fun `LoadingCaption keeps the text in Default and caps it with a cursor in Retro`() = runSkikoComposeUiTest(size = Size(400f, 60f), density = Density(1f)) {
        pin(ThemeStyle.DEFAULT, ThemePalette.STYLE)
        setContent { LoadingCaption("Connecting to cluster...") }
        onNodeWithText("Connecting to cluster...").assertExists()

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        waitForIdle()
        onNodeWithText("CONNECTING TO CLUSTER").assertExists()
        onAllNodesWithText("Connecting to cluster...").assertCountEquals(0)
    }

    @Test
    fun `LoadingCaption keeps a name's case when caps is false`() = runSkikoComposeUiTest(size = Size(400f, 60f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        setContent { LoadingCaption("Connecting to my-context…", caps = false) }
        onNodeWithText("Connecting to my-context").assertExists()
    }

    @Test
    fun `the cursor blinks every 500 ms`() = runSkikoComposeUiTest(size = Size(30f, 30f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(30.dp).background(Backdrop)) {
                BlinkingCursor(20.sp, Ink)
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        val on0 = captureToImage().toPixelMap().isInk(9, 10)

        var toggled = false
        var frames = 0
        while (frames < 40 && !toggled) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            toggled = captureToImage().toPixelMap().isInk(9, 10) != on0
            frames++
        }
        assertTrue(toggled, "the cursor should toggle within 40 frames, but stayed ${if (on0) "on" else "off"}")

        var back = false
        frames = 0
        while (frames < 40 && !back) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            back = captureToImage().toPixelMap().isInk(9, 10) == on0
            frames++
        }
        assertTrue(back, "the cursor should toggle back within another 40 frames")
    }

    @Test
    fun `a wrapping Retro caption still draws its cursor in the accent colour`() = runSkikoComposeUiTest(size = Size(200f, 120f), density = Density(1f)) {
        pin(ThemeStyle.RETRO, ThemePalette.STYLE)
        val label = "Connecting to some-namespace/some-deployment-7c9f8d6b5-x2x9q · container…"
        setContent {
            Box(Modifier.size(200.dp, 120.dp).background(Color.Black)) {
                LoadingCaption(label, style = TextStyle(fontSize = 14.sp), color = Color.White, caps = false)
            }
        }
        waitForIdle()
        val shown = label.removeSuffix("…")
        val textHeight = onNodeWithText(shown).fetchSemanticsNode().size.height
        assertTrue(textHeight > 28, "the caption should wrap onto several lines in 200 px, but is $textHeight px tall")

        val accent = KdAccent
        val map = captureToImage().toPixelMap()
        val cursorPixels = (0 until map.width).sumOf { x ->
            (0 until map.height).count { y ->
                val c = map[x, y]
                abs(c.red - accent.red) < 0.05f && abs(c.green - accent.green) < 0.05f && abs(c.blue - accent.blue) < 0.05f
            }
        }
        assertTrue(cursorPixels > 20, "the cursor should be drawn in the accent colour beside a wrapped caption, found $cursorPixels pixels")
    }
}
