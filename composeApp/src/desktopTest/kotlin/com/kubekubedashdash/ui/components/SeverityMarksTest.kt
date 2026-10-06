package com.kubekubedashdash.ui.components

import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The status colour of each usage level (its thresholds are pinned by UsageLevelsTest), and
 * the shape difference between the two "good" status badges (D18). The colours are compared
 * with the same theme tokens, so no theme setup is needed; the dots and rings are covered by
 * the live smoke.
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
    fun `each usage level takes its status colour`() {
        assertEquals(KdSuccess, UsageLevel.NORMAL.color())
        assertEquals(KdWarning, UsageLevel.WARNING.color())
        assertEquals(KdError, UsageLevel.CRITICAL.color())
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
