package com.kubekubedashdash.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the CPU/memory quantity parsers (audit M1/M2). The previous
 * `toLongOrNull()` / integer-division implementations returned 0 for fractional
 * quantities and for sub-millicore nanocore values, which under-counted node and
 * pod capacity/usage.
 */
class KubeClientParsingTest {

    // ── parseCpuToMillis ────────────────────────────────────────────────────

    @Test
    fun `cpu blank and zero are zero`() {
        assertEquals(0, parseCpuToMillis(""))
        assertEquals(0, parseCpuToMillis("0"))
    }

    @Test
    fun `cpu millicores and whole cores`() {
        assertEquals(500, parseCpuToMillis("500m"))
        assertEquals(2000, parseCpuToMillis("2"))
    }

    @Test
    fun `cpu fractional cores no longer parse to zero (M1)`() {
        assertEquals(500, parseCpuToMillis("0.5"))
        assertEquals(1500, parseCpuToMillis("1.5"))
    }

    @Test
    fun `cpu sub-millicore nanocores are not truncated to zero (M2)`() {
        assertEquals(1, parseCpuToMillis("750000n")) // 0.75 millicore -> rounds to 1, was 0
        assertEquals(250, parseCpuToMillis("250000000n")) // 250 millicore
    }

    @Test
    fun `cpu microcores`() {
        assertEquals(2, parseCpuToMillis("2000u"))
    }

    // ── parseMemoryToBytes ──────────────────────────────────────────────────

    @Test
    fun `memory blank and zero are zero`() {
        assertEquals(0, parseMemoryToBytes(""))
        assertEquals(0, parseMemoryToBytes("0"))
    }

    @Test
    fun `memory binary suffixes`() {
        assertEquals(1024L * 1024 * 1024, parseMemoryToBytes("1Gi"))
        assertEquals(512L * 1024 * 1024, parseMemoryToBytes("512Mi"))
        assertEquals(1024L, parseMemoryToBytes("1Ki"))
    }

    @Test
    fun `memory fractional binary no longer parses to zero (M1)`() {
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), parseMemoryToBytes("1.5Gi"))
        assertEquals((1.5 * 1024 * 1024).toLong(), parseMemoryToBytes("1.5Mi"))
    }

    @Test
    fun `memory decimal suffixes`() {
        assertEquals(2_000_000_000L, parseMemoryToBytes("2G"))
        assertEquals(1_500_000_000L, parseMemoryToBytes("1.5G"))
        assertEquals(1_000L, parseMemoryToBytes("1k"))
    }

    @Test
    fun `memory plain bytes`() {
        assertEquals(100L, parseMemoryToBytes("100"))
    }
}
