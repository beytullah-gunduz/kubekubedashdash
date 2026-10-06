package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.kdCorner

/**
 * The one horizontal usage bar: a [trackColor] track with a [color] fill [fraction] of its width from
 * the start edge. Both ends round to half of [height] in Default and are square in Retro ([kdCorner]);
 * the fill's far end is flat. A null or NaN [fraction] draws the track alone; the rest is clamped
 * to 0..1, and exposed to accessibility as progress. Give the width through [modifier] (a weight or a width).
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
