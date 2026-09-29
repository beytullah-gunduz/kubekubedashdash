package com.kubekubedashdash.ui.components

import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The one usage-tier rule shared by every gauge, usage bar and quota row, and the
 * shape difference between the two "good" status badges (D18). Asserts no colours,
 * so it needs no theme setup; the dots and rings are covered by the live smoke.
 */
class SeverityMarksTest {

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    @Test
    fun `usageTier treats exactly 0_70 as OK and anything above as WARN`() {
        assertEquals(UsageTier.OK, usageTier(0.70f))
        assertEquals(UsageTier.WARN, usageTier(0.7001f))
    }

    @Test
    fun `usageTier treats exactly 0_85 as WARN and anything above as CRITICAL`() {
        assertEquals(UsageTier.WARN, usageTier(0.85f))
        assertEquals(UsageTier.CRITICAL, usageTier(0.8501f))
    }

    @Test
    fun `usageTier covers the ends of the range`() {
        assertEquals(UsageTier.OK, usageTier(0f))
        assertEquals(UsageTier.CRITICAL, usageTier(1f))
    }

    @Test
    fun `a finished status has a different glyph from a running one`() {
        assertNotEquals(statusVisual("succeeded").icon, statusVisual("running").icon)
    }

    @Test
    fun `succeeded completed and complete share one glyph`() {
        val succeeded = statusVisual("succeeded").icon
        assertEquals(succeeded, statusVisual("completed").icon)
        assertEquals(succeeded, statusVisual("complete").icon)
    }
}
