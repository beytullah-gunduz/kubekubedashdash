package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.kdStrokeCap
import kotlin.math.floor
import kotlin.math.min

/** Milliseconds per step of the Retro ring: 10 steps a second, one lap of the 8-cell ring in 800 ms. */
internal const val BUSY_STEP_MILLIS = 100L

/** Steps per cycle. The 8-cell ring laps once per cycle, the 4-cell ring twice. */
internal const val BUSY_RING_STEPS = 8

/** The box M3 gives an unsized CircularProgressIndicator (CircularProgressIndicatorTokens.Size). */
private val BusyIndicatorDiameter = 40.dp

/** At or below this side the 3 x 3 ring's cells would be about 3 px at 1x, so the ring is 2 x 2. */
private val SmallRingMaxSide = 12.dp

/** (column, row) of each 2 x 2 cell, clockwise from the top-left. */
private val SmallRingOrder = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)

/** (column, row) of each 3 x 3 ring cell, clockwise from the top-left; the centre is never drawn. */
private val LargeRingOrder = listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1)

/**
 * The app's one indeterminate busy indicator.
 *
 * Default: Material 3's [CircularProgressIndicator], value-identical to the calls it replaced.
 * Retro: a ring of square cells around an empty centre (3 x 3; 2 x 2 in a box of 12 dp or less).
 * One cell is lit at a time and steps clockwise every [BUSY_STEP_MILLIS] with a short dimming
 * trail: stepped, never eased, like an old terminal.
 *
 * Every instance takes its step from the frame clock's absolute time, so all rings on screen move
 * in phase. The step is read only in the draw phase: a step redraws, never recomposes. The frame
 * loop goes through withInfiniteAnimationFrameMillis, so Compose UI tests stop it exactly as they
 * stop Material's own spinner. Both styles expose indeterminate progress semantics.
 *
 * [strokeWidth] applies to Default only; Retro cells size themselves from the box.
 */
@Composable
fun BusyIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    if (ThemeManager.isRetro) {
        RetroBusyRing(modifier, color, highContrast = ThemeManager.palette == ThemePalette.HIGH_CONTRAST)
    } else {
        CircularProgressIndicator(modifier, color, strokeWidth, strokeCap = kdStrokeCap) // kd-shape-exempt: Default-only delegate of BusyIndicator
    }
}

@Composable
private fun RetroBusyRing(modifier: Modifier, color: Color, highContrast: Boolean) {
    val step = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis { frameTimeMillis -> step.intValue = busyStep(frameTimeMillis) }
        }
    }
    Spacer(
        modifier
            .progressSemantics()
            .size(BusyIndicatorDiameter)
            .drawWithCache {
                val cells = busyRingCells(size.width, size.height, SmallRingMaxSide.toPx())
                onDrawBehind {
                    if (cells.isEmpty()) return@onDrawBehind
                    val head = step.intValue % cells.size
                    cells.forEachIndexed { index, cell ->
                        val alpha = busyCellAlpha(head, index, cells.size, highContrast)
                        if (alpha > 0f) {
                            drawRect(
                                color = color.copy(alpha = color.alpha * alpha),
                                topLeft = Offset(cell.left, cell.top),
                                size = Size(cell.side, cell.side),
                            )
                        }
                    }
                }
            },
    )
}

/** One square cell of the Retro ring, in px from the indicator's top-left; every value is a whole number. */
internal data class BusyCell(val left: Float, val top: Float, val side: Float)

/**
 * The ring's cells in clockwise order from the top-left, snapped to whole pixels so edges stay crisp
 * at 1x. A box whose shorter side is at most [smallRingMaxSidePx] gets the 2 x 2 ring, any other the
 * 3 x 3 ring without its centre. Centred in the box; empty when a cell would be under 1 px.
 */
internal fun busyRingCells(width: Float, height: Float, smallRingMaxSidePx: Float): List<BusyCell> {
    val side = floor(min(width, height))
    val small = side <= smallRingMaxSidePx
    val grid = if (small) 2 else 3
    val gap = maxOf(1f, floor(side / if (small) 10f else 14f))
    val cell = floor((side - (grid - 1) * gap) / grid)
    if (!(cell >= 1f)) return emptyList() // also NaN, from a NaN or infinite box
    val extent = grid * cell + (grid - 1) * gap
    val originX = floor((width - extent) / 2f)
    val originY = floor((height - extent) / 2f)
    return (if (small) SmallRingOrder else LargeRingOrder).map { (column, row) ->
        BusyCell(originX + column * (cell + gap), originY + row * (cell + gap), cell)
    }
}

/** The ring step for a frame time: advances every [BUSY_STEP_MILLIS] and wraps at [BUSY_RING_STEPS]. */
internal fun busyStep(frameTimeMillis: Long): Int = (frameTimeMillis / BUSY_STEP_MILLIS).mod(BUSY_RING_STEPS.toLong()).toInt()

/**
 * Opacity of ring cell [index] while cell [head] is lit, on a ring of [cellCount] cells. The head is
 * opaque; the cells just behind it (counter-clockwise) fade, two on the 8-cell ring and one on the
 * 4-cell ring; every other cell rests faintly. High Contrast keeps a brighter trail and drops the
 * resting cells.
 */
internal fun busyCellAlpha(head: Int, index: Int, cellCount: Int, highContrast: Boolean): Float {
    val behind = (head - index).mod(cellCount)
    return when {
        behind == 0 -> 1f
        behind == 1 -> if (highContrast) 0.75f else 0.55f
        behind == 2 && cellCount > 4 -> if (highContrast) 0.5f else 0.3f
        else -> if (highContrast) 0f else 0.14f
    }
}
