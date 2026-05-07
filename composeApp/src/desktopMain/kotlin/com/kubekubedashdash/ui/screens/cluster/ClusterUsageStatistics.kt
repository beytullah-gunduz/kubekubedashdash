package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.ui.components.HalfCircularUsageIndicator
import com.kubekubedashdash.ui.components.PodStatusBar
import com.kubekubedashdash.ui.components.UsageHistoryBar
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import org.jetbrains.compose.resources.painterResource

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
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
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
                Text(
                    "Cluster Usage Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ClusterCpuGauge(usage, cpuHistory)
                        ClusterMemoryGauge(usage, memHistory)
                        ClusterPodsGauge(podsCount, podsCapacity, podsLoaded, podsHistory)
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
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
                                    CircularProgressIndicator(
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                ) {
                                    StatusLegend("Running", phaseCounts.running, KdSuccess)
                                    StatusLegend("Pending", phaseCounts.pending, KdWarning)
                                    StatusLegend("Failed", phaseCounts.failed, KdError)
                                    StatusLegend("Succeeded", phaseCounts.succeeded, KdInfo)
                                }
                            }
                        }

                        if (topNodes.size >= 3 && usage?.metricsAvailable == true) {
                            Box(modifier = Modifier.weight(1f)) {
                                TopNodesByPressure(topNodes, onNodeClick)
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
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
    val frac = if (podsCapacity > 0) podsCount.toFloat() / podsCapacity.toFloat() else 0f
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
private fun GaugePlaceholder(loading: Boolean, message: String? = null) {
    Box(
        modifier = Modifier.size(width = 120.dp, height = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = KdPrimary)
        } else if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
        }
    }
}
