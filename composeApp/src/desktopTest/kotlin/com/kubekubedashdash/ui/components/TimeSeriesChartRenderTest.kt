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
}
