package com.kubekubedashdash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.drawKdDot
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdStrokeCap
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** One line of a [TimeSeriesChart]; [name] is empty for a pod-level series. */
data class ChartSeries(val name: String, val color: Color, val points: List<SeriesPoint>)

/** A horizontal guide line (a resource request or limit) drawn across a [TimeSeriesChart]. */
data class ChartReference(val label: String, val value: Double, val dashed: Boolean)

internal const val MAX_CHART_SERIES = 6

private const val NO_DATA_TEXT = "No data in this range"

private val PlotHeight = 96.dp

private val ReadoutTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
private val AxisTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * A card with a time-series plot: one line per series on a shared time axis, a hover crosshair
 * with a readout, optional request/limit guide lines, and a legend when lines need telling apart.
 * [note] replaces the plot with a centred message. At most [MAX_CHART_SERIES] series are drawn;
 * the legend says how many were left out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeSeriesChart(
    title: String,
    series: List<ChartSeries>,
    window: QueryWindow,
    formatValue: (Double) -> String,
    modifier: Modifier = Modifier,
    references: List<ChartReference> = emptyList(),
    note: String? = null,
) {
    val drawn = remember(series) { series.take(MAX_CHART_SERIES) }
    val hidden = series.size - drawn.size
    val timestamps = remember(drawn) { drawn.flatMap { s -> s.points.map { it.epochSec } }.distinct().sorted() }
    val segments = remember(drawn, window.stepSec) { drawn.map { gapSegments(it.points, window.stepSec) } }
    val axisLabels = remember(window.startEpochSec, window.endEpochSec) {
        timeAxisLabels(window.startEpochSec, window.endEpochSec, ZoneId.systemDefault())
    }
    val seriesMax = drawn.maxOfOrNull { s -> s.points.maxOfOrNull { it.value } ?: 0.0 } ?: 0.0
    val yMax = chartYMax(seriesMax, references.maxOfOrNull { it.value } ?: 0.0)
    val message = note ?: if (drawn.all { it.points.isEmpty() }) NO_DATA_TEXT else null

    // The pointer's position, not a sample index: a refresh moves the window under a still pointer,
    // and the nearest sample is looked up again against the data on screen.
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    val hoverTimestamp = hoverFraction
        ?.let { nearestTimestampIndex(timestamps, window.startEpochSec, window.endEpochSec, it) }
        ?.let { timestamps[it] }

    val dim = KdTextSecondary.copy(alpha = 0.7f)

    Surface(modifier = modifier, shape = 8.dp.kdCorner, color = KdSurfaceVariant) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                val only = series.singleOrNull()
                val last = only?.points?.lastOrNull()
                if (only != null && last != null) {
                    Text(
                        formatValue(last.value),
                        style = MaterialTheme.typography.labelLarge,
                        color = only.color,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth().height(PlotHeight)) {
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
                    )
                } else {
                    Canvas(
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    hoverFraction = if (event.type == PointerEventType.Exit || size.width <= 0) {
                                        null
                                    } else {
                                        (event.changes.first().position.x / size.width).coerceIn(0f, 1f)
                                    }
                                }
                            }
                        },
                    ) {
                        val span = (window.endEpochSec - window.startEpochSec).coerceAtLeast(1L).toFloat()

                        fun xOf(epochSec: Long) = (epochSec - window.startEpochSec) / span * size.width

                        fun yOf(value: Double) = size.height * (1f - (value / yMax).toFloat().coerceIn(0f, 1f))

                        for (i in 1..3) {
                            val y = size.height * i / 4f
                            drawLine(KdBorder.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
                        }

                        references.forEach { ref ->
                            val y = yOf(ref.value)
                            if (ref.dashed) {
                                drawLine(
                                    KdTextSecondary.copy(alpha = 0.7f),
                                    Offset(0f, y),
                                    Offset(size.width, y),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                                )
                            } else {
                                drawLine(KdError.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                            }
                        }

                        val filled = series.size == 1 && !ThemeManager.isRetro
                        val lineStyle = Stroke(
                            width = 2.dp.toPx(),
                            cap = kdStrokeCap,
                            join = if (ThemeManager.isRetro) StrokeJoin.Miter else StrokeJoin.Round,
                        )
                        drawn.forEachIndexed { index, s ->
                            segments[index].forEach { segment ->
                                if (segment.size == 1) {
                                    // A lone sample between gaps has no line to carry it.
                                    drawKdDot(s.color, 2.dp.toPx(), Offset(xOf(segment[0].epochSec), yOf(segment[0].value)))
                                    return@forEach
                                }
                                val line = Path()
                                segment.forEachIndexed { i, p ->
                                    val x = xOf(p.epochSec)
                                    val y = yOf(p.value)
                                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                                }
                                if (filled) {
                                    val fill = Path()
                                    fill.moveTo(xOf(segment.first().epochSec), size.height)
                                    segment.forEach { p -> fill.lineTo(xOf(p.epochSec), yOf(p.value)) }
                                    fill.lineTo(xOf(segment.last().epochSec), size.height)
                                    fill.close()
                                    drawPath(
                                        fill,
                                        brush = Brush.verticalGradient(
                                            listOf(s.color.copy(alpha = 0.25f), s.color.copy(alpha = 0.02f)),
                                        ),
                                    )
                                }
                                drawPath(line, color = s.color, style = lineStyle)
                            }
                        }

                        if (hoverTimestamp != null) {
                            val x = xOf(hoverTimestamp)
                            drawLine(KdBorder, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                            drawn.forEach { s ->
                                s.points.firstOrNull { it.epochSec == hoverTimestamp }?.let { p ->
                                    drawKdDot(s.color, 3.dp.toPx(), Offset(x, yOf(p.value)))
                                }
                            }
                        }
                    }
                    Text(
                        formatValue(yMax),
                        style = MaterialTheme.typography.labelSmall,
                        color = dim,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                axisLabels.forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = dim)
                }
            }

            // minLines keeps the row's height when the readout is empty, so a hover never resizes the card.
            Text(
                readoutText(message, hoverTimestamp, drawn, formatValue, dim),
                style = MaterialTheme.typography.labelSmall,
                color = dim,
                maxLines = 2,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (drawn.size > 1 || references.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    drawn.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(s.color, 2.dp.kdCorner))
                            Spacer(Modifier.width(4.dp))
                            Text(s.name, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                        }
                    }
                    if (hidden > 0) {
                        Text("+$hidden more", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                    }
                    references.forEach { ref ->
                        val sample = if (ref.dashed) "- -" else "──"
                        Text(
                            "$sample ${ref.label} ${formatValue(ref.value)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }
            }
        }
    }
}

// Hovering: the time and each line's value there. At rest: a lone line's min and max.
private fun readoutText(
    message: String?,
    hoverTimestamp: Long?,
    drawn: List<ChartSeries>,
    formatValue: (Double) -> String,
    baseColor: Color,
): AnnotatedString = when {
    message != null -> AnnotatedString("")

    hoverTimestamp != null -> buildAnnotatedString {
        withStyle(SpanStyle(color = baseColor)) {
            append(ReadoutTime.format(Instant.ofEpochSecond(hoverTimestamp).atZone(ZoneId.systemDefault())))
        }
        drawn.forEach { s ->
            val point = s.points.firstOrNull { it.epochSec == hoverTimestamp } ?: return@forEach
            append("  ")
            withStyle(SpanStyle(color = s.color)) {
                append(if (s.name.isBlank()) formatValue(point.value) else "${s.name} ${formatValue(point.value)}")
            }
        }
    }

    drawn.size == 1 -> {
        val values = drawn.first().points.map { it.value }
        AnnotatedString("Min: ${formatValue(values.min())} · Max: ${formatValue(values.max())}")
    }

    else -> AnnotatedString("")
}

/** Larger of the two maxima plus 15 % headroom; 1.0 when both are ≤ 0. */
internal fun chartYMax(seriesMax: Double, referenceMax: Double): Double {
    val max = maxOf(seriesMax, referenceMax)
    return if (max > 0.0 && max.isFinite()) max * 1.15 else 1.0
}

/** Index of the timestamp closest to [fractionX] of the window; null for an empty list. */
internal fun nearestTimestampIndex(timestamps: List<Long>, startEpochSec: Long, endEpochSec: Long, fractionX: Float): Int? {
    if (timestamps.isEmpty()) return null
    val target = startEpochSec + (endEpochSec - startEpochSec).toDouble() * fractionX
    return timestamps.indices.minByOrNull { abs(timestamps[it] - target) }
}

/** Start, middle and end of the window as "HH:mm" in [zone]. */
internal fun timeAxisLabels(startEpochSec: Long, endEpochSec: Long, zone: ZoneId): List<String> = listOf(startEpochSec, startEpochSec + (endEpochSec - startEpochSec) / 2, endEpochSec)
    .map { AxisTime.format(Instant.ofEpochSecond(it).atZone(zone)) }

/** Splits where consecutive samples are more than 1.5 steps apart, so a gap is not drawn as a line. */
internal fun gapSegments(points: List<SeriesPoint>, stepSec: Long): List<List<SeriesPoint>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf<List<SeriesPoint>>()
    var current = mutableListOf(points.first())
    for (i in 1 until points.size) {
        if ((points[i].epochSec - points[i - 1].epochSec) * 2 > stepSec * 3) {
            segments += current
            current = mutableListOf()
        }
        current += points[i]
    }
    segments += current
    return segments
}
