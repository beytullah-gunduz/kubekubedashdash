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
}
