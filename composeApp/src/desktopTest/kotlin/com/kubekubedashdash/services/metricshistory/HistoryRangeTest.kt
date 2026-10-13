package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.ShellEnvironment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The window and step each range menu entry asks for. */
class HistoryRangeTest {

    private var previousCloudClis: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun restoreCloudClis() {
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private val now = 1_000_000L

    @Test
    fun `15 minutes steps by 30 seconds`() {
        assertEquals(QueryWindow(999_090, 999_990, 30), HistoryRange.M15.window(now))
    }

    @Test
    fun `1 hour steps by 30 seconds`() {
        assertEquals(QueryWindow(996_390, 999_990, 30), HistoryRange.H1.window(now))
    }

    @Test
    fun `6 hours steps by 180 seconds`() {
        assertEquals(QueryWindow(978_300, 999_900, 180), HistoryRange.H6.window(now))
    }

    @Test
    fun `24 hours steps by 720 seconds`() {
        assertEquals(QueryWindow(912_960, 999_360, 720), HistoryRange.H24.window(now))
    }

    @Test
    fun `7 days steps by 5040 seconds`() {
        assertEquals(QueryWindow(393_120, 997_920, 5_040), HistoryRange.D7.window(now))
    }

    @Test
    fun `no range asks for more than 120 steps and the end sits on a step boundary`() {
        for (range in HistoryRange.entries) {
            val window = range.window(now)
            assertTrue((window.endEpochSec - window.startEpochSec) / window.stepSec <= 120, "$range asks for too many points: $window")
            assertEquals(0L, window.endEpochSec % window.stepSec, "$range does not end on a step boundary: $window")
            assertEquals(range.seconds, window.endEpochSec - window.startEpochSec, "$range spans the wrong number of seconds")
        }
    }

    @Test
    fun `the window is stable inside one step so a refresh with nothing new can be skipped`() {
        val first = HistoryRange.H1.window(1_000_000)
        val sameStep = HistoryRange.H1.window(1_000_000 + 9)
        val nextStep = HistoryRange.H1.window(1_000_000 + 30)
        assertEquals(first, sameStep)
        assertEquals(first.endEpochSec + 30, nextStep.endEpochSec)
    }

    // ── a window around a run ───────────────────────────────────────────────────

    @Test
    fun `a run of 90 seconds gets a minute of margin on each side at the finest step`() {
        assertEquals(QueryWindow(999_930, 1_000_170, 30), QueryWindow.spanning(1_000_000, 1_000_090))
    }

    @Test
    fun `a run of 2 hours gets a margin of a twentieth and a step of 90 seconds`() {
        assertEquals(QueryWindow(999_630, 1_007_640, 90), QueryWindow.spanning(1_000_000, 1_007_200))
    }

    @Test
    fun `a run of 3 days steps by 2400 seconds and both ends sit on a step boundary`() {
        val window = QueryWindow.spanning(1_000_000, 1_259_200)

        assertEquals(QueryWindow(986_400, 1_274_400, 2_400), window)
        assertEquals(120L, (window.endEpochSec - window.startEpochSec) / window.stepSec)
    }

    @Test
    fun `a run that ends before it starts is a run of no length, and equal inputs give equal windows`() {
        assertEquals(QueryWindow.spanning(1_000_090, 1_000_090), QueryWindow.spanning(1_000_090, 1_000_000))
        assertEquals(QueryWindow.spanning(1_000_000, 1_007_200), QueryWindow.spanning(1_000_000, 1_007_200))
    }

    @Test
    fun `a run window contains the run with a margin, aligns to its step and stays near 120 points`() {
        for (from in listOf(0L, 1L, 29L, 1_000_000L, 1_000_017L)) {
            for (length in listOf(0L, 1L, 59L, 60L, 90L, 600L, 3_599L, 7_200L, 86_400L, 259_200L, 604_800L, 2_000_000L)) {
                val window = QueryWindow.spanning(from, from + length)
                val label = "run $from + $length -> $window"

                assertTrue(window.stepSec >= HistoryRange.MIN_STEP_SEC && window.stepSec % HistoryRange.MIN_STEP_SEC == 0L, label)
                assertEquals(0L, window.startEpochSec % window.stepSec, label)
                assertEquals(0L, window.endEpochSec % window.stepSec, label)
                assertTrue(window.startEpochSec <= from - 60, label)
                assertTrue(window.endEpochSec >= from + length + 60, label)
                assertTrue((window.endEpochSec - window.startEpochSec) / window.stepSec <= HistoryRange.TARGET_POINTS + 2, label)
            }
        }
    }
}
