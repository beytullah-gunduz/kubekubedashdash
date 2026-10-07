package com.kubekubedashdash.ui.crt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The "CRT refresh bar" setting (`crt_refresh_bar`). Drawn only in Retro with the CRT scanlines
 * switch on. OFF by default.
 */
enum class CrtRefreshBarMode(val label: String) { OFF("Off"), ONCE("Once"), ROLLING("Rolling") }

/** The Settings "Replay" button. Each bump plays one ONCE pass; never persisted. */
object CrtRefreshBarReplay {
    var requests by mutableIntStateOf(0)
        private set

    fun request() {
        requests++
    }
}

internal const val SWEEP_MS = 1_000
internal const val ROLL_MS = 8_000

/** Bar progress at rest: the trail has left the bottom edge, so nothing is drawn. */
internal const val BAR_REST = 1f

// Per-mode look. Dark adds phosphor light; light darkens instead, because additive light on the
// cream background clips straight to white (D4).
private const val TRAIL_ALPHA_DARK = 0.12f
private const val LINE_ALPHA_DARK = 0.28f
private const val TRAIL_ALPHA_LIGHT = 0.07f
private const val LINE_ALPHA_LIGHT = 0.14f

/** The bar's progress, hoisted in `App` next to the power-on so it outlives the root `Box`. */
@Composable
fun rememberCrtRefreshBar(): Animatable<Float, AnimationVector1D> = remember { Animatable(BAR_REST) }

/**
 * Runs the bar. [active] is "Retro with the scanlines switch on". A Unit composable on purpose:
 * it reads the window focus, so only this scope recomposes when focus changes, never `App`.
 *
 * - ONCE: one [SWEEP_MS] pass when each power-on finishes, when ONCE becomes active, and on
 *   Replay. Finite, so it keeps D7.
 * - ROLLING: an endless [ROLL_MS] pass while the window has focus, or always when
 *   [rollInBackground] is on. The one opt-in exception to D7; by default it never runs for an
 *   unfocused window left open all day. When it stops, the pass in
 *   flight leaves the screen instead of popping out. `infiniteRepeatable` drives frames through
 *   `withInfiniteAnimationFrameNanos`, so a test's `InfiniteAnimationPolicy` can stop it.
 */
@Composable
fun CrtRefreshBarDriver(
    bar: Animatable<Float, AnimationVector1D>,
    powerOn: Animatable<Float, AnimationVector1D>,
    active: Boolean,
    mode: CrtRefreshBarMode,
    rollInBackground: Boolean = false,
) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val once = active && mode == CrtRefreshBarMode.ONCE
    val rolling = active && mode == CrtRefreshBarMode.ROLLING && (focused || rollInBackground)
    val onceNow by rememberUpdatedState(once)
    val shownNow by rememberUpdatedState(active && mode != CrtRefreshBarMode.OFF)

    // ONCE after a power-on: window start, entering Retro, or a dark/light flip.
    LaunchedEffect(powerOn, bar) {
        var wasOn = powerOn.value >= 1f
        snapshotFlow { powerOn.value >= 1f }.collect { on ->
            if (on && !wasOn && onceNow) launch { bar.sweep() }
            wasOn = on
        }
    }

    // ONCE when picked, when Retro or scanlines switch on, and on Replay. Waits one frame so a
    // power-on started in this same frame has reset first; that power-on's end plays the pass.
    LaunchedEffect(once, CrtRefreshBarReplay.requests) {
        if (!once) return@LaunchedEffect
        withFrameNanos { }
        if (powerOn.value >= 1f) bar.sweep()
    }

    LaunchedEffect(rolling) {
        if (rolling) {
            if (bar.value < BAR_REST) bar.finishPass()
            bar.snapTo(0f)
            bar.animateTo(BAR_REST, infiniteRepeatable(tween(ROLL_MS, easing = LinearEasing)))
        } else if (bar.value < BAR_REST) {
            if (shownNow) bar.finishPass() else bar.snapTo(BAR_REST)
        }
    }
}

private suspend fun Animatable<Float, AnimationVector1D>.sweep() {
    snapTo(0f)
    animateTo(BAR_REST, tween(SWEEP_MS, easing = LinearEasing))
}

/** Lets a pass in flight leave the screen at rolling speed. */
private suspend fun Animatable<Float, AnimationVector1D>.finishPass() {
    val remainingMs = ((BAR_REST - value) * ROLL_MS).roundToInt().coerceAtLeast(1)
    animateTo(BAR_REST, tween(remainingMs, easing = LinearEasing))
}

/**
 * Draws the bar: a bright leading line with a soft phosphor trail above it, moving down. At
 * progress 0 the line sits on the top edge; at [BAR_REST] the trail has left the bottom edge.
 * Draw-only: [progress] and [enabled] are read inside the draw lambda, so a pass invalidates
 * draw and never recomposes (D6). At rest it draws `drawContent()` alone.
 *
 * Same blind spots as [crtScanlines]: dialogs, menus, tooltips and the terminal's `SwingPanel`
 * are not covered, so the bar does not cross them.
 */
fun Modifier.crtRefreshBar(
    progress: Animatable<Float, AnimationVector1D>,
    enabled: () -> Boolean,
): Modifier = drawWithCache {
    val dark = ThemeManager.isDarkTheme
    val trailHeight = max(48.dp.toPx(), size.height * 0.14f)
    val lineHeight = 2.dp.toPx()
    val ink = if (dark) crtLook(CrtScale.CUT, dark = true, KdPrimary).line else Color.Black
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val trailBrush = Brush.verticalGradient(
        0f to Color.Transparent,
        1f to ink.copy(alpha = if (dark) TRAIL_ALPHA_DARK else TRAIL_ALPHA_LIGHT),
        startY = 0f,
        endY = trailHeight,
    )
    val lineColor = ink.copy(alpha = if (dark) LINE_ALPHA_DARK else LINE_ALPHA_LIGHT)

    onDrawWithContent {
        drawContent()
        val p = progress.value
        if (p <= 0f || p >= BAR_REST || !enabled()) return@onDrawWithContent
        val lead = p * (size.height + trailHeight)
        translate(top = lead - trailHeight) {
            drawRect(trailBrush, size = Size(size.width, trailHeight), blendMode = blend)
        }
        drawRect(
            color = lineColor,
            topLeft = Offset(0f, lead - lineHeight),
            size = Size(size.width, lineHeight),
            blendMode = blend,
        )
    }
}
