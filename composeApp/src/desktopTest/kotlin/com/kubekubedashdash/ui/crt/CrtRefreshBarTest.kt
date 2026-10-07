package com.kubekubedashdash.ui.crt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CRT refresh bar: [crtRefreshBar] draws nothing at rest or when disabled, and draws a band
 * that ends at the leading line; [CrtRefreshBarDriver] plays ONCE passes and runs ROLLING only
 * while the window has focus.
 *
 * The driver tests turn `mainClock.autoAdvance` off: with it on, the test's
 * `InfiniteAnimationPolicy` cancels every infinite animation (ui-test `ComposeUiTest.skiko.kt`),
 * so ROLLING would never run.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrtRefreshBarTest {

    // ── Drawing ──────────────────────────────────────────────────────────────

    private val boxColor = Color(0xFF336699)

    // 200 x 120 px at density 1, so the trail is its 48 dp floor: 48 px.
    private fun capture(progress: Float?, enabled: Boolean = true): IntArray {
        val bar = progress?.let { Animatable(it) }
        var buffer = IntArray(0)
        runSkikoComposeUiTest(size = Size(200f, 120f), density = Density(1f)) {
            setContent {
                Box(
                    modifier = Modifier
                        .size(200.dp, 120.dp)
                        .then(if (bar != null) Modifier.crtRefreshBar(bar) { enabled } else Modifier)
                        .background(boxColor),
                )
            }
            waitForIdle()
            buffer = captureToImage().toPixelMap().buffer
        }
        return buffer
    }

    private fun IntArray.row(y: Int) = copyOfRange(y * 200, (y + 1) * 200)

    @Test
    fun `at rest the bar is pixel-identical to no modifier`() {
        assertTrue(capture(progress = null).contentEquals(capture(progress = BAR_REST)))
    }

    @Test
    fun `at progress 0 the bar is pixel-identical to no modifier`() {
        assertTrue(capture(progress = null).contentEquals(capture(progress = 0f)))
    }

    @Test
    fun `disabled mid-pass, the bar is pixel-identical to no modifier`() {
        assertTrue(capture(progress = null).contentEquals(capture(progress = 0.5f, enabled = false)))
    }

    @Test
    fun `mid-pass, only the band above the leading line changes`() {
        val plain = capture(progress = null)
        val bar = capture(progress = 0.5f)
        // Leading line at 0.5 x (120 + 48) = 84 px; the trail spans 36..84 above it.
        assertTrue(plain.row(10).contentEquals(bar.row(10)), "above the trail must be untouched")
        assertTrue(plain.row(100).contentEquals(bar.row(100)), "below the leading line must be untouched")
        assertFalse(plain.row(83).contentEquals(bar.row(83)), "the leading line must draw")
        assertFalse(plain.row(70).contentEquals(bar.row(70)), "the trail must draw")
    }

    // ── Driver ───────────────────────────────────────────────────────────────

    private class FakeWindowInfo(focused: Boolean) : WindowInfo {
        override var isWindowFocused by mutableStateOf(focused)
    }

    private fun ComposeUiTest.drive(
        bar: Animatable<Float, AnimationVector1D>,
        mode: CrtRefreshBarMode,
        window: WindowInfo = FakeWindowInfo(focused = true),
        powerOn: Animatable<Float, AnimationVector1D> = Animatable(1f),
        active: Boolean = true,
    ) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                CrtRefreshBarDriver(bar, powerOn, active = active, mode = mode)
            }
        }
    }

    private fun Animatable<Float, AnimationVector1D>.assertMidPass() {
        assertTrue(value > 0f && value < BAR_REST, "expected a pass in flight, got $value")
        assertTrue(isRunning, "expected the bar to be animating")
    }

    private fun Animatable<Float, AnimationVector1D>.assertAtRest() {
        assertEquals(BAR_REST, value, "expected the bar at rest")
        assertFalse(isRunning, "expected no animation running")
    }

    @Test
    fun `ONCE plays a single pass when picked, then rests`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        drive(bar, CrtRefreshBarMode.ONCE)
        mainClock.advanceTimeBy(SWEEP_MS / 2L)
        bar.assertMidPass()
        mainClock.advanceTimeBy(SWEEP_MS.toLong())
        bar.assertAtRest()
        mainClock.advanceTimeBy(ROLL_MS.toLong())
        bar.assertAtRest()
    }

    @Test
    fun `Replay plays another ONCE pass`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        drive(bar, CrtRefreshBarMode.ONCE)
        mainClock.advanceTimeBy(SWEEP_MS * 2L)
        bar.assertAtRest()
        CrtRefreshBarReplay.request()
        mainClock.advanceTimeBy(SWEEP_MS / 2L)
        bar.assertMidPass()
    }

    @Test
    fun `ONCE waits for a power-on to finish before its pass`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        val powerOn = Animatable(0f)
        drive(bar, CrtRefreshBarMode.ONCE, powerOn = powerOn)
        mainClock.advanceTimeBy(SWEEP_MS / 2L)
        bar.assertAtRest()
        runBlocking { powerOn.snapTo(1f) }
        mainClock.advanceTimeBy(SWEEP_MS / 2L)
        bar.assertMidPass()
    }

    @Test
    fun `ROLLING keeps passing while the window has focus`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        drive(bar, CrtRefreshBarMode.ROLLING)
        mainClock.advanceTimeBy(ROLL_MS / 2L)
        bar.assertMidPass()
        val first = bar.value
        // A quarter pass into the second lap: it wrapped back to the top.
        mainClock.advanceTimeBy(ROLL_MS * 3L / 4L)
        bar.assertMidPass()
        assertTrue(bar.value < first, "expected a wrap to a new pass, got ${bar.value} after $first")
    }

    @Test
    fun `ROLLING never starts while the window is unfocused`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        drive(bar, CrtRefreshBarMode.ROLLING, window = FakeWindowInfo(focused = false))
        mainClock.advanceTimeBy(ROLL_MS.toLong())
        bar.assertAtRest()
    }

    @Test
    fun `with Keep rolling on, ROLLING keeps passing while the window is unfocused`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        mainClock.autoAdvance = false
        val window = FakeWindowInfo(focused = false)
        val powerOn = Animatable(1f)
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                CrtRefreshBarDriver(bar, powerOn, active = true, mode = CrtRefreshBarMode.ROLLING, rollInBackground = true)
            }
        }
        mainClock.advanceTimeBy(ROLL_MS / 2L)
        bar.assertMidPass()
        val first = bar.value
        mainClock.advanceTimeBy(ROLL_MS * 3L / 4L)
        bar.assertMidPass()
        assertTrue(bar.value < first, "expected a wrap to a new pass, got ${bar.value} after $first")
    }

    @Test
    fun `turning Keep rolling off in the background lets the pass finish, then stops`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        mainClock.autoAdvance = false
        val window = FakeWindowInfo(focused = false)
        val powerOn = Animatable(1f)
        var keepRolling by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                CrtRefreshBarDriver(bar, powerOn, active = true, mode = CrtRefreshBarMode.ROLLING, rollInBackground = keepRolling)
            }
        }
        mainClock.advanceTimeBy(ROLL_MS / 2L)
        bar.assertMidPass()
        keepRolling = false
        mainClock.advanceTimeBy(ROLL_MS / 4L)
        bar.assertMidPass()
        mainClock.advanceTimeBy(ROLL_MS / 2L)
        bar.assertAtRest()
        mainClock.advanceTimeBy(ROLL_MS.toLong())
        bar.assertAtRest()
    }

    @Test
    fun `losing focus lets the pass in flight finish, then ROLLING stops`() = runComposeUiTest {
        val bar = Animatable(BAR_REST)
        val window = FakeWindowInfo(focused = true)
        drive(bar, CrtRefreshBarMode.ROLLING, window = window)
        mainClock.advanceTimeBy(ROLL_MS / 2L)
        bar.assertMidPass()
        window.isWindowFocused = false
        mainClock.advanceTimeBy(ROLL_MS / 4L)
        bar.assertMidPass()
        mainClock.advanceTimeBy(ROLL_MS / 2L)
        bar.assertAtRest()
        mainClock.advanceTimeBy(ROLL_MS.toLong())
        bar.assertAtRest()
    }

    @Test
    fun `nothing plays outside Retro with scanlines on`() = runComposeUiTest {
        val rolling = Animatable(BAR_REST)
        val once = Animatable(BAR_REST)
        val powerOn = Animatable(1f)
        val window = FakeWindowInfo(focused = true)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                CrtRefreshBarDriver(rolling, powerOn, active = false, mode = CrtRefreshBarMode.ROLLING)
                CrtRefreshBarDriver(once, powerOn, active = false, mode = CrtRefreshBarMode.ONCE)
            }
        }
        mainClock.advanceTimeBy(ROLL_MS.toLong())
        rolling.assertAtRest()
        once.assertAtRest()
    }
}
