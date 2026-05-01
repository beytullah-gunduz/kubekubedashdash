package com.kubekubedashdash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close
import kotlinx.coroutines.delay
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
private const val ARC_ROTATION_MS = 1100
private const val MIN_SPINNER_VISIBLE_MS: Long = 1100L

// Sweep / color / track-alpha all crossfade over this same window when
// flipping between the connecting and settled visuals, so the arc closes
// into a ring (or unwraps back into a partial arc) rather than snapping.
private const val ARC_TRANSITION_MS = 400

private val LABEL_MAX_WIDTH = 180.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ClusterChip(
    label: String,
    color: ClusterColor,
    initial: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    isDropTarget: Boolean = false,
    isConnected: Boolean? = null,
    isConnecting: Boolean = false,
    /**
     * When true, the active chip gets a vibrant bottom underline drawn
     * across its full width — the visual "this tab is selected" signal.
     * Only meaningful in a multi-chip strip; the single-chip-in-title-bar
     * caller leaves this off because there's nothing to compare against.
     */
    showActiveIndicator: Boolean = false,
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
                .drawBehind {
                    // Active-tab underline. Drawn after the rounded clip so the
                    // bar's lower-left and lower-right are softly tucked into
                    // the chip's corner radius, matching browser-tab look.
                    if (showActiveIndicator && isActive) {
                        val barHeight = 1.5.dp.toPx()
                        drawRect(
                            color = KdPrimary,
                            topLeft = Offset(0f, size.height - barHeight),
                            size = Size(size.width, barHeight),
                        )
                    }
                }
                .then(targetBorder)
                .then(dragModifier)
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isTertiaryPressed && onClose != null) {
                        onClose()
                        event.changes.firstOrNull()?.consume()
                    }
                }
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClusterAvatar(
                color = color.composeColor,
                initial = initial,
                isConnected = isConnected,
                isConnecting = isConnecting,
            )

            // Width-measured start truncation: pick the longest suffix that
            // still fits LABEL_MAX_WIDTH at the actual font/density, prefix
            // with `…`. Avoids char-count magic numbers (which were wrong on
            // macOS — `…` + 23 chars at labelMedium still overflowed 140 dp,
            // re-triggering Compose's *trailing* ellipsis on top of our
            // leading one). Compose Multiplatform 1.11's desktop renderer
            // silently treats `TextOverflow.StartEllipsis` as the default
            // trailing-ellipsis, so this is the workaround.
            val labelStyle = MaterialTheme.typography.labelMedium
            val measurer = rememberTextMeasurer()
            val maxLabelWidthPx = with(LocalDensity.current) { LABEL_MAX_WIDTH.roundToPx() }
            val displayLabel = remember(label, maxLabelWidthPx, labelStyle) {
                truncateStart(label, measurer, labelStyle, maxLabelWidthPx)
            }
            Text(
                text = displayLabel,
                style = labelStyle,
                maxLines = 1,
                // Safety net only — `displayLabel` is already measured to fit.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = LABEL_MAX_WIDTH),
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

/**
 * 20 dp avatar — colored circle with the cluster's initial — overlaid by
 * a connection-state ring:
 *
 * * `isConnecting = true` → 360° faint warning-tinted track + a 120°
 *   warning-colored arc rotating once per [ARC_ROTATION_MS]. The arc is
 *   held visible for at least one full revolution after [isConnecting]
 *   flips back to false (anti-flash hold) so a sub-second connect still
 *   reads as activity instead of a flicker.
 * * Steady state (after the hold expires) → full green ring when
 *   [isConnected] is true, full red ring when false, no ring when null.
 *
 * The avatar background and centered initial render the same in all
 * three states so the chip's identity never blanks during transitions.
 *
 * Pattern follows the `compose-rotating-arc-status-dot` skill: 120°
 * sweep (anything below ~100° reads as stationary), 1100 ms full
 * revolution with [LinearEasing], `MIN_SPINNER_VISIBLE_MS` matched to
 * one revolution.
 */
@Composable
private fun ClusterAvatar(
    color: Color,
    initial: String,
    isConnected: Boolean?,
    isConnecting: Boolean,
) {
    var enteredConnectingAt by remember {
        mutableStateOf<Long?>(if (isConnecting) System.currentTimeMillis() else null)
    }
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            enteredConnectingAt = System.currentTimeMillis()
        } else {
            val started = enteredConnectingAt ?: return@LaunchedEffect
            val elapsed = System.currentTimeMillis() - started
            val remaining = (MIN_SPINNER_VISIBLE_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0) delay(remaining)
            enteredConnectingAt = null
        }
    }
    val showSpinner = isConnecting || enteredConnectingAt != null

    // Freeze the last rotating-arc angle once the spinner stops, so the
    // arc keeps the same start angle while its sweep grows from 120° to
    // 360°. Without this the partial arc would snap to a fixed angle
    // (e.g., 12 o'clock) the moment the rotation animation tears down,
    // before the sweep growth had time to hide the discontinuity.
    var lastRotation by remember { mutableStateOf(0f) }
    val arcRotation = if (showSpinner) {
        val transition = rememberInfiniteTransition(label = "clusterArc")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = ARC_ROTATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "clusterArcAngle",
        )
        SideEffect { lastRotation = rotation }
        rotation
    } else {
        lastRotation
    }

    // Animated targets so the connecting → settled (and reverse) transition
    // feels like the arc closing into a ring, not a sudden swap.
    val targetSweep = if (showSpinner) 120f else 360f
    val targetArcColor = when {
        showSpinner -> KdWarning
        isConnected == true -> KdSuccess
        isConnected == false -> KdError
        else -> KdWarning // never displayed (gated below by isConnected != null)
    }
    val targetTrackAlpha = if (showSpinner) 0.25f else 0f
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = ARC_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "clusterArcSweep",
    )
    val animatedArcColor by animateColorAsState(
        targetValue = targetArcColor,
        animationSpec = tween(durationMillis = ARC_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "clusterArcColor",
    )
    val animatedTrackAlpha by animateFloatAsState(
        targetValue = targetTrackAlpha,
        animationSpec = tween(durationMillis = ARC_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "clusterArcTrackAlpha",
    )

    Box(
        modifier = Modifier.size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Cluster-color fill, intentionally smaller than the avatar's outer
        // 20 dp footprint so there's a ~0.5 dp transparent gap between the
        // fill's edge and the ring's inner edge. Without it, a green-tinted
        // cluster color blends into the connected-green ring (and likewise
        // for any cluster whose color happens to match a state color),
        // erasing the ring visually.
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = initial,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        if (showSpinner || isConnected != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val stroke = 2.5.dp.toPx()
                val pad = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(pad, pad)
                if (animatedTrackAlpha > 0.001f) {
                    drawArc(
                        color = KdWarning.copy(alpha = animatedTrackAlpha),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                }
                drawArc(
                    color = animatedArcColor,
                    startAngle = arcRotation - 90f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * Width-measured start-side truncation. If [label] is wider than
 * [maxWidthPx] at [style], returns `"…" + label.takeLast(n)` where `n` is
 * the largest tail length whose rendered width still fits. Otherwise
 * returns [label] unchanged. Uses binary search over `n` so a long ARN
 * costs ~`log2(label.length)` measurements (~10 for a 1k-char string).
 */
private fun truncateStart(
    label: String,
    measurer: TextMeasurer,
    style: TextStyle,
    maxWidthPx: Int,
): String {
    if (label.isEmpty()) return label
    val fullWidth = measurer.measure(label, style, maxLines = 1).size.width
    if (fullWidth <= maxWidthPx) return label
    var lo = 0
    var hi = label.length
    var best = "…"
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        val candidate = "…" + label.takeLast(mid)
        val candidateWidth = measurer.measure(candidate, style, maxLines = 1).size.width
        if (candidateWidth <= maxWidthPx) {
            best = candidate
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return best
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
