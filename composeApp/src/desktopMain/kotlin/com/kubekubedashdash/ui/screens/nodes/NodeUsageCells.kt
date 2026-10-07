package com.kubekubedashdash.ui.screens.nodes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.OverflowTooltipText
import com.kubekubedashdash.ui.components.UsageBar
import com.kubekubedashdash.ui.components.color
import com.kubekubedashdash.ui.components.formatUsagePair
import com.kubekubedashdash.ui.components.usageLevel
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import com.kubekubedashdash.util.parseCpuToMillis
import com.kubekubedashdash.util.parseMemoryToBytes

/** What a node's CPU or Memory cell shows; [fraction] null = no sample (allocatable only, no bar). */
internal data class NodeUsageCellModel(val text: String, val fraction: Float?)

internal fun nodeCpuModel(node: NodeInfo, usage: NodeResourceUsage?): NodeUsageCellModel = if (usage != null && usage.cpuCapacityMillis > 0) {
    usageModel(usage.cpuFraction, formatCpuCores(usage.cpuUsedMillis), formatCpuCores(usage.cpuCapacityMillis))
} else {
    NodeUsageCellModel(if (node.cpu.isBlank()) NONE_PLACEHOLDER else formatCpuCores(parseCpuToMillis(node.cpu)), null)
}

internal fun nodeMemoryModel(node: NodeInfo, usage: NodeResourceUsage?): NodeUsageCellModel = if (usage != null && usage.memoryCapacityBytes > 0) {
    usageModel(usage.memoryFraction, formatMemorySize(usage.memoryUsedBytes), formatMemorySize(usage.memoryCapacityBytes))
} else {
    NodeUsageCellModel(if (node.memory.isBlank()) NONE_PLACEHOLDER else formatMemorySize(parseMemoryToBytes(node.memory)), null)
}

// Percent first: a narrow column's ellipsis then eats the detail, not the number.
private fun usageModel(fraction: Float, used: String, capacity: String) = NodeUsageCellModel("${(fraction * 100).toInt()}% · ${formatUsagePair(used, capacity)}", fraction)

internal fun nodeUsageCell(model: NodeUsageCellModel): CellData {
    val fraction = model.fraction ?: return CellData(model.text)
    return CellData(
        text = model.text,
        sortNumber = fraction.toDouble(),
        content = { NodeUsageCellContent(model.text, fraction) },
    )
}

/** A 32 dp bar coloured by [usageLevel], then the text (full text on hover when truncated). */
@Composable
private fun NodeUsageCellContent(text: String, fraction: Float) {
    val barColor = usageLevel(fraction).color()
    // End padding: a value as wide as its column must not run into the next one.
    Row(modifier = Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        UsageBar(fraction, barColor, Modifier.width(32.dp))
        Spacer(Modifier.width(6.dp))
        OverflowTooltipText(text = text, style = MaterialTheme.typography.bodySmall, color = KdTextPrimary)
    }
}
