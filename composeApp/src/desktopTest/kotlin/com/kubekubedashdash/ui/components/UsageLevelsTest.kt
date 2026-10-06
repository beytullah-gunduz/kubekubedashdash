package com.kubekubedashdash.ui.components

import com.kubekubedashdash.ui.screens.cluster.viewmodel.NODE_PRESSURE_THRESHOLD
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared usage levels: where WARNING and CRITICAL start, and the "used / capacity" text. */
class UsageLevelsTest {

    @Test
    fun `levels switch at 80 and 90 percent`() {
        assertEquals(UsageLevel.NORMAL, usageLevel(0.79f))
        assertEquals(UsageLevel.WARNING, usageLevel(0.80f))
        assertEquals(UsageLevel.WARNING, usageLevel(0.89f))
        assertEquals(UsageLevel.CRITICAL, usageLevel(0.90f))
        assertEquals(UsageLevel.CRITICAL, usageLevel(1.2f))
    }

    @Test
    fun `the critical fraction is the node pressure threshold`() {
        assertEquals(NODE_PRESSURE_THRESHOLD, USAGE_CRITICAL_FRACTION)
    }

    @Test
    fun `formatUsagePair writes a shared unit once`() {
        assertEquals("11.0 / 15.5 GiB", formatUsagePair("11.0 GiB", "15.5 GiB"))
        assertEquals("125m / 4.0 cores", formatUsagePair("125m", "4.0 cores"))
        assertEquals("512 MiB / 15.5 GiB", formatUsagePair("512 MiB", "15.5 GiB"))
    }
}
