package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodUsage
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.ui.components.ActionTooltip
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.UsageLevel
import com.kubekubedashdash.ui.components.usageLevel
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import org.jetbrains.compose.resources.painterResource

/** The container closest to its own memory limit. */
internal data class MemoryPressure(val container: String, val usedBytes: Long, val limitBytes: Long) {
    val fraction: Float get() = usedBytes.toFloat() / limitBytes.toFloat()
}

/**
 * The container with the highest used/limit memory ratio, among those that
 * have both a sample and a limit; null when none has. Compared per container:
 * a container is OOM-killed at its own limit, so a pod total can hide one
 * about to die (500/512 MiB beside a 10/512 MiB sidecar is "50 %" of the pod).
 * Ties keep the first container in sample order.
 */
internal fun worstMemoryPressure(usage: PodUsage, resources: List<ContainerResources>): MemoryPressure? {
    val limits = resources.associate { it.name to it.memoryLimitBytes }
    return usage.containers
        .mapNotNull { c -> limits[c.name]?.let { limit -> MemoryPressure(c.name, c.memoryBytes, limit) } }
        .maxByOrNull { it.fraction }
}

internal fun memoryLevel(usage: PodUsage, resources: List<ContainerResources>): UsageLevel = worstMemoryPressure(usage, resources)?.let { usageLevel(it.fraction) } ?: UsageLevel.NORMAL

/**
 * One "Request: …" / "Limit: …" line for a resource across the pod's
 * containers: the sum when every container sets it, "none" when none does,
 * and a count otherwise — a pod-wide sum would be wrong then.
 */
internal fun resourceLine(label: String, values: List<Long?>, format: (Long) -> String): String {
    val set = values.filterNotNull()
    return when {
        set.isEmpty() -> "$label: none"
        set.size == values.size -> "$label: ${format(set.sum())}"
        else -> "$label: set on ${set.size} of ${values.size} containers"
    }
}

internal fun cpuTooltip(usage: PodUsage, resources: List<ContainerResources>): String = listOf(
    "Used: ${formatCpuCores(usage.cpuMillis)}",
    resourceLine("Request", resources.map { it.cpuRequestMillis }, ::formatCpuCores),
    resourceLine("Limit", resources.map { it.cpuLimitMillis }, ::formatCpuCores),
).joinToString("\n")

internal fun memoryTooltip(usage: PodUsage, resources: List<ContainerResources>): String {
    val lines = mutableListOf(
        "Used: ${formatMemorySize(usage.memoryBytes)}",
        resourceLine("Request", resources.map { it.memoryRequestBytes }, ::formatMemorySize),
        resourceLine("Limit", resources.map { it.memoryLimitBytes }, ::formatMemorySize),
    )
    worstMemoryPressure(usage, resources)?.let { p ->
        val pct = (p.fraction * 100).toInt()
        lines += if (resources.size > 1) {
            "Closest to its limit: ${p.container} ($pct% of ${formatMemorySize(p.limitBytes)})"
        } else {
            "$pct% of the limit"
        }
    }
    return lines.joinToString("\n")
}

/** The Pods table's CPU cell; "—" without a sample. Never tinted. */
internal fun cpuCell(pod: PodInfo, usage: PodUsage?): CellData {
    if (usage == null) return CellData(NONE_PLACEHOLDER)
    val text = formatCpuCores(usage.cpuMillis)
    val tooltip = cpuTooltip(usage, pod.resources)
    return CellData(
        text = text,
        sortNumber = usage.cpuMillis.toDouble(),
        content = { UsageCellContent(text, "CPU", tooltip, UsageLevel.NORMAL) },
    )
}

/** The Pods table's Memory cell; "—" without a sample; tinted by [memoryLevel]. */
internal fun memoryCell(pod: PodInfo, usage: PodUsage?): CellData {
    if (usage == null) return CellData(NONE_PLACEHOLDER)
    val text = formatMemorySize(usage.memoryBytes)
    val level = memoryLevel(usage, pod.resources)
    val tooltip = memoryTooltip(usage, pod.resources)
    return CellData(
        text = text,
        sortNumber = usage.memoryBytes.toDouble(),
        content = { UsageCellContent(text, "Memory", tooltip, level) },
    )
}

/**
 * Value + hover tooltip; a WARNING/CRITICAL level adds a glyph (so the alert
 * reads without colour — CVD and Mono palettes) and tints the value.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsageCellContent(text: String, title: String, tooltip: String, level: UsageLevel) {
    val color = when (level) {
        UsageLevel.CRITICAL -> KdError
        UsageLevel.WARNING -> KdWarning
        UsageLevel.NORMAL -> KdTextPrimary
    }
    TooltipArea(
        tooltip = { ActionTooltip(title, tooltip) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (level != UsageLevel.NORMAL) {
                Icon(
                    painter = painterResource(if (level == UsageLevel.CRITICAL) Res.drawable.error_filled else Res.drawable.warning_filled),
                    contentDescription = if (level == UsageLevel.CRITICAL) {
                        "At least 90% of a container's memory limit"
                    } else {
                        "At least 80% of a container's memory limit"
                    },
                    modifier = Modifier.size(12.dp),
                    tint = color,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
