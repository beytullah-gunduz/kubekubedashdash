package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdAccent
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemePalette
import kotlin.math.floor

/** Cells in the Retro scanner row. */
internal const val SCANNER_CELLS = 10

/** Milliseconds per scanner step: the lit cell crosses the row in 720 ms and comes back. */
internal const val SCANNER_STEP_MILLIS = 80L

/** Steps per scanner cycle: there (heads 0..9) and back (heads 8..1). */
internal const val SCANNER_STEPS = 2 * (SCANNER_CELLS - 1)

/** Milliseconds the Retro caption cursor stays on, then off. */
internal const val CURSOR_BLINK_MILLIS = 500L

/**
 * A loader that fills a whole screen, list or tab.
 *
 * Default: [BusyIndicator] in a [ringSize] box, the ring these sites drew before. Retro: a
 * boot-screen scanner, a row of [SCANNER_CELLS] square cells where one lit cell sweeps right and
 * back, one cell every [SCANNER_STEP_MILLIS] (1.44 s a round trip), with the ring's dimming trail
 * behind it. Cells are a quarter of
 * [ringSize], so a bigger ring gives a bigger scanner. Like the ring, every scanner takes its step
 * from the frame clock's absolute time and redraws without recomposing; both styles expose
 * indeterminate progress semantics.
 *
 * [strokeWidth] applies to Default only.
 */
@Composable
fun BusyScanner(
    ringSize: Dp,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    if (ThemeManager.isRetro) {
        RetroScanner(ringSize, modifier, color, highContrast = ThemeManager.palette == ThemePalette.HIGH_CONTRAST)
    } else {
        BusyIndicator(modifier.size(ringSize), color, strokeWidth)
    }
}

@Composable
private fun RetroScanner(ringSize: Dp, modifier: Modifier, color: Color, highContrast: Boolean) {
    val step = rememberFrameStep(SCANNER_STEP_MILLIS, SCANNER_STEPS)
    val cell = ringSize / 4
    val gap = cell / 4
    Spacer(
        modifier
            .progressSemantics()
            .size(width = cell * SCANNER_CELLS + gap * (SCANNER_CELLS - 1), height = cell)
            .drawWithCache {
                val cells = scannerCells(size.width, size.height, SCANNER_CELLS)
                onDrawBehind {
                    val current = step.intValue
                    cells.forEachIndexed { index, c ->
                        val alpha = scannerCellAlpha(current, index, highContrast)
                        if (alpha > 0f) {
                            drawRect(
                                color = color.copy(alpha = color.alpha * alpha),
                                topLeft = Offset(c.left, c.top),
                                size = Size(c.side, c.side),
                            )
                        }
                    }
                }
            },
    )
}

/**
 * The scanner's [count] cells left to right, snapped to whole pixels and centred: as tall as the box
 * (narrower if the row would not fit its width), a gap of a quarter of the box height (at least 1 px). Empty when
 * a cell would be under 1 px or the box is not finite.
 */
internal fun scannerCells(width: Float, height: Float, count: Int): List<BusyCell> {
    var cell = floor(height)
    val gap = maxOf(1f, floor(cell / 4f))
    if (count * cell + (count - 1) * gap > width) cell = floor((width - (count - 1) * gap) / count)
    if (!width.isFinite() || !height.isFinite() || !(cell >= 1f)) return emptyList()
    val extent = count * cell + (count - 1) * gap
    val originX = floor((width - extent) / 2f)
    val top = floor((height - cell) / 2f)
    return List(count) { index -> BusyCell(originX + index * (cell + gap), top, cell) }
}

/** True while the scanner head moves right (steps 0..8), false on the way back (steps 9..17). */
internal fun scannerMovingRight(step: Int): Boolean = step.mod(SCANNER_STEPS) < SCANNER_CELLS - 1

/** The lit cell at [step]: 0, 1, … 9 on the way right, then 9, 8, … 1 on the way back. */
internal fun scannerHead(step: Int): Int {
    val s = step.mod(SCANNER_STEPS)
    return if (s < SCANNER_CELLS - 1) s else SCANNER_STEPS - s
}

/** Opacity of scanner cell [index] at [step]: the ring's trail ([trailAlpha]) behind the head's direction. */
internal fun scannerCellAlpha(step: Int, index: Int, highContrast: Boolean): Float {
    val head = scannerHead(step)
    val behind = if (scannerMovingRight(step)) head - index else index - head
    return trailAlpha(behind, trailLength = 2, highContrast)
}

/**
 * The caption under a [BusyScanner]. Default: [text] exactly as given. Retro: [text] without its
 * trailing ellipsis, in caps when [caps] (pass false when it carries a name, such as a context, whose
 * case matters), followed by a block cursor in [KdAccent] that blinks every [CURSOR_BLINK_MILLIS].
 * The cursor is drawn, not a glyph, and has no semantics, so screen readers hear the text alone.
 */
@Composable
fun LoadingCaption(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    caps: Boolean = true,
) {
    if (ThemeManager.isRetro) {
        val fontSize = style.fontSize.takeIf { it.isSp } ?: 14.sp
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            // Weighted, so a caption that wraps still leaves the cursor its width.
            Text(retroCaptionText(text, caps), Modifier.weight(1f, fill = false), style = style, color = color)
            BlinkingCursor(fontSize, KdAccent)
        }
    } else {
        Text(text, modifier = modifier, style = style, color = color)
    }
}

/** [text] as the Retro caption shows it: trailing `...` or `…` dropped, upper-cased when [caps]. */
internal fun retroCaptionText(text: String, caps: Boolean): String {
    val trimmed = text.trimEnd().removeSuffix("...").removeSuffix("…").trimEnd()
    return if (caps) trimmed.uppercase() else trimmed
}

/** A [fontSize]-tall block in [color], on for [CURSOR_BLINK_MILLIS] then off, from the shared frame clock. */
@Composable
internal fun BlinkingCursor(fontSize: TextUnit, color: Color) {
    val step = rememberFrameStep(CURSOR_BLINK_MILLIS, 2)
    val height = with(LocalDensity.current) { fontSize.toDp() }
    Spacer(
        Modifier
            .padding(start = height * 0.15f)
            .size(width = height * 0.6f, height = height)
            .drawBehind { if (step.intValue == 0) drawRect(color) },
    )
}
