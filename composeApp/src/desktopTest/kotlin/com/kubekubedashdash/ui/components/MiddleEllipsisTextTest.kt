package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [truncateMiddle] keeps both ends of a name that does not fit. Widths here are one unit per
 * character, the ellipsis included, so the expected head and tail are easy to count.
 */
class MiddleEllipsisTextTest {

    private val oneUnitPerChar: (String) -> Int = { it.length }

    @Test
    fun `a name that fits is left alone`() {
        assertEquals("example-pod-1", truncateMiddle("example-pod-1", 13, oneUnitPerChar))
    }

    @Test
    fun `an empty name stays empty`() {
        assertEquals("", truncateMiddle("", 0, oneUnitPerChar))
    }

    @Test
    fun `an even number of kept characters splits evenly around the ellipsis`() {
        // 7 units: the ellipsis plus 6 characters, 3 from each end.
        assertEquals("abc…hij", truncateMiddle("abcdefghij", 7, oneUnitPerChar))
    }

    @Test
    fun `an odd number of kept characters gives the extra one to the head`() {
        // 6 units: the ellipsis plus 5 characters, 3 from the head and 2 from the tail.
        assertEquals("abc…ij", truncateMiddle("abcdefghij", 6, oneUnitPerChar))
    }

    @Test
    fun `the distinguishing suffix of a long name survives`() {
        // 15 units: the ellipsis plus 14 characters, 7 from each end.
        assertEquals("example…exec-11", truncateMiddle("example-app-driver-exec-11", 15, oneUnitPerChar))
    }

    @Test
    fun `too little room leaves just the ellipsis`() {
        assertEquals("…", truncateMiddle("example-pod", 1, oneUnitPerChar))
        assertEquals("…", truncateMiddle("example-pod", 0, oneUnitPerChar))
    }

    @Test
    fun `the tail never starts inside a surrogate pair`() {
        // U+1F600 (a surrogate pair) at both ends: a 4-unit budget would keep one head
        // pair and half of the tail pair, so the half is dropped.
        assertEquals("😀…", truncateMiddle("😀xyz😀", 4, oneUnitPerChar))
    }

    @Test
    fun `neither end is cut inside a surrogate pair`() {
        // A 2-unit budget would keep half of the head pair and half of the tail pair.
        assertEquals("…", truncateMiddle("😀xyz😀", 2, oneUnitPerChar))
    }
}
