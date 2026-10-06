package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

private val HostSize = Size(300f, 40f)
private val Backdrop = Color.White
private val Content = Color(0xFFCC0000)

/**
 * [horizontalScrollFade] on a 300 px viewport over a white backdrop: solid red content whose
 * green channel reads 0 where it is opaque and climbs toward the backdrop's 1 where it is faded.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class HorizontalScrollFadeTest {

    private fun SkikoComposeUiTest.showStrip(contentWidth: Int): ScrollState {
        val state = ScrollState(0)
        setContent {
            Box(Modifier.fillMaxSize().background(Backdrop)) {
                Row(Modifier.fillMaxSize().horizontalScrollFade(state, 40.dp).horizontalScroll(state)) {
                    Box(Modifier.width(contentWidth.dp).fillMaxHeight().background(Content))
                }
            }
        }
        waitForIdle()
        return state
    }

    private fun SkikoComposeUiTest.pixels(): PixelMap = captureToImage().toPixelMap()

    private fun PixelMap.green(x: Int): Float = this[x, 20].green

    private fun assertOpaque(map: PixelMap, x: Int) = assertTrue(map.green(x) < 0.02f, "x=$x should be opaque content, green=${map.green(x)}")

    private fun assertFaded(map: PixelMap, x: Int) = assertTrue(map.green(x) > 0.9f, "x=$x should be faded to the backdrop, green=${map.green(x)}")

    @Test
    fun `content that fits is never faded`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        showStrip(contentWidth = 300)
        val map = pixels()
        assertOpaque(map, 0)
        assertOpaque(map, 150)
        assertOpaque(map, 299)
    }

    @Test
    fun `at the start only the end edge fades`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        showStrip(contentWidth = 900)
        val map = pixels()
        assertOpaque(map, 0)
        assertOpaque(map, 150)
        assertOpaque(map, 259)
        assertFaded(map, 299)
    }

    @Test
    fun `in the middle both edges fade`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val state = showStrip(contentWidth = 900)
        runOnIdle { state.dispatchRawDelta(300f) }
        waitForIdle()
        val map = pixels()
        assertFaded(map, 0)
        assertOpaque(map, 150)
        assertFaded(map, 299)
    }

    @Test
    fun `at the end only the start edge fades`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val state = showStrip(contentWidth = 900)
        runOnIdle { state.dispatchRawDelta(1000f) }
        waitForIdle()
        val map = pixels()
        assertFaded(map, 0)
        assertOpaque(map, 40)
        assertOpaque(map, 150)
        assertOpaque(map, 299)
    }

    @Test
    fun `an edge fade animates in rather than popping`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val state = showStrip(contentWidth = 900)
        mainClock.autoAdvance = false
        runOnIdle { state.dispatchRawDelta(300f) }
        mainClock.advanceTimeBy(SCROLL_FADE_MS / 2L)
        val mid = pixels().green(0)
        assertTrue(mid > 0.1f && mid < 0.9f, "start edge half-way through its fade should be part-faded, green=$mid")
        mainClock.advanceTimeBy(SCROLL_FADE_MS.toLong())
        assertFaded(pixels(), 0)
    }
}
