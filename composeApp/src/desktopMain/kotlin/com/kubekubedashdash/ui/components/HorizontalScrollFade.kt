package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How long an edge fade takes to appear or go when the content reaches or leaves that edge. */
internal const val SCROLL_FADE_MS = 150

/**
 * Fades the content out over [width] at each edge [scrollState] can still scroll toward, so a
 * strip that hides content says so on that side; an edge with nothing beyond it stays sharp. A
 * mask, not a painted gradient: the content itself turns transparent, so the fade matches
 * whatever lies behind it in every theme. Goes before `horizontalScroll` in the chain, so the
 * fade stays on the viewport edges.
 */
@Composable
fun Modifier.horizontalScrollFade(scrollState: ScrollState, width: Dp = 40.dp): Modifier {
    val start = animateFloatAsState(if (scrollState.canScrollBackward) 1f else 0f, tween(SCROLL_FADE_MS), label = "scrollFadeStart")
    val end = animateFloatAsState(if (scrollState.canScrollForward) 1f else 0f, tween(SCROLL_FADE_MS), label = "scrollFadeEnd")
    return this
        // DstIn needs a layer of its own, or it punches through to the window behind.
        .graphicsLayer {
            compositingStrategy = if (start.value > 0f || end.value > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            val fadePx = width.toPx().coerceAtMost(size.width / 2)
            val s = start.value
            if (s > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - s), Color.Black), startX = 0f, endX = fadePx),
                    size = Size(fadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            val e = end.value
            if (e > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - e)), startX = size.width - fadePx, endX = size.width),
                    topLeft = Offset(size.width - fadePx, 0f),
                    size = Size(fadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}
