package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CHART_W = 320
private const val CHART_H = 300
private const val CHART_TAG = "chart"

/**
 * [TimeSeriesChart]'s loading skeleton and its Retro reveal. The skeleton keeps the loaded chart's
 * height (so nothing below it moves when the data lands), a note beats it, and a Default chart that
 * went loading -> loaded is pixel-identical to one first composed loaded. In Retro the data lights up
 * with a CRT power-on and a beam trace, which a chart first composed loaded never plays. Every theme
 * switch goes through `sync*`, never a `set*`, so nothing here ever persists; the manager's prior
 * mode, style and palette are restored after each case and the test runs only against the Gradle
 * test-data store (harness copied from [TimeSeriesChartRenderTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class TimeSeriesChartSkeletonTest {

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
        ScreenshotHooks.crtTimeScale.value = 1
    }

    @AfterTest
    fun restore() {
        ScreenshotHooks.crtTimeScale.value = 1
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private val window = QueryWindow(0, 180, 30)

    private val loadedOne = listOf(ChartSeries("", Color.Red, (0L..180L step 30).map { SeriesPoint(it, 1.0 + it / 60.0) }))

    /** One series ending on 7: its header value "v7" is unlike the "v8" axis label. */
    private val loadedSeven = listOf(ChartSeries("", Color.Red, (0L..180L step 30).map { SeriesPoint(it, 1.0 + it / 30.0) }))

    private val loadedTwo = listOf(
        ChartSeries("alpha", Color.Red, (0L..180L step 30).map { SeriesPoint(it, 1.0 + it / 60.0) }),
        ChartSeries("beta", Color.Blue, (0L..180L step 30).map { SeriesPoint(it, 2.0 + it / 90.0) }),
    )

    /** Shows one chart whose `loading` flag and series the test flips through the returned states. */
    private class Handle(val loading: MutableState<Boolean>, val series: MutableState<List<ChartSeries>>)

    private fun SkikoComposeUiTest.show(
        startLoading: Boolean,
        series: List<ChartSeries>,
        note: String? = null,
        reserveLegend: Boolean = false,
        headerValue: String? = null,
        poweredOn: Boolean = false,
    ): Handle {
        val handle = Handle(mutableStateOf(startLoading), mutableStateOf(series))
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(CHART_W.dp, CHART_H.dp).background(Color.White)) {
                    CompositionLocalProvider(LocalChartAreaPoweredOn provides poweredOn) {
                        TimeSeriesChart(
                            title = "CPU",
                            series = handle.series.value,
                            window = window,
                            formatValue = { "v${it.toInt()}" },
                            modifier = Modifier.fillMaxWidth().testTag(CHART_TAG),
                            note = note,
                            loading = handle.loading.value,
                            reserveLegend = reserveLegend,
                            headerValue = headerValue,
                        )
                    }
                }
            }
        }
        waitForIdle()
        return handle
    }

    private fun SkikoComposeUiTest.chartHeight() = onNodeWithTag(CHART_TAG).getBoundsInRoot().let { it.bottom - it.top }

    private fun SkikoComposeUiTest.image(): PixelMap = onRoot().captureToImage().toPixelMap()

    private fun PixelMap.sameAs(other: PixelMap) = width == other.width && height == other.height && buffer.contentEquals(other.buffer)

    private fun runChart(block: SkikoComposeUiTest.() -> Unit) = runSkikoComposeUiTest(size = Size(CHART_W.toFloat(), CHART_H.toFloat()), density = Density(1f), block = block)

    private fun PixelMap.redCount(): Int {
        var count = 0
        for (x in 0 until width) {
            for (y in 0 until height) {
                val c = this[x, y]
                if (c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f) count++
            }
        }
        return count
    }

    // ── Heights ─────────────────────────────────────────────────────────────────

    @Test
    fun `a loading single-series chart is as tall as the loaded one`() = runChart {
        val handle = show(startLoading = true, series = emptyList())
        val skeleton = chartHeight()
        handle.series.value = loadedOne
        handle.loading.value = false
        waitForIdle()
        assertEquals(skeleton, chartHeight(), "the skeleton keeps the loaded chart's height")
    }

    @Test
    fun `a loading chart with a reserved legend is as tall as a loaded two-series chart`() = runChart {
        val handle = show(startLoading = true, series = emptyList(), reserveLegend = true)
        val skeleton = chartHeight()
        handle.series.value = loadedTwo
        handle.loading.value = false
        waitForIdle()
        onNodeWithText("alpha").assertExists()
        assertEquals(skeleton, chartHeight(), "the reserved legend line is as tall as the real legend")
    }

    @Test
    fun `reserving a legend makes the skeleton one line taller`() {
        var bare = 0f
        runChart {
            show(startLoading = true, series = emptyList(), reserveLegend = false)
            bare = chartHeight().value
        }
        runChart {
            show(startLoading = true, series = emptyList(), reserveLegend = true)
            assertTrue(chartHeight().value > bare, "reserving a legend adds a line")
        }
    }

    // ── Skeleton content ────────────────────────────────────────────────────────

    @Test
    fun `the skeleton tag exists while loading and is gone after`() = runChart {
        val handle = show(startLoading = true, series = emptyList())
        onNodeWithTag("chart-skeleton").assertExists()
        handle.series.value = loadedOne
        handle.loading.value = false
        waitForIdle()
        onNodeWithTag("chart-skeleton").assertDoesNotExist()
    }

    @Test
    fun `a skeleton draws no series and no yMax label`() = runChart {
        show(startLoading = true, series = loadedOne)
        assertEquals(0, image().redCount(), "no line while loading")
        onNodeWithText("v", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a note wins over loading`() = runChart {
        show(startLoading = true, series = emptyList(), note = "No access")
        onNodeWithText("No access").assertExists()
        onNodeWithTag("chart-skeleton").assertDoesNotExist()
    }

    @Test
    fun `a loading chart does not claim there is no data`() = runChart {
        show(startLoading = true, series = emptyList())
        onNodeWithText("No data in this range").assertDoesNotExist()
    }

    @Test
    fun `a chart first composed loaded has no skeleton`() = runChart {
        show(startLoading = false, series = loadedOne)
        onNodeWithTag("chart-skeleton").assertDoesNotExist()
        assertTrue(image().redCount() > 200)
    }

    // ── Default: loading -> loaded is the same picture as first composed loaded ──

    @Test
    fun `Default loading to loaded is pixel-identical to first composed loaded`() {
        var settled: PixelMap? = null
        runChart {
            show(startLoading = false, series = loadedOne)
            settled = image()
        }
        runChart {
            val handle = show(startLoading = true, series = emptyList())
            handle.series.value = loadedOne
            handle.loading.value = false
            waitForIdle()
            assertTrue(image().sameAs(settled!!), "Default has no reveal: the pixels are those of a chart that was never loading")
        }
    }

    @Test
    fun `Default two-series loading to loaded is pixel-identical to first composed loaded`() {
        var settled: PixelMap? = null
        runChart {
            show(startLoading = false, series = loadedTwo, reserveLegend = true)
            settled = image()
        }
        runChart {
            val handle = show(startLoading = true, series = emptyList(), reserveLegend = true)
            handle.series.value = loadedTwo
            handle.loading.value = false
            waitForIdle()
            assertTrue(image().sameAs(settled!!))
        }
    }

    @Test
    fun `Default does not clip a flat zero line to the plot box`() = runChart {
        // A series at value 0 sits on the plot's bottom edge, so its 2 px stroke is half inside and half
        // below the 96 dp box. The pre-reveal chart drew unclipped; the reveal's clipRect must not
        // trim that overhang (nor the round caps at x = 0 and x = width) outside Retro.
        val zero = listOf(ChartSeries("", Color.Red, (0L..180L step 30).map { SeriesPoint(it, 0.0) }))
        show(startLoading = false, series = zero)
        val img = image()

        // Rows holding a full-width run of series colour. The header's value text is red too, but it is
        // short and sits above the plot, so only the line itself reaches 100 pixels in a row.
        val lineRows = (0 until img.height).filter { y ->
            (0 until img.width).count { x ->
                val c = img[x, y]
                c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f
            } > 100
        }
        // At density 1 the stroke is 2 px thick and centred on the plot's bottom edge; clipped to the
        // plot box it would be 1 px thick.
        assertEquals(2, lineRows.size, "the 2 px stroke keeps both halves, rows were $lineRows")
        assertEquals(lineRows.first() + 1, lineRows.last(), "the two rows are adjacent")
    }

    // ── Header value ────────────────────────────────────────────────────────────

    @Test
    fun `a header value shows while loading and gives way to the series value, at the same height`() = runChart {
        val handle = show(startLoading = true, series = emptyList(), headerValue = "live-1")
        onNodeWithText("live-1").assertExists()
        val skeleton = chartHeight()
        handle.series.value = loadedSeven
        handle.loading.value = false
        waitForIdle()
        onNodeWithText("live-1").assertDoesNotExist()
        onNodeWithText("v7").assertExists()
        assertEquals(skeleton, chartHeight(), "the header slot is always emitted, so nothing moves")
    }

    @Test
    fun `a header value is ignored when not loading`() = runChart {
        val handle = show(startLoading = false, series = loadedSeven, headerValue = "ignored")
        onNodeWithText("ignored").assertDoesNotExist()
        onNodeWithText("v7").assertExists()
        handle.series.value = emptyList()
        waitForIdle()
        onNodeWithText("ignored").assertDoesNotExist()
    }

    @Test
    fun `a header value is ignored when a note wins over loading`() = runChart {
        show(startLoading = true, series = emptyList(), note = "No access", headerValue = "ignored")
        onNodeWithText("No access").assertExists()
        onNodeWithText("ignored").assertDoesNotExist()
    }

    // ── Retro: a chart lit by its area only traces ──────────────────────────────

    @Test
    fun `Retro chart born in a powered-on area only traces the first time, then plays the full reveal`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var settledImage: PixelMap? = null
        var fullAt50Image: PixelMap? = null
        var fullAt100Image: PixelMap? = null
        runChart {
            show(startLoading = false, series = loadedOne)
            settledImage = image()
        }
        val settled = settledImage!!
        // The control: a chart born outside a powered-on area, 50 ms and 100 ms after its data lands.
        runChart {
            mainClock.autoAdvance = false
            val handle = show(startLoading = true, series = emptyList(), poweredOn = false)
            mainClock.advanceTimeByFrame()
            handle.series.value = loadedOne
            handle.loading.value = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            mainClock.advanceTimeBy(50)
            waitForIdle()
            fullAt50Image = image()
            mainClock.advanceTimeBy(50)
            waitForIdle()
            fullAt100Image = image()
        }
        val fullAt50 = fullAt50Image!!
        val fullAt100 = fullAt100Image!!
        runChart {
            mainClock.autoAdvance = false
            val handle = show(startLoading = true, series = emptyList(), poweredOn = true)
            mainClock.advanceTimeByFrame()
            handle.series.value = loadedOne
            handle.loading.value = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            mainClock.advanceTimeBy(50)
            waitForIdle()
            val traced = image()
            assertFalse(traced.sameAs(fullAt50), "no CRT power-on frame: the area already lit the screen")
            // The power-on frame hides the whole plot, so only the header value is red there; the trace draws the line.
            assertTrue(traced.redCount() > fullAt50.redCount(), "the beam is already drawing the line")
            assertTrue(traced.redCount() < settled.redCount(), "the beam has not reached the right edge")

            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(image().sameAs(settled), "after the trace the chart is the settled one")

            // A later loading -> loaded flip of the same chart (a range change) plays the full reveal.
            handle.series.value = emptyList()
            handle.loading.value = true
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithTag("chart-skeleton").assertExists()
            handle.series.value = loadedOne
            handle.loading.value = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            // In the same 50 + 50 ms steps as the control: the clock's frame boundaries depend on the stepping.
            mainClock.advanceTimeBy(50)
            waitForIdle()
            mainClock.advanceTimeBy(50)
            waitForIdle()
            val second = image()
            assertFalse(second.sameAs(settled), "the second reveal is still running")
            assertTrue(second.sameAs(fullAt100), "the second reveal is the full power-on, as for a chart outside an area")
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(image().sameAs(settled))
        }
    }

    // ── Retro: reveal on data ───────────────────────────────────────────────────

    @Test
    fun `Retro plays the power-on and beam trace after loading and then settles`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        var settled: PixelMap? = null
        runChart {
            show(startLoading = false, series = loadedOne)
            settled = image()
        }
        runChart {
            mainClock.autoAdvance = false
            val handle = show(startLoading = true, series = emptyList())
            mainClock.advanceTimeByFrame()
            onNodeWithTag("chart-skeleton").assertExists()
            handle.series.value = loadedOne
            handle.loading.value = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithTag("chart-skeleton").assertDoesNotExist()

            mainClock.advanceTimeBy(100)
            waitForIdle()
            assertFalse(image().sameAs(settled!!), "100 ms in, the CRT is still igniting")

            // 500 ms is inside the beam trace (370 ms of power-on, then 300 ms of trace): the line is cut short.
            mainClock.advanceTimeBy(400)
            waitForIdle()
            val tracing = image()
            assertFalse(tracing.sameAs(settled!!), "500 ms in, the beam has not reached the right edge")
            assertTrue(tracing.redCount() in 1 until settled!!.redCount(), "part of the line is drawn")

            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(image().sameAs(settled!!), "after the reveal the chart is the settled one")
        }
    }

    @Test
    fun `Retro chart first composed loaded is settled at once`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        runChart {
            mainClock.autoAdvance = false
            show(startLoading = false, series = loadedOne)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val first = image()
            assertTrue(first.redCount() > 200, "the whole line is there on the first frame")
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(image().sameAs(first), "nothing animates afterwards")
        }
    }

    @Test
    fun `Retro skeleton pulses between two looks`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        runChart {
            mainClock.autoAdvance = false
            show(startLoading = true, series = emptyList())
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val seen = HashSet<Int>()
            repeat(60) {
                mainClock.advanceTimeByFrame()
                waitForIdle()
                seen += image().buffer.contentHashCode()
            }
            assertEquals(2, seen.size, "a Retro square wave: two looks")
        }
    }
}
