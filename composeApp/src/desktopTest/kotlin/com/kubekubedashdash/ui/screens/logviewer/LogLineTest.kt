package com.kubekubedashdash.ui.screens.logviewer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [matchRanges] — the case-insensitive occurrence finder behind log
 * line highlighting (audit L2; the `highlight` parameter was previously dead).
 */
class LogLineTest {

    @Test
    fun `empty query matches nothing`() {
        assertEquals(emptyList(), matchRanges("hello world", ""))
    }

    @Test
    fun `query not present matches nothing`() {
        assertEquals(emptyList(), matchRanges("abc", "z"))
    }

    @Test
    fun `case-insensitive match returns the line-cased range`() {
        // "Error" matches query "error" at index 0..4.
        assertEquals(listOf(0 until 5), matchRanges("Error here", "error"))
    }

    @Test
    fun `multiple non-overlapping occurrences`() {
        assertEquals(listOf(0 until 5, 11 until 16), matchRanges("Error here error", "error"))
        assertEquals(listOf(1 until 2, 3 until 4), matchRanges("aXaXa", "x"))
    }
}
