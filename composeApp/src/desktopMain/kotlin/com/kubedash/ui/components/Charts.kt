package com.kubedash.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdInfo
import com.kubedash.KdSuccess
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning

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
fun UsageHistoryBar(
    history: List<Float>,
    modifier: Modifier = Modifier,
    maxEntries: Int = 20,
) {
    if (history.size < 2) return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(KdSurfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val entries = history.takeLast(maxEntries)
            val barCount = entries.size
            if (barCount == 0) return@Canvas

            val gapPx = 1.5.dp.toPx()
            val barMaxPx = 4.dp.toPx()
            val naturalBarWidth = ((size.width - (barCount - 1) * gapPx) / barCount).coerceAtLeast(1f)
            val barWidth = naturalBarWidth.coerceAtMost(barMaxPx)
            val totalBarsWidth = barCount * barWidth + (barCount - 1) * gapPx
            val startX = size.width - totalBarsWidth

            entries.forEachIndexed { index, fraction ->
                val clamped = fraction.coerceIn(0f, 1f)
                val barHeight = (clamped * size.height).coerceAtLeast(1.dp.toPx())
                val x = startX + index * (barWidth + gapPx)
                val y = size.height - barHeight

                val fade = 0.4f + 0.6f * (index.toFloat() / (barCount - 1).coerceAtLeast(1))
                val barColor = when {
                    clamped > 0.85f -> KdError
                    clamped > 0.70f -> KdWarning
                    else -> KdSuccess
                }

                drawRoundRect(
                    color = barColor.copy(alpha = fade),
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
        }
    }
}

@Composable
fun MetricsLineChart(
    values: List<Long>,
    label: String,
    currentText: String,
    formatValue: (Long) -> String,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = KdSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
                Text(
                    currentText,
                    style = MaterialTheme.typography.labelLarge,
                    color = lineColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(6.dp))

            if (values.size >= 2) {
                Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                    val maxVal = values.max()
                    val yMax = (maxVal * 1.15).toLong().coerceAtLeast(1)

                    val n = values.size
                    val stepX = size.width / (n - 1).coerceAtLeast(1)

                    for (i in 1..3) {
                        val y = size.height * i / 4f
                        drawLine(
                            KdBorder.copy(alpha = 0.4f),
                            Offset(0f, y),
                            Offset(size.width, y),
                            strokeWidth = 0.5f,
                        )
                    }

                    val linePath = Path()
                    val fillPath = Path()

                    values.forEachIndexed { idx, value ->
                        val x = idx * stepX
                        val frac = value.toFloat() / yMax
                        val y = size.height * (1f - frac)

                        if (idx == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, size.height)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }

                    fillPath.lineTo((n - 1) * stepX, size.height)
                    fillPath.close()

                    drawPath(
                        fillPath,
                        brush = Brush.verticalGradient(
                            listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0.02f)),
                        ),
                    )

                    drawPath(
                        linePath,
                        color = lineColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )

                    val lastX = (n - 1) * stepX
                    val lastFrac = values.last().toFloat() / yMax
                    val lastY = size.height * (1f - lastFrac)
                    drawCircle(lineColor, 3.dp.toPx(), Offset(lastX, lastY))
                    drawCircle(KdSurfaceVariant, 1.5.dp.toPx(), Offset(lastX, lastY))
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Min: ${formatValue(values.min())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary.copy(alpha = 0.7f),
                    )
                    Text(
                        "Max: ${formatValue(values.max())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary.copy(alpha = 0.7f),
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Collecting data\u2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                    )
                }
            }
        }
    }
}
