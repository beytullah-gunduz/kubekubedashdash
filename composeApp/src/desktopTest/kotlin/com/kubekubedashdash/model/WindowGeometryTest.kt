package com.kubekubedashdash.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowGeometryTest {

    @Test
    fun `positionless geometry is visible on an empty list and on any list`() {
        val geometry = WindowGeometry(widthDp = 800, heightDp = 600)
        assertTrue(geometry.isVisibleOn(emptyList()))
        assertTrue(geometry.isVisibleOn(listOf(ScreenBounds(0, 0, 1920, 1080))))
    }

    @Test
    fun `a window at 100,100 800x600 is visible on a 1920x1080 screen at origin`() {
        val geometry = WindowGeometry(x = 100, y = 100, widthDp = 800, heightDp = 600)
        assertTrue(geometry.isVisibleOn(listOf(ScreenBounds(0, 0, 1920, 1080))))
    }

    @Test
    fun `a window at 3000,100 is not visible on that screen but is visible with a second screen`() {
        val geometry = WindowGeometry(x = 3000, y = 100, widthDp = 800, heightDp = 600)
        val onlyFirstScreen = listOf(ScreenBounds(0, 0, 1920, 1080))
        assertFalse(geometry.isVisibleOn(onlyFirstScreen))

        val withSecondScreen = onlyFirstScreen + ScreenBounds(1920, 0, 1920, 1080)
        assertTrue(geometry.isVisibleOn(withSecondScreen))
    }

    @Test
    fun `a window whose title strip is above the screen is not visible but one that overlaps is`() {
        val screens = listOf(ScreenBounds(0, 0, 1920, 1080))
        val aboveScreen = WindowGeometry(x = 100, y = -100, widthDp = 800, heightDp = 600)
        assertFalse(aboveScreen.isVisibleOn(screens))

        val overlappingStrip = WindowGeometry(x = 100, y = -20, widthDp = 800, heightDp = 600)
        assertTrue(overlappingStrip.isVisibleOn(screens))
    }

    @Test
    fun `a window at -1500,100 is visible when a screen sits to the left`() {
        val geometry = WindowGeometry(x = -1500, y = 100, widthDp = 800, heightDp = 600)
        val screens = listOf(ScreenBounds(-1920, 0, 1920, 1080))
        assertTrue(geometry.isVisibleOn(screens))
    }
}
