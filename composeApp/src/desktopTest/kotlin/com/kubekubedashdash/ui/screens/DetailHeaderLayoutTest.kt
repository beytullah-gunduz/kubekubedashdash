package com.kubekubedashdash.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the header's dp fit math — [verbButtonWidthDp], [overflowButtonWidthDp],
 * [headerVerbSpaceDp] and [fitHeaderVerbs] — independent of any live measurement,
 * density or Compose runtime (there is no Compose UI test infrastructure here).
 */
class DetailHeaderLayoutTest {

    @Test
    fun `a verb button widens for its label and for a trailing chevron, floored at the material minimum`() {
        assertEquals(95f, verbButtonWidthDp(60f, hasMenu = false))
        assertEquals(114f, verbButtonWidthDp(60f, hasMenu = true))
        assertEquals(58f, verbButtonWidthDp(20f, hasMenu = false))
    }

    @Test
    fun `the overflow button has no leading icon, just the chevron, floored at the material minimum`() {
        assertEquals(80f, overflowButtonWidthDp(45f))
        assertEquals(58f, overflowButtonWidthDp(5f))
    }

    @Test
    fun `available space reserves padding, title, icon buttons and dividers, and is unbounded on an unbounded header`() {
        assertEquals(558f, headerVerbSpaceDp(800f, hasExpand = true))
        assertEquals(586f, headerVerbSpaceDp(800f, hasExpand = false))
        assertEquals(0f, headerVerbSpaceDp(200f, hasExpand = true))
        assertEquals(Float.MAX_VALUE, headerVerbSpaceDp(Float.POSITIVE_INFINITY, hasExpand = true))
    }

    @Test
    fun `verbs fit while they and the overflow button clear the budget, then collapse from the end`() {
        assertEquals(3, fitHeaderVerbs(300f, listOf(100f, 100f, 100f), 80f))
        assertEquals(2, fitHeaderVerbs(299f, listOf(100f, 100f, 100f), 80f))
        assertEquals(0, fitHeaderVerbs(100f, listOf(100f, 100f), 80f))
        assertEquals(0, fitHeaderVerbs(500f, emptyList(), 80f))
    }
}
