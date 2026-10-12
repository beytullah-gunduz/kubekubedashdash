package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.util.SystemDirectories
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CHART_WIDTH = 320
private const val CHART_HEIGHT = 260

// The card pads 10 dp, so at 1x the plot spans x = 10 .. 310.
private const val PLOT_LEFT = 10
private const val PLOT_WIDTH = 300

/**
 * What [TimeSeriesChart] draws, read back from pixels at 1x density over a white backdrop: a line per
 * series in the series colour (pure red here, which no theme colour is), no bridge across a data gap,
 * a dot for a lone sample, nothing under a note, and a hover readout that follows the pointer. Every
 * switch goes through `sync*`, never a `set*`, so nothing here ever persists; the manager's prior
 * mode, style and palette are restored after each case and the test runs only against the Gradle
 * test-data store (harness copied from [HalfGaugeTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class TimeSeriesChartRenderTest {

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
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncPaletteFromPreferences(ThemePalette.STYLE)
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
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

    private val window = QueryWindow(0, 180, 30)

    private fun points(vararg epochSecs: Long) = epochSecs.map { SeriesPoint(it, 1.0) }

    private fun SkikoComposeUiTest.show(
        series: List<ChartSeries>,
        note: String? = null,
        formatValue: (Double) -> String = { "" },
    ) {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(CHART_WIDTH.dp, CHART_HEIGHT.dp).background(Color.White)) {
                    TimeSeriesChart(
                        title = "",
                        series = series,
                        window = window,
                        formatValue = formatValue,
                        modifier = Modifier.fillMaxWidth(),
                        note = note,
                    )
                }
            }
        }
        waitForIdle()
    }

    // Pixels that are (nearly) pure red: the line, and nothing else a card draws.
    private fun PixelMap.redIn(xs: IntRange): Int {
        var count = 0
        for (x in xs) {
            for (y in 0 until height) {
                val c = this[x, y]
                if (c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f) count++
            }
        }
        return count
    }

    private val red = ChartSeries("", Color.Red, emptyList())

    @Test
    fun `a series with data is drawn as a line in its colour`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        show(listOf(red.copy(points = points(0, 30, 60, 90, 120, 150, 180))))
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.redIn(PLOT_LEFT..PLOT_LEFT + PLOT_WIDTH) > 200, "a 300 px line has at least that many red pixels")
    }

    @Test
    fun `Retro draws the line too`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        show(listOf(red.copy(points = points(0, 30, 60, 90, 120, 150, 180))))
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.redIn(PLOT_LEFT..PLOT_LEFT + PLOT_WIDTH) > 200)
    }

    @Test
    fun `a gap in the data is not bridged by a line`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        // 0..60 and 150..180 are two runs: x = 10..110 and 260..310.
        show(listOf(red.copy(points = points(0, 30, 60, 150, 180))))
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.redIn(20..100) > 50, "the first run is drawn")
        assertTrue(map.redIn(270..300) > 20, "the second run is drawn")
        assertEquals(0, map.redIn(125..245), "nothing is drawn across the gap")
    }

    @Test
    fun `a lone sample between gaps is drawn as a dot`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        // x = 10 and x = 160, each 90 s from its neighbour: no line anywhere, only two dots.
        show(listOf(red.copy(points = points(0, 90))))
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.redIn(PLOT_LEFT - 3..PLOT_LEFT + 3) > 0, "the first dot")
        assertTrue(map.redIn(PLOT_LEFT + 147..PLOT_LEFT + 153) > 0, "the second dot")
        assertEquals(0, map.redIn(PLOT_LEFT + 40..PLOT_LEFT + 110), "no line between them")
    }

    @Test
    fun `a note replaces the plot`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        show(listOf(red.copy(points = points(0, 30, 60))), note = "No access")
        val map = onRoot().captureToImage().toPixelMap()
        onNodeWithText("No access").assertExists()
        assertEquals(0, map.redIn(0 until CHART_WIDTH))
    }

    @Test
    fun `a series without points says there is no data`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        show(listOf(red))
        val map = onRoot().captureToImage().toPixelMap()
        onNodeWithText("No data in this range").assertExists()
        assertEquals(0, map.redIn(0 until CHART_WIDTH))
    }

    @Test
    fun `hovering reads the values at the nearest sample and leaving clears them`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val low = ChartSeries("alpha", Color.Red, listOf(SeriesPoint(0, 1.0), SeriesPoint(90, 2.0), SeriesPoint(180, 3.0)))
        val high = ChartSeries("beta", Color.Blue, listOf(SeriesPoint(0, 4.0), SeriesPoint(90, 5.0), SeriesPoint(180, 6.0)))
        show(listOf(low, high), formatValue = { String.format(Locale.ROOT, "%.0f", it) })
        onNodeWithText("alpha 1", substring = true).assertDoesNotExist()

        // The plot is 96 dp high from about y = 30; y = 60 is inside it.
        onRoot().performMouseInput { moveTo(Offset(PLOT_LEFT + 5f, 60f)) }
        waitForIdle()
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).format(Instant.ofEpochSecond(0).atZone(ZoneId.systemDefault()))
        onNodeWithText("$clock  alpha 1  beta 4").assertExists()

        onRoot().performMouseInput { moveTo(Offset(PLOT_LEFT + PLOT_WIDTH - 5f, 60f)) }
        waitForIdle()
        onNodeWithText("alpha 3  beta 6", substring = true).assertExists()
        onNodeWithText("alpha 1", substring = true).assertDoesNotExist()

        onRoot().performMouseInput { moveTo(Offset(PLOT_LEFT + PLOT_WIDTH / 2f, 60f)) }
        waitForIdle()
        onNodeWithText("alpha 2  beta 5", substring = true).assertExists()

        // Leaving from the right end: were the exit ignored, the last sample's readout would stay.
        onRoot().performMouseInput { moveTo(Offset(PLOT_LEFT + PLOT_WIDTH - 5f, 60f)) }
        waitForIdle()
        onNodeWithText("alpha 3  beta 6", substring = true).assertExists()
        onRoot().performMouseInput { exit(Offset(CHART_WIDTH - 2f, CHART_HEIGHT - 2f)) }
        waitForIdle()
        onNodeWithText("alpha 3", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a lone series reads its minimum and maximum until hovered`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val only = ChartSeries("", Color.Red, listOf(SeriesPoint(0, 2.0), SeriesPoint(90, 7.0), SeriesPoint(180, 4.0)))
        show(listOf(only), formatValue = { String.format(Locale.ROOT, "%.0f", it) })
        onNodeWithText("Min: 2 · Max: 7").assertExists()
    }

    // ── Markers and muted series ────────────────────────────────────────────────

    // 600 s over 300 px: one second is half a pixel, so epoch 201 lands on the middle of pixel 110.
    private val wideWindow = QueryWindow(0, 600, 30)
    private val markerColumn = PLOT_LEFT + 100

    private fun flat(value: Double) = (0L..600L step 30).map { SeriesPoint(it, value) }

    private fun SkikoComposeUiTest.showWide(
        series: List<ChartSeries>,
        markers: List<ChartMarker> = emptyList(),
        formatValue: (Double) -> String = { String.format(Locale.ROOT, "%.0f", it) },
    ) {
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(CHART_WIDTH.dp, CHART_HEIGHT.dp).background(Color.White)) {
                    TimeSeriesChart(
                        title = "",
                        series = series,
                        window = wideWindow,
                        formatValue = formatValue,
                        modifier = Modifier.fillMaxWidth(),
                        markers = markers,
                    )
                }
            }
        }
        waitForIdle()
    }

    // The plot ends above y = 130; the legend row sits below it.
    private val plotRows = 0 until 130

    private fun PixelMap.count(xs: IntRange, ys: IntRange = 0 until height, match: (Color) -> Boolean): Int {
        var count = 0
        for (x in xs) {
            for (y in ys) {
                if (match(this[x, y])) count++
            }
        }
        return count
    }

    // Plot pixels redder than the card's own background, read from the padding at the same row: a fill under a red line.
    private fun PixelMap.redderThanCard(rows: IntRange): Int {
        var count = 0
        for (y in rows) {
            val background = this[3, y]
            for (x in PLOT_LEFT + 10..PLOT_LEFT + PLOT_WIDTH - 10) {
                val c = this[x, y]
                if ((c.red - c.blue) - (background.red - background.blue) > 0.04f) count++
            }
        }
        return count
    }

    // Loose enough for a marker under a lone series' translucent fill, far from every other theme colour.
    private fun Color.isNear(other: Color) = abs(red - other.red) <= 0.15f && abs(green - other.green) <= 0.15f && abs(blue - other.blue) <= 0.15f

    private fun Color.isBlue() = blue > 0.6f && red < 0.3f && green < 0.3f

    private fun Color.isGreen() = green > 0.6f && red < 0.3f && blue < 0.3f

    @Test
    fun `a marker inside the window is a dashed line at its time and a legend entry`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        showWide(listOf(red.copy(points = flat(1.0))), markers = listOf(ChartMarker(201, "revision 7")))
        val map = onRoot().captureToImage().toPixelMap()
        val warning = KdWarning
        // 96 rows of a 6 on, 4 off dash: about 58 lit pixels in the marker's column.
        assertTrue(map.count(markerColumn..markerColumn, plotRows) { it.isNear(warning) } > 40, "the marker's column is lit")
        assertEquals(0, map.count(markerColumn - 5..markerColumn - 2, plotRows) { it.isNear(warning) }, "nothing left of it")
        assertEquals(0, map.count(markerColumn + 3..markerColumn + 6, plotRows) { it.isNear(warning) }, "nothing right of it")
        onNodeWithText("revision 7").assertExists()
    }

    @Test
    fun `a marker outside the window is neither drawn nor listed`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        // 610 and -10 would sit at x = 315 and x = 5, inside the card but outside the plot.
        showWide(
            listOf(red.copy(points = flat(1.0))),
            markers = listOf(ChartMarker(610, "after the window"), ChartMarker(-10, "before the window")),
        )
        val map = onRoot().captureToImage().toPixelMap()
        assertEquals(0, map.count(0 until CHART_WIDTH) { it.isNear(KdWarning) })
        onNodeWithText("after the window").assertDoesNotExist()
        onNodeWithText("before the window").assertDoesNotExist()
    }

    @Test
    fun `markers at the window's edges are kept`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        showWide(
            listOf(red.copy(points = flat(1.0))),
            markers = listOf(ChartMarker(0, "at the start"), ChartMarker(600, "at the end")),
        )
        onNodeWithText("at the start").assertExists()
        onNodeWithText("at the end").assertExists()
    }

    @Test
    fun `a muted series is drawn but has no legend entry and no readout`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val coloured = ChartSeries("alpha", Color.Red, flat(40.0))
        val ghost = ChartSeries("ghost", Color.Blue, flat(10.0), muted = true)
        showWide(listOf(coloured, ghost))
        val before = onRoot().captureToImage().toPixelMap()
        val blueBefore = before.count(PLOT_LEFT + 10..PLOT_LEFT + PLOT_WIDTH - 10, plotRows) { it.isBlue() }
        assertTrue(blueBefore > 200, "the muted line is drawn in its own colour")
        onNodeWithText("ghost", substring = true).assertDoesNotExist()
        onNodeWithText("1 earlier pod").assertExists()

        onRoot().performMouseInput { moveTo(Offset(PLOT_LEFT + PLOT_WIDTH / 2f, 60f)) }
        waitForIdle()
        onNodeWithText("alpha 40", substring = true).assertExists()
        onNodeWithText("ghost", substring = true).assertDoesNotExist()
        // The crosshair may cover a few of the line's pixels; a dot on the muted line would add many.
        val after = onRoot().captureToImage().toPixelMap()
        assertTrue(after.count(PLOT_LEFT + 10..PLOT_LEFT + PLOT_WIDTH - 10, plotRows) { it.isBlue() } <= blueBefore, "no hover dot on a muted line")
    }

    @Test
    fun `the earlier-pods entry counts every muted series handed in`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val coloured = ChartSeries("alpha", Color.Red, flat(40.0))
        val older = (1..15).map { ChartSeries("old-$it", Color.Blue, flat(it.toDouble()), muted = true) }
        showWide(listOf(coloured) + older)
        onNodeWithText("15 earlier pods").assertExists()
        onNodeWithText("old-1", substring = true).assertDoesNotExist()
        onNodeWithText("more", substring = true).assertDoesNotExist()
    }

    @Test
    fun `thirteen muted series draw twelve and drop the lowest peak`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val lowest = ChartSeries("old-0", Color.Green, flat(1.0), muted = true)
        val others = (2..13).map { ChartSeries("old-$it", Color.Blue, flat(it.toDouble()), muted = true) }
        showWide(listOf(lowest) + others)
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.count(PLOT_LEFT + 10..PLOT_LEFT + PLOT_WIDTH - 10, plotRows) { it.isBlue() } > 200, "the twelve are drawn")
        assertEquals(0, map.count(0 until CHART_WIDTH, plotRows) { it.isGreen() }, "the lowest peak is dropped")
        onNodeWithText("13 earlier pods").assertExists()
    }

    @Test
    fun `twelve muted series are all drawn`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val lowest = ChartSeries("old-0", Color.Green, flat(1.0), muted = true)
        val others = (2..12).map { ChartSeries("old-$it", Color.Blue, flat(it.toDouble()), muted = true) }
        showWide(listOf(lowest) + others)
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.count(PLOT_LEFT + 10..PLOT_LEFT + PLOT_WIDTH - 10, plotRows) { it.isGreen() } > 200, "the lowest peak is among the twelve")
        onNodeWithText("12 earlier pods").assertExists()
    }

    @Test
    fun `a muted line is not clipped by the axis`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        // The coloured line peaks at 1 and the grey one at 100: were the axis to ignore it, it would sit above the plot.
        showWide(listOf(ChartSeries("alpha", Color.Red, flat(1.0)), ChartSeries("ghost", Color.Blue, flat(100.0), muted = true)))
        val map = onRoot().captureToImage().toPixelMap()
        assertTrue(map.count(PLOT_LEFT + 10..PLOT_LEFT + PLOT_WIDTH - 10, plotRows) { it.isBlue() } > 200)
        onNodeWithText("115").assertExists()
    }

    @Test
    fun `muted series do not count toward the coloured limit`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val coloured = (0 until MAX_CHART_SERIES).map { ChartSeries("pod-$it", Color.Red, flat(it + 1.0)) }
        val older = (0 until 3).map { ChartSeries("old-$it", Color.Blue, flat(it + 1.0), muted = true) }
        showWide(coloured + older)
        onNodeWithText("more", substring = true).assertDoesNotExist()
        onNodeWithText("3 earlier pods").assertExists()
        onNodeWithText("pod-5").assertExists()
    }

    @Test
    fun `coloured series beyond the limit are still counted as more`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        val coloured = (0 until MAX_CHART_SERIES + 1).map { ChartSeries("pod-$it", Color.Red, flat(it + 1.0)) }
        val older = (0 until 3).map { ChartSeries("old-$it", Color.Blue, flat(it + 1.0), muted = true) }
        showWide(coloured + older)
        onNodeWithText("+1 more").assertExists()
        onNodeWithText("3 earlier pods").assertExists()
    }

    @Test
    fun `a lone coloured series shows its latest value in the header`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        showWide(listOf(ChartSeries("total", Color.Red, flat(40.0))))
        onNodeWithText("40").assertExists()
    }

    @Test
    fun `a coloured series among muted ones has no header value and no fill`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        showWide(listOf(ChartSeries("alpha", Color.Red, flat(40.0)), ChartSeries("ghost", Color.Blue, flat(10.0), muted = true)))
        onNodeWithText("40").assertDoesNotExist()
        assertEquals(0, onRoot().captureToImage().toPixelMap().redderThanCard(60 until 120), "no fill under the coloured line")
    }

    @Test
    fun `a lone coloured series is filled under its line`() = runSkikoComposeUiTest(size = Size(CHART_WIDTH.toFloat(), CHART_HEIGHT.toFloat()), density = Density(1f)) {
        showWide(listOf(ChartSeries("total", Color.Red, flat(40.0))))
        assertTrue(onRoot().captureToImage().toPixelMap().redderThanCard(60 until 120) > 1000, "the control: a lone line has a fill")
    }
}
