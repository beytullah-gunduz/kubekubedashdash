package com.kubekubedashdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close
import com.kubekubedashdash.resources.mediation
import org.jetbrains.compose.resources.painterResource
import java.awt.MouseInfo
import kotlin.math.sqrt

private const val ALL_CLUSTERS_DRAG_THRESHOLD_PX = 30.0

/**
 * Tab-strip chip for the AllClusters (utility) tab. Mirrors the layout of [LogsChip] —
 * same height, same active underline, same drag-to-new-window gesture — with a circular
 * avatar (static ring + mediation icon) that visually echoes [ClusterChip].
 *
 * [onClose] is nullable: when null (≥2 cluster tabs in this window) the close × is hidden
 * and middle-click is a no-op, preventing accidental dismissal of the centralized view.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AllClustersChip(
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
    onDragMove: ((screenX: Int, screenY: Int) -> Unit)? = null,
    onDragRelease: ((screenX: Int, screenY: Int) -> Unit)? = null,
    onDragCancelled: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val background = if (isActive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val ringAndIconColor: Color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    val dragModifier = if (onDragRelease != null) {
        Modifier.pointerInput(Unit) {
            var startScreen: java.awt.Point? = null
            var lastScreen: java.awt.Point? = null
            var crossedThreshold = false
            detectDragGestures(
                onDragStart = {
                    crossedThreshold = false
                    startScreen = MouseInfo.getPointerInfo()?.location
                    lastScreen = startScreen
                },
                onDrag = { _, _ ->
                    val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                    lastScreen = now
                    if (!crossedThreshold) {
                        val start = startScreen ?: return@detectDragGestures
                        val dx = (now.x - start.x).toDouble()
                        val dy = (now.y - start.y).toDouble()
                        if (sqrt(dx * dx + dy * dy) > ALL_CLUSTERS_DRAG_THRESHOLD_PX) {
                            crossedThreshold = true
                        }
                    }
                    if (crossedThreshold) onDragMove?.invoke(now.x, now.y)
                },
                onDragEnd = {
                    val end = lastScreen
                    if (crossedThreshold && end != null) {
                        onDragRelease(end.x, end.y)
                    } else {
                        onDragCancelled?.invoke()
                    }
                    crossedThreshold = false
                    startScreen = null
                    lastScreen = null
                },
                onDragCancel = {
                    if (crossedThreshold) onDragCancelled?.invoke()
                    crossedThreshold = false
                    startScreen = null
                    lastScreen = null
                },
            )
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .drawBehind {
                if (isActive) {
                    val barHeight = 1.5.dp.toPx()
                    drawRect(
                        color = KdPrimary,
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight),
                    )
                }
            }
            .then(dragModifier)
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isTertiaryPressed && onClose != null) {
                    onClose()
                    event.changes.firstOrNull()?.consume()
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Static circular avatar: stroked ring + centered mediation icon
        Box(
            modifier = Modifier
                .size(24.dp)
                .drawBehind {
                    val strokePx = 1.5.dp.toPx()
                    val radius = (size.minDimension / 2f) - strokePx / 2f
                    drawCircle(
                        color = ringAndIconColor,
                        radius = radius,
                        style = Stroke(width = strokePx),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.mediation),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = ringAndIconColor,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "All Clusters",
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) KdTextPrimary else KdTextSecondary,
        )
        if (onClose != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.close),
                    contentDescription = "Close All Clusters tab",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
