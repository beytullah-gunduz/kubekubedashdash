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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close
import com.kubekubedashdash.resources.terminal_filled
import org.jetbrains.compose.resources.painterResource
import java.awt.MouseInfo
import kotlin.math.sqrt

private const val TERMINAL_DRAG_THRESHOLD_PX = 30.0

/**
 * Tab-strip chip for an open terminal. Mirrors [ClusterChip] — same height,
 * same active underline, same close ×, same drag-to-new-window gesture.
 * Difference: the terminal icon, the dynamic [label] (pod · container),
 * and a max-width cap so long pod names don't blow out the strip.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onDragMove: ((screenX: Int, screenY: Int) -> Unit)? = null,
    onDragRelease: ((screenX: Int, screenY: Int) -> Unit)? = null,
    onDragCancelled: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val background = if (isActive) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

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
                        if (sqrt(dx * dx + dy * dy) > TERMINAL_DRAG_THRESHOLD_PX) {
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
                if (event.buttons.isTertiaryPressed) {
                    onClose()
                    event.changes.firstOrNull()?.consume()
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(Res.drawable.terminal_filled),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = KdTextSecondary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = if (isActive) KdTextPrimary else KdTextSecondary,
            modifier = Modifier.widthIn(max = 200.dp),
        )
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
                contentDescription = "Close terminal tab",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
