package com.kubekubedashdash.util

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatElapsedTest {

    @Test
    fun `formatElapsed uses the Age column's units`() {
        assertEquals("0s", formatElapsed(Duration.ZERO))
        assertEquals("45s", formatElapsed(Duration.ofSeconds(45)))
        assertEquals("12m", formatElapsed(Duration.ofSeconds(12 * 60 + 30)))
        assertEquals("1h5m", formatElapsed(Duration.ofMinutes(65)))
        assertEquals("2d3h", formatElapsed(Duration.ofHours(51)))
        assertEquals("2mo5d", formatElapsed(Duration.ofDays(65)))
        assertEquals("1y2mo", formatElapsed(Duration.ofDays(365 + 61)))
    }

    @Test
    fun `formatAge reads the same as formatElapsed over the time since`() {
        val now = Instant.parse("2026-01-10T12:00:00Z")
        for (seconds in listOf(0L, 59L, 3_599L, 3_600L, 90_061L, 40L * 86_400, 400L * 86_400)) {
            assertEquals(formatElapsed(Duration.ofSeconds(seconds)), formatAge(now.minusSeconds(seconds).toString(), now), "$seconds s")
        }
        assertEquals("0s", formatAge(now.plusSeconds(30).toString(), now))
    }
}
