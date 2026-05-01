package com.kubekubedashdash.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close
import org.jetbrains.compose.resources.painterResource
import java.awt.MouseInfo
import kotlin.math.sqrt

/**
 * The always-visible cluster chip that anchors a session.
 *
 * Single chip lives inline in the title bar at N=1; chips lift into a
 * [WindowTabStrip] at N≥2. When [onDragRelease] is non-null, the chip becomes
 * a drag handle: once the cursor has moved [DRAG_THRESHOLD_PX] pixels in screen
 * space the gesture is "committed", subsequent moves stream through
 * [onDragMove] (so other windows can highlight their chip-drop zones), and the
 * mouse-up fires [onDragRelease] with the final screen position. The caller
 * decides between merge-into-another-window and tear-out-to-new-window from
 * that release point — see
 * [com.kubekubedashdash.services.WorkspaceManager.handleChipRelease].
 *
 * Tap and drag are independent gestures: a brief click still fires [onClick]
 * (the drag detector waits for Compose's touch slop before claiming events).
 * If the gesture finishes without ever reaching the drag threshold, the
 * chip's drag callbacks never fire — but [onDragCancelled] is invoked so the
 * caller can clear any stale drag-over highlight that might have leaked
 * through.
 */
private const val DRAG_THRESHOLD_PX = 30.0

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClusterChip(
    label: String,
    color: ClusterColor,
    initial: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    isDropTarget: Boolean = false,
    isConnected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onDragMove: ((screenX: Int, screenY: Int) -> Unit)? = null,
    onDragRelease: ((screenX: Int, screenY: Int) -> Unit)? = null,
    onDragCancelled: (() -> Unit)? = null,
) {
    val background = when {
        isDropTarget -> MaterialTheme.colorScheme.primaryContainer
        isActive -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }

    val dragModifier = if (onDragRelease != null) {
        // Stable key — re-keying mid-drag would discard `crossedThreshold` /
        // `lastScreen` and the drag would silently degrade. The captured
        // callbacks freeze at first composition, but they all funnel through
        // [com.kubekubedashdash.services.WorkspaceManager] singleton methods
        // and the session id captured by the caller's lambda is itself stable
        // for the chip's lifetime.
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
                        if (sqrt(dx * dx + dy * dy) > DRAG_THRESHOLD_PX) {
                            crossedThreshold = true
                        }
                    }
                    if (crossedThreshold) {
                        onDragMove?.invoke(now.x, now.y)
                    }
                },
                onDragEnd = {
                    val end = lastScreen
                    if (crossedThreshold && end != null) {
                        onDragRelease(end.x, end.y)
                    } else {
                        // Sub-threshold drag — treat as a click; just make sure
                        // any leaked drag-over highlight on other windows is
                        // cleared.
                        onDragCancelled?.invoke()
                    }
                    crossedThreshold = false
                    startScreen = null
                    lastScreen = null
                },
                onDragCancel = {
                    if (crossedThreshold) {
                        onDragCancelled?.invoke()
                    }
                    crossedThreshold = false
                    startScreen = null
                    lastScreen = null
                },
            )
        }
    } else {
        Modifier
    }

    val targetBorder = if (isDropTarget) {
        Modifier.border(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(6.dp),
        )
    } else {
        Modifier
    }

    TooltipArea(
        tooltip = { ChipTooltip(label) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(background)
                .then(targetBorder)
                .then(dragModifier)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(color.composeColor)
                    .let { base ->
                        // Connection-state ring: green when connected, red when
                        // disconnected, transparent when state is unknown (e.g.
                        // single-chip-in-title-bar callers that don't pass it).
                        // Drawn after clip so the stroke is contained inside
                        // the avatar's circle and doesn't bleed past it.
                        if (isConnected != null) {
                            base.border(
                                width = 2.5.dp,
                                color = if (isConnected) KdSuccess else KdError,
                                shape = CircleShape,
                            )
                        } else {
                            base
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
            )

            if (onClose != null) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(Res.drawable.close),
                        contentDescription = "Close $label",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipTooltip(label: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
        )
    }
}
