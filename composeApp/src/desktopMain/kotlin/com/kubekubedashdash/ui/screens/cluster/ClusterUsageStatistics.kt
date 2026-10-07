package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.components.HalfCircularUsageIndicator
import com.kubekubedashdash.ui.components.PodStatusBar
import com.kubekubedashdash.ui.components.UsageHistoryBar
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import org.jetbrains.compose.resources.painterResource

/**
 * Header wording for a usage section whose pod count and usage are narrower
 * than its capacity (the gauges' denominator), which is always whole-cluster.
 */
data class UsageScope(val title: String, val note: String) {
    companion object {
        fun namespace(namespace: String) = UsageScope(
            title = "Usage Statistics · $namespace",
            note = "This namespace's usage and pods, against whole-cluster capacity",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClusterUsageStatistics(
    phaseCounts: PodPhaseCounts?,
    usage: ResourceUsageSummary?,
    cpuHistory: List<Float>,
    memHistory: List<Float>,
    podsCount: Int?,
    podsCapacity: Int,
    podsLoaded: Boolean,
    podsHistory: List<Float>,
    topNodes: List<NodeResourceUsage>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNodeClick: (String) -> Unit,
    // Non-null when the pod count and usage don't cover the whole cluster.
    scope: UsageScope? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = 10.dp.kdCorner,
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(KdBorder)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        scope?.title ?: "Cluster Usage Statistics",
                        style = MaterialTheme.typography.titleMedium,
                        color = KdTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (scope != null) {
                        Text(
                            scope.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }
                Icon(
                    painter = painterResource(
                        if (expanded) {
                            Res.drawable.keyboard_arrow_up_filled
                        } else {
                            Res.drawable.keyboard_arrow_down_filled
                        },
                    ),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = KdTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                    // A third gauge that doesn't fit drops to its own line; the
                    // padding keeps the captions ("cores", "GiB") from touching.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.padding(horizontal = GAUGE_GAP / 2)) { ClusterCpuGauge(usage, cpuHistory) }
                        Box(Modifier.padding(horizontal = GAUGE_GAP / 2)) { ClusterMemoryGauge(usage, memHistory) }
                        Box(Modifier.padding(horizontal = GAUGE_GAP / 2)) { ClusterPodsGauge(podsCount, podsCapacity, podsLoaded, podsHistory) }
                    }

                    Spacer(Modifier.height(20.dp))

                    val showTopNodes = topNodes.size >= 3 && usage?.metricsAvailable == true
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        if (usageSectionsSideBySide(maxWidth)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                PodStatusSection(phaseCounts, Modifier.weight(1f))
                                if (showTopNodes) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        TopNodesByPressure(topNodes, onNodeClick)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                PodStatusSection(phaseCounts, Modifier.fillMaxWidth())
                                if (showTopNodes) {
                                    Spacer(Modifier.height(20.dp))
                                    TopNodesByPressure(topNodes, onNodeClick)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val GAUGE_GAP = 16.dp
private val LEGEND_GAP = 16.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PodStatusSection(phaseCounts: PodPhaseCounts?, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(
            "Pod Status",
            style = MaterialTheme.typography.labelLarge,
            color = KdTextPrimary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        if (phaseCounts == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                BusyIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = KdPrimary,
                )
            }
        } else {
            PodStatusBar(
                phaseCounts.running,
                phaseCounts.pending,
                phaseCounts.failed,
                phaseCounts.succeeded,
            )
            Spacer(Modifier.height(10.dp))
            // Wraps onto a second line rather than squeezing the last entry
            // into one letter per line; the padding is the gap SpaceAround
            // alone doesn't guarantee.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val itemModifier = Modifier.padding(horizontal = LEGEND_GAP / 2)
                StatusLegend("Running", phaseCounts.running, KdSuccess, itemModifier)
                StatusLegend("Pending", phaseCounts.pending, KdWarning, itemModifier)
                StatusLegend("Failed", phaseCounts.failed, KdError, itemModifier)
                StatusLegend("Succeeded", phaseCounts.succeeded, KdInfo, itemModifier)
            }
        }
    }
}

@Composable
private fun ClusterCpuGauge(usage: ResourceUsageSummary?, cpuHistory: List<Float>) {
    if (usage == null) {
        GaugePlaceholder(loading = true)
        return
    }
    if (!usage.metricsAvailable) {
        GaugePlaceholder(loading = false, message = "Metrics server unavailable")
        return
    }
    val frac = if (usage.cpuCapacityMillis > 0) {
        usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis.toFloat()
    } else {
        0f
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HalfCircularUsageIndicator(
            fraction = frac,
            label = "CPU",
            usedText = formatCpuCores(usage.cpuUsedMillis),
            totalText = formatCpuCores(usage.cpuCapacityMillis),
        )
        Spacer(Modifier.height(6.dp))
        UsageHistoryBar(history = cpuHistory, modifier = Modifier.width(120.dp).height(36.dp))
    }
}

@Composable
private fun ClusterMemoryGauge(usage: ResourceUsageSummary?, memHistory: List<Float>) {
    if (usage == null) {
        GaugePlaceholder(loading = true)
        return
    }
    if (!usage.metricsAvailable) {
        GaugePlaceholder(loading = false, message = "Metrics server unavailable")
        return
    }
    val frac = if (usage.memoryCapacityBytes > 0) {
        usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes.toFloat()
    } else {
        0f
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HalfCircularUsageIndicator(
            fraction = frac,
            label = "Memory",
            usedText = formatMemorySize(usage.memoryUsedBytes),
            totalText = formatMemorySize(usage.memoryCapacityBytes),
        )
        Spacer(Modifier.height(6.dp))
        UsageHistoryBar(history = memHistory, modifier = Modifier.width(120.dp).height(36.dp))
    }
}

@Composable
private fun ClusterPodsGauge(podsCount: Int?, podsCapacity: Int, podsLoaded: Boolean, podsHistory: List<Float>) {
    if (!podsLoaded || podsCount == null) {
        GaugePlaceholder(loading = true)
        return
    }
    if (podsCapacity <= 0) {
        // Capacity unknown (no node allocatable reported — e.g. RBAC can't list
        // nodes). Show the count alone instead of a misleading "0%" gauge that
        // reads "247 / 0".
        PodCountWithoutCapacity(podsCount)
        return
    }
    val frac = podsCount.toFloat() / podsCapacity.toFloat()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HalfCircularUsageIndicator(
            fraction = frac,
            label = "Pods",
            usedText = "$podsCount",
            totalText = "$podsCapacity",
        )
        Spacer(Modifier.height(6.dp))
        UsageHistoryBar(history = podsHistory, modifier = Modifier.width(120.dp).height(36.dp))
    }
}

@Composable
private fun PodCountWithoutCapacity(podsCount: Int) {
    Column(
        modifier = Modifier.size(width = 120.dp, height = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$podsCount", style = MaterialTheme.typography.titleLarge, color = KdTextPrimary)
        Text("Pods", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
        Text("capacity unknown", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
    }
}

@Composable
private fun GaugePlaceholder(loading: Boolean, message: String? = null) {
    Box(
        modifier = Modifier.size(width = 120.dp, height = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            BusyIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = KdPrimary)
        } else if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        }
    }
}
