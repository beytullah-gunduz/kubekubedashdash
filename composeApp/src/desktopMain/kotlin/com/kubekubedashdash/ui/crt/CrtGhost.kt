package com.kubekubedashdash.ui.crt

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager

/**
 * Snapshot of one in-tree modal card, used to play its CRT collapse after the card has left
 * composition (D16). The card records itself into [layer] while it is shown (crtCardReveal);
 * on dismissal the host draws that recording through the power-off frame. Plain vars, not
 * snapshot state: they are written from the draw/layout phase and only read when an exit starts.
 */
class CrtGhost internal constructor(internal val layer: GraphicsLayer) {
    internal var cardTopLeftInRoot: Offset = Offset.Zero
    internal var cardSize: IntSize = IntSize.Zero
    internal var hasSnapshot: Boolean = false
}

@Composable
fun rememberCrtGhost(): CrtGhost {
    val layer = rememberGraphicsLayer()
    return remember(layer) { CrtGhost(layer) }
}

/**
 * Plays [ghost]'s power-off collapse for 140 ms after [visible] flips true → false, in Retro
 * only, and only if a snapshot was recorded. Draw-only: it adds no pointer, focus or key
 * handling, so the UI under it receives input from the first frame (D6). Place it directly
 * after the `if (visible) { Modal(…) }` it belongs to, inside the same root Box.
 */
@Composable
fun CrtGhostExit(visible: Boolean, ghost: CrtGhost, scrimAlpha: Float) {
    // Default never ghosts, so it must not pay for the transition either: without this early
    // return every Default modal close would still run a 140 ms frame loop nobody reads.
    // Switching to Retro while a modal is open is safe — the fresh transition starts settled.
    if (!ThemeManager.isRetro) return
    val transition = updateTransition(targetState = visible, label = "crtGhost")
    // true→false is the 140 ms collapse; false→true snaps, so re-opening animates nothing.
    val progress by transition.animateFloat(
        transitionSpec = { if (targetState) snap() else tween(140, easing = LinearEasing) },
        label = "crtGhostProgress",
    ) { if (it) 1f else 0f }
    // Same frame as the card leaving: currentState is still true while targetState is false.
    val exiting = transition.currentState && !transition.targetState
    if (!exiting || !ghost.hasSnapshot) return
    // Seeded at zero: onGloballyPositioned only reports after the first placement, so the first
    // ghost frame assumes this host sits at the root origin. True today (the App root Box fills
    // the window from (0, 0)); if an offset ancestor is ever added above it, the ghost would
    // sit misplaced for exactly one frame.
    var hostTopLeftInRoot by remember { mutableStateOf(Offset.Zero) }
    val look = crtLook(CrtScale.CARD, ThemeManager.isDarkTheme, KdPrimary)
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { hostTopLeftInRoot = it.positionInRoot() }.drawBehind {
            drawRect(Color.Black.copy(alpha = scrimAlpha * progress))
        },
    ) {
        Box(
            Modifier
                .offset { (ghost.cardTopLeftInRoot - hostTopLeftInRoot).round() }
                .size(with(density) { DpSize(ghost.cardSize.width.toDp(), ghost.cardSize.height.toDp()) })
                .drawBehind {
                    val f = crtFrameFor(progress, entering = false, igniteFraction = 0f)
                    drawCrtFrame(f.ignite, f.open, f.glow, look) { drawLayer(ghost.layer) }
                },
        )
    }
}
