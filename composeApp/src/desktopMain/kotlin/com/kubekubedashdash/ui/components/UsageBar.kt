package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.kdCorner
import kotlin.math.floor
import kotlin.math.roundToInt

/** Cells in the Retro usage bar: each stands for 10 %. */
internal const val USAGE_CELLS = 10

/**
 * The one horizontal usage bar: a [trackColor] track with a [color] fill [fraction] of its width from
 * the start edge, both ends rounded to half of [height] ([kdCorner]) and the fill's far end flat. A
 * null or NaN [fraction] draws the track alone; the rest is clamped to 0..1, and exposed to
 * accessibility as progress. Give the width through [modifier] (a weight or a width).
 *
 * That is Default. In Retro the bar is [USAGE_CELLS] cells with 1 dp gaps on whole pixels, lit in
 * [color] for [litCells] of them and [trackColor] otherwise, filling from the start edge (mirrored in
 * RTL).
 */
@Composable
fun UsageBar(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    trackColor: Color = KdSurfaceVariant.copy(alpha = 0.4f),
) {
    val filled = fraction?.takeUnless { it.isNaN() }?.coerceIn(0f, 1f)
    if (ThemeManager.isRetro) {
        Spacer(
            modifier
                .then(if (filled != null) Modifier.progressSemantics(filled) else Modifier)
                .height(height)
                .drawWithCache {
                    val cells = itemSegments(size.width, USAGE_CELLS, gapPx = maxOf(1f, floor(1.dp.toPx())))
                    val lit = litCells(filled ?: 0f, USAGE_CELLS)
                    onDrawBehind {
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        cells.forEachIndexed { index, (left, right) ->
                            val position = if (rtl) cells.size - 1 - index else index
                            drawRect(
                                color = if (position < lit) color else trackColor,
                                topLeft = Offset(left, 0f),
                                size = Size(right - left, size.height),
                            )
                        }
                    }
                },
        )
    } else {
        Box(
            modifier
                .then(if (filled != null) Modifier.progressSemantics(filled) else Modifier)
                .height(height)
                .clip((height / 2).kdCorner)
                .background(trackColor),
        ) {
            if (filled != null) {
                Box(
                    Modifier
                        .fillMaxWidth(filled)
                        .fillMaxHeight()
                        .background(color),
                )
            }
        }
    }
}

/**
 * How many of [cells] to light for [fraction]: none for NaN or a value at or below 0; otherwise the
 * nearest whole cell of `min(fraction, 1) × cells`, at least one, so any non-zero value shows.
 */
internal fun litCells(fraction: Float, cells: Int): Int {
    if (fraction.isNaN() || fraction <= 0f) return 0
    return (fraction.coerceAtMost(1f) * cells).roundToInt().coerceIn(1, cells)
}
