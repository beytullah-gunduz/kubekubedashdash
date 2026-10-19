package com.kubekubedashdash.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.kdStrokeCap

internal const val CHART_LOADING_TEXT = "Loading…"
internal const val CHART_ACQUIRING_TEXT = "Acquiring signal…" // Retro shows "ACQUIRING SIGNAL" + cursor
private const val BAND_SWEEP_MS = 1_400
private const val BAND_WIDTH_FRACTION = 0.3f
private const val RETRO_BEAM_STEP_MS = 160L
private const val RETRO_BEAM_STEPS = 10

/** Normalised y (0 = top) of the Default ghost curve, evenly spaced in x. */
private val GhostCurve = listOf(0.69f, 0.63f, 0.66f, 0.54f, 0.57f, 0.46f, 0.49f, 0.40f, 0.43f)

/**
 * The in-plot loading look, filling the plot Box it is called in. Default: the static grid, a faint
 * ghost curve, a translucent band sweeping left to right and a small spinner with [caption] centred.
 * Retro: the static grid, a 2 dp beam stepping left to right and [retroCaption] with its block cursor.
 * Every animated value is read in the draw phase; both clocks go through withInfiniteAnimationFrame*,
 * so UI tests stop them under autoAdvance and drive them under a manual clock.
 */
@Composable
internal fun BoxScope.ChartLoadingPlot(caption: String, retroCaption: String = caption) {
    val retro = ThemeManager.isRetro
    val bandLeft = if (retro) {
        null
    } else {
        rememberInfiniteTransition(label = "chartBand").animateFloat(
            initialValue = -BAND_WIDTH_FRACTION,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BAND_SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "left",
        )
    }
    val beamStep = if (retro) rememberFrameStep(RETRO_BEAM_STEP_MS, RETRO_BEAM_STEPS) else null
    Canvas(Modifier.fillMaxSize()) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(KdBorder.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
        }
        if (beamStep != null) {
            val x = size.width * (beamStep.intValue + 0.5f) / RETRO_BEAM_STEPS
            drawLine(KdPrimary.copy(alpha = 0.85f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        } else {
            val ghost = Path()
            GhostCurve.forEachIndexed { i, f ->
                val x = size.width * i / (GhostCurve.size - 1)
                val y = size.height * f
                if (i == 0) ghost.moveTo(x, y) else ghost.lineTo(x, y)
            }
            drawPath(ghost, KdTextSecondary.copy(alpha = 0.25f), style = Stroke(3.dp.toPx(), cap = kdStrokeCap, join = StrokeJoin.Round))
            // Clipped: a draw modifier is not, and the band starts 30 % left of the plot.
            clipRect {
                drawRect(
                    KdTextPrimary.copy(alpha = 0.06f),
                    topLeft = Offset(bandLeft!!.value * size.width, 0f),
                    size = Size(size.width * BAND_WIDTH_FRACTION, size.height),
                )
            }
        }
    }
    Row(
        Modifier.align(Alignment.Center),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (retro) {
            LoadingCaption(retroCaption, style = MaterialTheme.typography.labelSmall, color = KdPrimary)
        } else {
            BusyIndicator(Modifier.size(10.dp), color = KdTextSecondary, strokeWidth = 1.5.dp)
            LoadingCaption(caption, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
        }
    }
}
