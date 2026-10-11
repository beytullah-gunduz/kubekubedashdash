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
    fun `axis labels follow the zone`() {
        assertEquals(listOf("02:00", "02:30", "03:00"), timeAxisLabels(0, 3_600, ZoneOffset.ofHours(2)))
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
