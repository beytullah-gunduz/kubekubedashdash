package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning

data class HeatmapData(
    val clusters: List<String>,
    val reasons: List<String>,
    val cells: Map<Pair<String, String>, Int>,
)

fun quantileColor(value: Int, allNonZeroValues: List<Int>): Color {
    if (value == 0) return Color.Transparent
    if (allNonZeroValues.isEmpty()) return Color.Transparent
    val sorted = allNonZeroValues.sorted()
    val size = sorted.size
    val q1 = sorted[(size * 0.2).toInt().coerceIn(0, size - 1)]
    val q2 = sorted[(size * 0.4).toInt().coerceIn(0, size - 1)]
    val q3 = sorted[(size * 0.6).toInt().coerceIn(0, size - 1)]
    val q4 = sorted[(size * 0.8).toInt().coerceIn(0, size - 1)]
    return when {
        value <= q1 -> KdSuccess.copy(alpha = 0.15f)
        value <= q2 -> KdWarning.copy(alpha = 0.20f)
        value <= q3 -> KdWarning.copy(alpha = 0.40f)
        value <= q4 -> KdError.copy(alpha = 0.50f)
        else -> KdError.copy(alpha = 0.65f)
    }
}
