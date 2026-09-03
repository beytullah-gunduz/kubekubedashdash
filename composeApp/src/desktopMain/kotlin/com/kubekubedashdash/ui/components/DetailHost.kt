package com.kubekubedashdash.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.screenshots.ScreenshotHooks

/** How the host lays out list and detail for the measured content width. */
enum class DetailLayout { Split, Overlay }

object DetailHostDefaults {
    /** Default detail share of the content width when nothing is remembered. */
    const val DEFAULT_FRACTION = 0.42f

    /** The list never drops below this in Split mode (the handle is reserved separately). */
    const val MIN_LIST_DP = 320f

    /** The detail never drops below this in any mode. */
    const val MIN_DETAIL_DP = 320f

    /** Below this content width the split becomes an overlay sheet. */
    const val OVERLAY_BELOW_DP = 1200f

    /** The overlay sheet always leaves this much of the list visible. */
    const val OVERLAY_MARGIN_DP = 64f

    /** Width of [ResizeHandle], reserved next to the list in Split mode. */
    const val HANDLE_DP = 5f
}

/** Split when the content area can hold a list and a detail; overlay otherwise. */
fun detailLayoutFor(contentWidthDp: Float): DetailLayout = if (contentWidthDp < DetailHostDefaults.OVERLAY_BELOW_DP) DetailLayout.Overlay else DetailLayout.Split

/**
 * Detail width for Split mode: the per-kind memory, else
 * [DetailHostDefaults.DEFAULT_FRACTION] of the content; clamped so the list
 * keeps [DetailHostDefaults.MIN_LIST_DP] beside the handle and the detail
 * keeps [DetailHostDefaults.MIN_DETAIL_DP] (the detail floor wins if both
 * cannot hold, which the overlay rule keeps unreachable).
 */
fun detailWidthFor(contentWidthDp: Float, remembered: Float?): Float {
    val wanted = remembered ?: (contentWidthDp * DetailHostDefaults.DEFAULT_FRACTION)
    val max = (contentWidthDp - DetailHostDefaults.MIN_LIST_DP - DetailHostDefaults.HANDLE_DP)
        .coerceAtLeast(DetailHostDefaults.MIN_DETAIL_DP)
    return wanted.coerceIn(DetailHostDefaults.MIN_DETAIL_DP, max)
}

/** Sheet width for Overlay mode: the same preference, but never covering the last [DetailHostDefaults.OVERLAY_MARGIN_DP] of the list. */
fun overlayWidthFor(contentWidthDp: Float, remembered: Float?): Float {
    val wanted = remembered ?: (contentWidthDp * DetailHostDefaults.DEFAULT_FRACTION)
    val max = (contentWidthDp - DetailHostDefaults.OVERLAY_MARGIN_DP).coerceAtLeast(DetailHostDefaults.MIN_DETAIL_DP)
    return wanted.coerceIn(DetailHostDefaults.MIN_DETAIL_DP, max)
}

/** What a panel header reads to draw its Expand / Restore control. */
data class DetailHostControls(val expanded: Boolean, val onToggleExpand: () -> Unit)

/**
 * Provided by [DetailHost] around its detail slot; null outside a host. A
 * plain (non-static) local on purpose: its value changes only when the
 * expanded flag flips, and only the header buttons read it — a static local
 * would recompose the whole panel on every host recomposition.
 */
val LocalDetailHostControls = compositionLocalOf<DetailHostControls?> { null }

/**
 * Presence alone stops fall-through: hit testing ends at the top-most subtree
 * that has a pointer-input node. Consume press, release and wheel so nothing
 * beneath reacts — but never a move: buttons inside check the Final pass of
 * every move for consumption while pressed and would cancel their tap.
 */
private fun Modifier.blockFallThrough(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val terminal = event.type == PointerEventType.Press ||
                event.type == PointerEventType.Release ||
                event.type == PointerEventType.Scroll
            if (terminal) event.changes.forEach { if (!it.isConsumed) it.consume() }
        }
    }
}

/**
 * The one detail-pane host. Lays [list] and [detail] out as a resizable split
 * when the content area is wide enough, as an overlay sheet over the list
 * when it is not, and as a full-area detail when [expanded]. The list is
 * always this host's first child (only its reserved end space changes), so
 * its scroll position, filters and selection bar survive every mode change;
 * the detail moves between slots through `movableContentOf`, so its tab
 * state survives too.
 *
 * Width: per-kind memory in [PreferenceRepository] (keyed by [kindKey]),
 * else 42 % of the content width. A drag runs against a live value and is
 * committed once on release: to the memory (when [kindKey] is non-null) and
 * to [onWidthChange].
 */
@Composable
fun DetailHost(
    visible: Boolean,
    kindKey: String?,
    onWidthChange: (Float) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val currentDetail by rememberUpdatedState(detail)
    val movableDetail = remember { movableContentOf { currentDetail() } }
    val currentExpandedChange by rememberUpdatedState(onExpandedChange)
    val controls = remember(expanded) {
        DetailHostControls(expanded = expanded, onToggleExpand = { currentExpandedChange(!expanded) })
    }
    val detailWithControls: @Composable () -> Unit = {
        CompositionLocalProvider(LocalDetailHostControls provides controls) { movableDetail() }
    }
    val widths by PreferenceRepository.detailPaneWidths.collectAsState()
    val ignoreMemory by ScreenshotHooks.ignorePaneWidthMemory.collectAsState()
    val remembered = if (ignoreMemory) null else kindKey?.let { widths[it] }

    // The view-model drops the expanded flag in the same step that closes the
    // pane, so the mode is frozen at the last visible value while the pane
    // fades out; otherwise the expanded layer would vanish without its exit.
    var lastExpanded by remember { mutableStateOf(false) }
    SideEffect { if (visible) lastExpanded = expanded }
    val expandedLayer = if (visible) expanded else lastExpanded

    BoxWithConstraints(modifier = modifier) {
        val contentWidth = maxWidth.value
        val split = !expandedLayer && detailLayoutFor(contentWidth) == DetailLayout.Split
        // The handle reports deltas faster than a preference write can round-trip
        // through recomposition, so the drag runs against a snapshot-state live
        // width READ INSIDE the callback (never captured). The state is keyed on
        // the settled width, which is constant for the whole drag (memory and
        // content width only change on release or on a window resize), so it
        // re-initialises exactly when it should and never a frame late.
        val settled = detailWidthFor(contentWidth, remembered)
        val live = remember(settled) { mutableFloatStateOf(settled) }
        val splitWidth = live.floatValue
        var dragging by remember { mutableStateOf(false) }

        // The list's reserved end space follows the pane's fade so list and
        // detail move together; it snaps while a drag is live.
        val listEndTarget = if (split && visible) (splitWidth + DetailHostDefaults.HANDLE_DP).dp else 0.dp
        val listEndPadding by animateDpAsState(
            targetValue = listEndTarget,
            animationSpec = if (dragging) snap() else tween(150),
            label = "detailHostListEnd",
        )
        Box(modifier = Modifier.fillMaxSize().padding(end = listEndPadding)) { list() }

        when {
            expandedLayer -> AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                Box(modifier = Modifier.fillMaxSize().blockFallThrough()) { detailWithControls() }
            }

            split -> AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    ResizeHandle(
                        onDragStopped = {
                            dragging = false
                            if (kindKey != null) PreferenceRepository.setDetailPaneWidth(kindKey, live.floatValue)
                            onWidthChange(live.floatValue)
                        },
                    ) { delta ->
                        dragging = true
                        live.floatValue = detailWidthFor(contentWidth, live.floatValue - delta)
                    }
                    Box(modifier = Modifier.width(splitWidth.dp).fillMaxHeight()) { detailWithControls() }
                }
            }

            else -> {
                val sheetWidth = overlayWidthFor(contentWidth, remembered)
                AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClose,
                            ),
                    )
                }
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Box(
                        modifier = Modifier
                            .width(sheetWidth.dp)
                            .fillMaxHeight()
                            .shadow(12.dp)
                            .background(KdSurface)
                            .blockFallThrough(),
                    ) { detailWithControls() }
                }
            }
        }
    }
}
