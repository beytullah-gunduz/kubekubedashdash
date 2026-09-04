package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure zoom-step arithmetic in UiScale.kt (WS2, D12):
 * [clampUiScale] always lands on a member of [UiScaleSteps], and
 * [stepUiScale] walks the list without falling off either end.
 */
class UiScaleTest {

    @Test
    fun `100 is a member of UiScaleSteps`() {
        // A default that is not a step would be unreachable from the keyboard.
        assertTrue(100 in UiScaleSteps)
    }

    @Test
    fun `clampUiScale snaps a value below the lowest step up to it`() {
        assertEquals(80, clampUiScale(50))
    }

    @Test
    fun `clampUiScale snaps a value above the highest step down to it`() {
        // 200 was a step until it was found to unmount the sidebar; a store
        // written by that build must land on the new ceiling, not be rejected.
        assertEquals(150, clampUiScale(300))
        assertEquals(150, clampUiScale(200))
    }

    @Test
    fun `clampUiScale snaps a value between two steps to the nearer one`() {
        assertEquals(100, clampUiScale(110))
        assertEquals(125, clampUiScale(120))
    }

    @Test
    fun `clampUiScale maps an unknown value to 100`() {
        assertEquals(100, clampUiScale(0))
        assertEquals(100, clampUiScale(-40))
    }

    @Test
    fun `clampUiScale is a no-op on an already-valid step`() {
        UiScaleSteps.forEach { step -> assertEquals(step, clampUiScale(step)) }
    }

    @Test
    fun `stepUiScale walks up through every step`() {
        assertEquals(100, stepUiScale(80, up = true))
        assertEquals(125, stepUiScale(100, up = true))
        assertEquals(150, stepUiScale(125, up = true))
    }

    @Test
    fun `stepUiScale walks down through every step`() {
        assertEquals(125, stepUiScale(150, up = false))
        assertEquals(100, stepUiScale(125, up = false))
        assertEquals(80, stepUiScale(100, up = false))
    }

    @Test
    fun `stepUiScale is a no-op at each end of the range`() {
        assertEquals(150, stepUiScale(150, up = true))
        assertEquals(80, stepUiScale(80, up = false))
    }
}
