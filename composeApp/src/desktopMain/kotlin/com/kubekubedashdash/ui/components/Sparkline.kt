package com.kubekubedashdash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary

/**
 * Tiny inline bar histogram used as a background overlay in grouped event rows.
 * Mirrors [UsageHistoryBar] Canvas conventions (gap between bars, bar width calculation).
 *
 * @param buckets list of per-bucket event counts (expected size 12).
 * @param modifier sizing is the caller's responsibility — typically Modifier.matchParentSize().
 * @param color bar fill color; defaults to [KdPrimary] at fixed 0.4α.
 */
@Composable
internal fun Sparkline(
    buckets: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = KdPrimary,
) {
    Canvas(modifier = modifier) {
        val max = buckets.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val barCount = buckets.size
        val gap = 1.dp.toPx()
        val totalGap = gap * (barCount - 1)
        val barWidth = (size.width - totalGap) / barCount
        buckets.forEachIndexed { i, value ->
            val barHeight = (value.toFloat() / max) * size.height
            val x = i * (barWidth + gap)
            drawRect(
                color = color.copy(alpha = 0.4f),
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}
