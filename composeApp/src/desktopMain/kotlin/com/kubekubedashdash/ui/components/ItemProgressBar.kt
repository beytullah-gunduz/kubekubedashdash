package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.kdStrokeCap
import kotlin.math.floor

/** Most segments the Retro bar draws; with more items, each segment stands for an equal share. */
internal const val MAX_ITEM_SEGMENTS = 40

/**
 * Progress through [total] items, [done] of them finished.
 *
 * Default: Material 3's LinearProgressIndicator at `done / total` (0 when [total] is not positive),
 * value-identical to the calls it replaced. Retro: a row of square-ended segments, 8 dp tall with
 * 2 dp gaps and edges on whole pixels, one per item up to [MAX_ITEM_SEGMENTS] items (beyond that,
 * or for an empty run, [MAX_ITEM_SEGMENTS] equal shares). Finished items are filled in [color];
 * pending ones are hollow [KdBorder] outlines, so the two differ by shape and not only by colour
 * (High Contrast gives them equal brightness). Segments fill from the start edge, mirrored in RTL;
 * the bar fills in steps because items finish one at a time. Both styles expose the same progress
 * semantics.
 */
@Composable
fun ItemProgressBar(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
) {
    val fraction = itemFraction(done, total)
    if (ThemeManager.isRetro) {
        val track = KdBorder
        Spacer(
            modifier
                .progressSemantics(fraction)
                .size(width = 240.dp, height = 8.dp)
                .drawWithCache {
                    val segments = itemSegments(size.width, total, gapPx = maxOf(1f, floor(2.dp.toPx())))
                    val lit = litSegments(done, total)
                    val outlinePx = maxOf(1f, floor(1.dp.toPx()))
                    onDrawBehind {
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        segments.forEachIndexed { index, (left, right) ->
                            val position = if (rtl) segments.size - 1 - index else index
                            if (position < lit) {
                                drawRect(color, Offset(left, 0f), Size(right - left, size.height))
                            } else {
                                drawPendingSegment(track, left, right, size.height, outlinePx)
                            }
                        }
                    }
                },
        )
    } else {
        LinearProgressIndicator(progress = { fraction }, modifier = modifier, color = color, strokeCap = kdStrokeCap)
    }
}

/** `done / total` coerced to 0..1; 0 when [total] is not positive. */
internal fun itemFraction(done: Int, total: Int): Float = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

/** Segments drawn for [total] items: one per item for 1..[MAX_ITEM_SEGMENTS], else [MAX_ITEM_SEGMENTS]. */
internal fun segmentCount(total: Int): Int = if (total in 1..MAX_ITEM_SEGMENTS) total else MAX_ITEM_SEGMENTS

/** Lit segments for [done] of [total]: one per finished item, or a floored share beyond [MAX_ITEM_SEGMENTS]. */
internal fun litSegments(done: Int, total: Int): Int = when {
    total <= 0 -> 0
    total <= MAX_ITEM_SEGMENTS -> done.coerceIn(0, total)
    else -> (done.coerceIn(0, total).toLong() * MAX_ITEM_SEGMENTS / total).toInt()
}

/**
 * Left and right edges, in px, of each segment across [width]: segment `i` spans
 * `[floor(i (W + g) / n), floor((i + 1) (W + g) / n) - g)`, so edges are whole pixels and every gap is
 * exactly `g`. The gaps go when they would leave a segment under 1 px; nothing is returned when a
 * segment would still be under 1 px or [width] is not finite.
 */
internal fun itemSegments(width: Float, total: Int, gapPx: Float): List<Pair<Float, Float>> {
    if (!width.isFinite() || width < 1f) return emptyList()
    val n = segmentCount(total)
    val gap = if ((width + gapPx) / n < gapPx + 1f) 0f else gapPx
    if ((width + gap) / n - gap < 1f) return emptyList()
    return List(n) { i -> floor(i * (width + gap) / n) to floor((i + 1) * (width + gap) / n) - gap }
}

/**
 * A pending segment from [left] to [right]: a [stroke]-px outline in [color] with a hollow centre,
 * or filled when it is too small (under 3 strokes either way) to leave a centre.
 */
private fun DrawScope.drawPendingSegment(color: Color, left: Float, right: Float, height: Float, stroke: Float) {
    val width = right - left
    if (width < 3 * stroke || height < 3 * stroke) {
        drawRect(color, Offset(left, 0f), Size(width, height))
        return
    }
    drawRect(color, Offset(left, 0f), Size(width, stroke))
    drawRect(color, Offset(left, height - stroke), Size(width, stroke))
    drawRect(color, Offset(left, stroke), Size(stroke, height - 2 * stroke))
    drawRect(color, Offset(right - stroke, stroke), Size(stroke, height - 2 * stroke))
}
