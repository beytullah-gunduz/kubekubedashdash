package com.kubekubedashdash.ui.crt

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager

/**
 * Retro detail-pane open/close (D21) for a slot inside an `AnimatedVisibility` whose own enter
 * and exit are `None` in Retro. The collapse is a custom animation on the scope's own
 * transition, so the exit lasts exactly [CrtPanelTiming.CLOSE_MS]. A slot composed while
 * already visible starts at Visible, so a mode flip never replays. Draw-only (D6), finite (D7).
 * While closing, the slot swallows pointer input so a click never acts on the dying panel.
 * Default: returns [Modifier] unchanged.
 */
@Composable
internal fun AnimatedVisibilityScope.crtPanelFrame(): Modifier {
    if (!ThemeManager.isRetro) return Modifier
    val master = transition.animateFloat(
        transitionSpec = {
            if (EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible) {
                tween(CrtPanelTiming.OPEN_MS, easing = LinearEasing)
            } else {
                tween(CrtPanelTiming.CLOSE_MS, easing = LinearEasing)
            }
        },
        label = "crtPanel",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val look = crtLook(CrtScale.CUT, ThemeManager.isDarkTheme, KdPrimary)
    val frame = Modifier.drawWithContent {
        val entering = transition.targetState != EnterExitState.PostExit
        val f = crtFrameFor(master.value, entering = entering, igniteFraction = 0f)
        drawCrtFrame(f.ignite, f.open, f.glow, look)
    }
    return if (transition.targetState == EnterExitState.PostExit) frame.swallowPointerInput() else frame
}

// Consumed in the Initial pass at the slot, so no clickable inside it ever sees an unconsumed
// down, move, release or scroll.
private fun Modifier.swallowPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * D22: [value], or in Retro only, while [value] is null, the last non-null value it had, so a
 * closing pane still has content to collapse. Default always returns [value].
 */
@Composable
internal fun <T : Any> retroLatched(value: T?): T? {
    var last by remember { mutableStateOf<T?>(null) }
    SideEffect { if (value != null) last = value }
    return value ?: if (ThemeManager.isRetro) last else null
}
