package com.kubekubedashdash.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the header's dp fit math — [verbButtonWidthDp], [overflowButtonWidthDp],
 * [headerVerbSpaceDp], [fitHeaderVerbs] and [allocateFairWidths] — independent of any live measurement,
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
    fun `available space reserves padding, the status column and one divider, and is unbounded on an unbounded header`() {
        assertEquals(623f, headerVerbSpaceDp(800f))
        assertEquals(0f, headerVerbSpaceDp(150f))
        assertEquals(Float.MAX_VALUE, headerVerbSpaceDp(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `verbs fit while they and the overflow button clear the budget, then collapse from the end`() {
        assertEquals(3, fitHeaderVerbs(300f, listOf(100f, 100f, 100f), 80f))
        assertEquals(2, fitHeaderVerbs(299f, listOf(100f, 100f, 100f), 80f))
        assertEquals(0, fitHeaderVerbs(100f, listOf(100f, 100f), 80f))
        assertEquals(0, fitHeaderVerbs(500f, emptyList(), 80f))
    }

    @Test
    fun `an overflow-only verb reserves the overflow button even when every labelled verb would fit`() {
        assertEquals(2, fitHeaderVerbs(1000f, listOf(100f, 100f), 80f, forceOverflow = true))
        assertEquals(2, fitHeaderVerbs(300f, listOf(100f, 100f, 100f), 80f, forceOverflow = true))
        assertEquals(1, fitHeaderVerbs(279f, listOf(100f, 100f, 100f), 80f, forceOverflow = true))
        assertEquals(0, fitHeaderVerbs(500f, emptyList(), 80f, forceOverflow = true))
    }

    @Test
    fun `fair widths serve short items in full and split the rest`() {
        assertEquals(listOf(100, 300), allocateFairWidths(listOf(100, 300), 500))
        assertEquals(listOf(100, 200), allocateFairWidths(listOf(100, 300), 300))
        assertEquals(listOf(150, 100), allocateFairWidths(listOf(300, 100), 250))
        assertEquals(listOf(150, 150), allocateFairWidths(listOf(300, 300), 300))
        assertEquals(listOf(100, 100, 101), allocateFairWidths(listOf(200, 200, 200), 301))
        assertEquals(listOf(0, 0), allocateFairWidths(listOf(200, 200), 0))
        assertEquals(listOf(0, 0), allocateFairWidths(listOf(200, 200), -5))
        assertEquals(emptyList(), allocateFairWidths(emptyList(), 0))
    }
}
