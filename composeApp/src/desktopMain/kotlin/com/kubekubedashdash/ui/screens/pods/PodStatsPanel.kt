package com.kubekubedashdash.ui.screens.pods

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.ui.components.HalfCircularPodDistribution
import com.kubekubedashdash.ui.components.HalfCircularUsageIndicator
import com.kubekubedashdash.ui.components.PodSegment
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize

@Composable
internal fun PodStatsPanel(
    pods: List<PodInfo>,
    usage: ResourceUsageSummary?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = SolidColor(KdBorder),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Pod Statistics",
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodCountsSummary(pods)

                    MemoryUsageSummary(usage)
                    CpuUsageSummary(usage)
                }
            }
        }
    }
}

@Composable
private fun PodCountsSummary(pods: List<PodInfo>) {
    val running = pods.count { it.status == "Running" }
    val pending = pods.count { it.status == "Pending" || it.status == "ContainerCreating" }
    val failed = pods.count { it.status in listOf("Failed", "Error", "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "OOMKilled") }
    val succeeded = pods.count { it.status == "Succeeded" || it.status == "Completed" }

    HalfCircularPodDistribution(
        segments = listOf(
            PodSegment(running, "Running", KdSuccess),
            PodSegment(pending, "Pending", KdWarning),
            PodSegment(failed, "Failed", KdError),
            PodSegment(succeeded, "Succeeded", KdInfo),
        ),
    )
}

@Composable
private fun MemoryUsageSummary(usage: ResourceUsageSummary?) {
    if (usage != null && usage.metricsAvailable) {
        val memFraction = if (usage.memoryCapacityBytes > 0) {
            usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes.toFloat()
        } else {
            0f
        }
        HalfCircularUsageIndicator(
            fraction = memFraction,
            label = "Memory",
            usedText = formatMemorySize(usage.memoryUsedBytes),
            totalText = formatMemorySize(usage.memoryCapacityBytes),
        )
    } else if (usage == null) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = KdPrimary,
            )
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Metrics server unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
            )
        }
    }
}

@Composable
private fun CpuUsageSummary(usage: ResourceUsageSummary?) {
    if (usage != null && usage.metricsAvailable) {
        val cpuFraction = if (usage.cpuCapacityMillis > 0) {
            usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis.toFloat()
        } else {
            0f
        }
        HalfCircularUsageIndicator(
            fraction = cpuFraction,
            label = "CPU",
            usedText = formatCpuCores(usage.cpuUsedMillis),
            totalText = formatCpuCores(usage.cpuCapacityMillis),
        )
    }
}
