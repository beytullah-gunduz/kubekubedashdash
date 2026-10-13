package com.kubekubedashdash.ui.components

import com.kubekubedashdash.services.metricshistory.SeriesPoint
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure helpers behind [TimeSeriesChart]: scale, hover lookup, axis labels and gap splitting. */
class TimeSeriesChartMathTest {

    @Test
    fun `the y maximum is the larger of the two maxima plus fifteen percent`() {
        assertEquals(11.5, chartYMax(10.0, 0.0), 1e-9)
        assertEquals(4.6, chartYMax(2.0, 4.0), 1e-9)
    }

    @Test
    fun `a guide line joins the axis only within twice the data's peak`() {
        // A 500m limit over a 56m peak would flatten the line; a 50m request beside it stays.
        assertEquals(false, isOnScale(0.5, 0.056))
        assertEquals(true, isOnScale(0.05, 0.056))
        assertEquals(true, isOnScale(2.0, 1.0))
        assertEquals(false, isOnScale(2.01, 1.0))
    }

    @Test
    fun `with no positive data every guide line joins the axis`() {
        assertEquals(true, isOnScale(7.0, 0.0))
        assertEquals(true, isOnScale(7.0, Double.NaN))
    }

    @Test
    fun `the y maximum is one when there is nothing positive to scale to`() {
        assertEquals(1.0, chartYMax(0.0, 0.0), 0.0)
        assertEquals(1.0, chartYMax(-3.0, 0.0), 0.0)
        assertEquals(1.0, chartYMax(Double.NaN, 0.0), 0.0)
    }

    @Test
    fun `no timestamps means no nearest index`() {
        assertNull(nearestTimestampIndex(emptyList(), 100, 160, 0.5f))
    }

    @Test
    fun `the nearest timestamp is the one closest to the pointer`() {
        val timestamps = listOf(100L, 130L, 160L)
        assertEquals(0, nearestTimestampIndex(timestamps, 100, 160, 0.0f))
        // 0.6 of the window is t = 136, closer to 130 than to 160.
        assertEquals(1, nearestTimestampIndex(timestamps, 100, 160, 0.6f))
        assertEquals(2, nearestTimestampIndex(timestamps, 100, 160, 1.0f))
    }

    @Test
    fun `axis labels are the start, the middle and the end of the window`() {
        assertEquals(listOf("00:00", "00:30", "01:00"), timeAxisLabels(0, 3_600, ZoneOffset.UTC))
    }

    @Test
    fun `across midnight the start label names its day`() {
        // 1970-01-01 was a Thursday; a 24 h window ends at the same clock time the next day.
        assertEquals(listOf("Thu 00:00", "12:00", "00:00"), timeAxisLabels(0, 86_400, ZoneOffset.UTC))
        assertEquals(listOf("Thu 22:00", "00:00", "02:00"), timeAxisLabels(79_200, 93_600, ZoneOffset.UTC))
    }

    @Test
    fun `a window longer than a day names the day on every label`() {
        // 7 days from a Thursday midnight: the middle is Sunday noon and the end the next Thursday.
        assertEquals(listOf("Thu 00:00", "Sun 12:00", "Thu 00:00"), timeAxisLabels(0, 604_800, ZoneOffset.UTC))
        assertEquals(listOf("Thu 00:00", "Thu 12:00", "Fri 00:00"), timeAxisLabels(0, 86_401, ZoneOffset.UTC))
    }

    @Test
    fun `axis labels follow the zone`() {
        assertEquals(listOf("02:00", "02:30", "03:00"), timeAxisLabels(0, 3_600, ZoneOffset.ofHours(2)))
    }

    @Test
    fun `the hover readout names the day only in a window longer than a day`() {
        // 1970-01-01 was a Thursday.
        assertEquals("00:00:00", readoutTime(0, multiDay = false, zone = ZoneOffset.UTC))
        assertEquals("Thu 00:00:00", readoutTime(0, multiDay = true, zone = ZoneOffset.UTC))
        assertEquals("Sun 12:34:56", readoutTime(3 * 86_400L + 12 * 3_600 + 34 * 60 + 56, multiDay = true, zone = ZoneOffset.UTC))
        assertEquals("12:34:56", readoutTime(3 * 86_400L + 12 * 3_600 + 34 * 60 + 56, multiDay = false, zone = ZoneOffset.UTC))
    }

    @Test
    fun `the hover readout follows the zone, and its day with it`() {
        assertEquals("02:00:00", readoutTime(0, multiDay = false, zone = ZoneOffset.ofHours(2)))
        assertEquals("Thu 02:00:00", readoutTime(0, multiDay = true, zone = ZoneOffset.ofHours(2)))
        assertEquals("Wed 22:00:00", readoutTime(0, multiDay = true, zone = ZoneOffset.ofHours(-2)))
    }

    private fun points(vararg epochSecs: Long) = epochSecs.map { SeriesPoint(it, 1.0) }

    @Test
    fun `a gap of more than one and a half steps splits the line`() {
        val segments = gapSegments(points(0, 30, 60, 150, 180), stepSec = 30)
        assertEquals(listOf(listOf(0L, 30L, 60L), listOf(150L, 180L)), segments.map { s -> s.map { it.epochSec } })
    }

    @Test
    fun `a gap of exactly one and a half steps does not split`() {
        val segments = gapSegments(points(0, 45, 90), stepSec = 30)
        assertEquals(1, segments.size)
    }

    @Test
    fun `no points means no segments and a lone point is its own segment`() {
        assertEquals(emptyList(), gapSegments(emptyList(), stepSec = 30))
        assertEquals(listOf(listOf(7L)), gapSegments(points(7), stepSec = 30).map { s -> s.map { it.epochSec } })
    }
}
