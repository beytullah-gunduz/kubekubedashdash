package com.kubedash.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdInfo
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdSurface
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import java.awt.Cursor

@Composable
fun StatusBadge(status: String, color: Color = statusColor(status)) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
fun SummaryCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color = KdPrimary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = KdTextSecondary,
                )
            }
        }
    }
}

@Composable
fun PodStatusBar(running: Int, pending: Int, failed: Int, succeeded: Int) {
    val total = running + pending + failed + succeeded
    if (total == 0) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        if (running > 0) {
            Box(
                Modifier
                    .weight(running.toFloat() / total)
                    .fillMaxHeight()
                    .background(KdSuccess),
            )
        }
        if (pending > 0) {
            Box(
                Modifier
                    .weight(pending.toFloat() / total)
                    .fillMaxHeight()
                    .background(KdWarning),
            )
        }
        if (failed > 0) {
            Box(
                Modifier
                    .weight(failed.toFloat() / total)
                    .fillMaxHeight()
                    .background(KdError),
            )
        }
        if (succeeded > 0) {
            Box(
                Modifier
                    .weight(succeeded.toFloat() / total)
                    .fillMaxHeight()
                    .background(KdInfo.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
fun LabelChip(key: String, value: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = KdSurfaceVariant,
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(key, style = MaterialTheme.typography.labelSmall, color = KdPrimary)
            Text("=", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            Text(value, style = MaterialTheme.typography.labelSmall, color = KdTextPrimary)
        }
    }
}

fun statusColor(status: String): Color = when (status.lowercase()) {
    "running", "active", "ready", "bound", "available", "true" -> KdSuccess

    "pending", "waiting", "containercreating" -> KdWarning

    "failed", "error", "crashloopbackoff", "imagepullbackoff",
    "errimagepull", "oomkilled", "notready", "terminated",
    -> KdError

    "succeeded", "completed", "complete" -> KdInfo

    "terminating" -> KdWarning

    "unknown" -> KdTextSecondary

    else -> KdTextSecondary
}

@Composable
fun CircularUsageIndicator(
    fraction: Float,
    label: String,
    usedText: String,
    totalText: String,
    modifier: Modifier = Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 800),
    )
    val gaugeColor = when {
        clamped > 0.85f -> KdError
        clamped > 0.70f -> KdWarning
        else -> KdSuccess
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(96.dp),
        ) {
            Canvas(modifier = Modifier.size(84.dp)) {
                val strokeWidth = 7.dp.toPx()
                val pad = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(pad, pad)

                drawArc(
                    color = KdSurfaceVariant,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                if (animatedFraction > 0f) {
                    drawArc(
                        color = gaugeColor,
                        startAngle = -90f,
                        sweepAngle = (360f * animatedFraction).coerceAtLeast(6f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
            val percentText = when {
                clamped == 0f -> "0%"
                clamped < 0.1f -> "%.1f%%".format(clamped * 100)
                else -> "${(clamped * 100).toInt()}%"
            }
            Text(
                percentText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = KdTextPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = KdTextPrimary)
        Text(
            "$usedText / $totalText",
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
        )
    }
}

@Composable
fun ResizeHandle(onResize: (Float) -> Unit) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(5.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { if (!dragging) hovered = false }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp().value }
                    onResize(deltaDp)
                },
                onDragStarted = {
                    dragging = true
                    hovered = true
                },
                onDragStopped = {
                    dragging = false
                    hovered = false
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(5.dp)
                .background(if (hovered || dragging) KdPrimary.copy(alpha = 0.4f) else KdBorder),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(if (hovered || dragging) KdPrimary else Color.Transparent),
        )
    }
}
