package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the detail host's width precedence, clamps and the overlay breakpoint. */
class DetailHostMathTest {

    @Test
    fun `split at 1200 dp and above, overlay below`() {
        assertEquals(DetailLayout.Split, detailLayoutFor(1200f))
        assertEquals(DetailLayout.Split, detailLayoutFor(1600f))
        assertEquals(DetailLayout.Overlay, detailLayoutFor(1199f))
    }

    @Test
    fun `no memory and no drag means 42 percent of the content`() {
        assertEquals(672f, detailWidthFor(1600f, remembered = null, fallback = null))
    }

    @Test
    fun `memory beats the session fallback which beats the default`() {
        assertEquals(500f, detailWidthFor(1600f, remembered = 500f, fallback = 900f))
        assertEquals(900f, detailWidthFor(1600f, remembered = null, fallback = 900f))
    }

    @Test
    fun `the list keeps its minimum beside the handle and the detail keeps its floor`() {
        assertEquals(1275f, detailWidthFor(1600f, remembered = 5000f, fallback = null))
        assertEquals(320f, detailWidthFor(1600f, remembered = 10f, fallback = null))
        // 1200 dp content: 1200 - 320 - 5 = 875 max, so the list still gets 320 beside the handle.
        assertEquals(875f, detailWidthFor(1200f, remembered = 1100f, fallback = null))
    }

    @Test
    fun `the overlay sheet leaves the margin of list visible`() {
        assertEquals(420f, overlayWidthFor(1000f, remembered = null, fallback = null))
        assertEquals(936f, overlayWidthFor(1000f, remembered = 1500f, fallback = null))
        assertEquals(320f, overlayWidthFor(300f, remembered = null, fallback = null))
    }
}
