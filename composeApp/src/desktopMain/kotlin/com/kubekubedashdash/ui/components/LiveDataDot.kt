package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary

private const val PULSE_HALF_CYCLE_MS = 800

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveDataDot(isConnected: Boolean, errorMessage: String?, modifier: Modifier = Modifier) {
    val healthy = isConnected && errorMessage == null
    val dotColor = if (healthy) KdSuccess else KdError

    val dotAlpha = if (healthy) {
        val transition = rememberInfiniteTransition(label = "liveDataDot")
        val a by transition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_HALF_CYCLE_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "liveDataDotAlpha",
        )
        a
    } else {
        1.0f
    }

    val dot = @Composable {
        Box(
            modifier = modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(dotColor),
        )
    }

    if (!healthy) {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = KdSurface,
                    shadowElevation = 4.dp,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        text = errorMessage ?: "Disconnected",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextPrimary,
                    )
                }
            },
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
            content = dot,
        )
    } else {
        dot()
    }
}
