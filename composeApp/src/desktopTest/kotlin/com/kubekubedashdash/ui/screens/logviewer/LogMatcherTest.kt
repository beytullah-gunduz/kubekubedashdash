package com.kubekubedashdash.ui.screens.logviewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LogMatcher] — the pure matching/highlighting core behind log
 * line filtering (log-toolbar plan, D8). The first four cases moved here from
 * the deleted LogLineTest, adapted from the old free line-highlighting
 * function to [LogMatcher.ranges].
 */
class LogMatcherTest {

    @Test
    fun `empty query has no ranges`() {
        assertEquals(emptyList(), LogMatcher("").ranges("hello world"))
    }

    @Test
    fun `query not present has no ranges`() {
        assertEquals(emptyList(), LogMatcher("z").ranges("abc"))
    }

    @Test
    fun `case-insensitive match returns the line-cased range`() {
        // "Error" matches query "error" at index 0..4.
        assertEquals(listOf(0 until 5), LogMatcher("error").ranges("Error here"))
    }

    @Test
    fun `multiple non-overlapping occurrences`() {
        assertEquals(listOf(0 until 5, 11 until 16), LogMatcher("error").ranges("Error here error"))
        assertEquals(listOf(1 until 2, 3 until 4), LogMatcher("x").ranges("aXaXa"))
    }

    @Test
    fun `Aa makes matching case-sensitive`() {
        val matcher = LogMatcher("ERROR", caseSensitive = true)
        assertFalse(matcher.matches("error here"))
        assertTrue(matcher.matches("an ERROR here"))
        assertEquals(emptyList(), matcher.ranges("error here"))
        assertEquals(listOf(3 until 8), matcher.ranges("an ERROR here"))
    }

    @Test
    fun `a regex query matches and reports ranges`() {
        val matcher = LogMatcher("err(or)?", regex = true)
        assertTrue(matcher.matches("an error here"))
        assertEquals(listOf(3 until 8), matcher.ranges("an error here"))
    }

    @Test
    fun `an invalid regex is invalid, matches everything and highlights nothing`() {
        val matcher = LogMatcher("[", regex = true)
        assertTrue(matcher.invalid)
        assertTrue(matcher.matches("anything at all"))
        assertEquals(emptyList(), matcher.ranges("anything at all"))
    }

    @Test
    fun `zero-width regex matches are dropped from ranges but still count as a match`() {
        val matcher = LogMatcher("a*", regex = true)
        assertEquals(listOf(1..1), matcher.ranges("bab"))
        assertTrue(matcher.matches("bab"))
    }

    @Test
    fun `a blank query is not active`() {
        assertFalse(LogMatcher("").active)
        assertFalse(LogMatcher("   ").active)
    }
}
