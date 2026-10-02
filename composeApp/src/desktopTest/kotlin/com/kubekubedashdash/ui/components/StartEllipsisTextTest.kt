package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [truncateStart] keeps the end of a name that does not fit. Widths here are one unit per
 * character, the ellipsis included, so the expected tails are easy to count.
 */
class StartEllipsisTextTest {

    private val oneUnitPerChar: (String) -> Int = { it.length }

    @Test
    fun `a name that fits is left alone`() {
        assertEquals("example-cluster", truncateStart("example-cluster", 15, oneUnitPerChar))
        assertEquals("", truncateStart("", 0, oneUnitPerChar))
    }

    @Test
    fun `a name that does not fit keeps the longest tail behind an ellipsis`() {
        // 10 units: the ellipsis plus the last 9 characters.
        assertEquals("…cluster-1", truncateStart("demo-example-cluster-1", 10, oneUnitPerChar))
    }

    @Test
    fun `a prefix shared by every context gives way to the distinguishing end`() {
        val name = "shared/provider/prefix/example-cluster-2"
        assertEquals("…example-cluster-2", truncateStart(name, 18, oneUnitPerChar))
    }

    @Test
    fun `too little room leaves just the ellipsis`() {
        assertEquals("…", truncateStart("example-cluster", 1, oneUnitPerChar))
        assertEquals("…", truncateStart("example-cluster", 0, oneUnitPerChar))
    }

    @Test
    fun `the tail never starts inside a surrogate pair`() {
        // "ab" + U+1F600 (a surrogate pair): a 2-unit budget would cut the pair in half.
        val name = "ab😀"
        assertEquals("…", truncateStart(name, 2, oneUnitPerChar))
        assertEquals("…😀", truncateStart(name, 3, oneUnitPerChar))
    }
}
