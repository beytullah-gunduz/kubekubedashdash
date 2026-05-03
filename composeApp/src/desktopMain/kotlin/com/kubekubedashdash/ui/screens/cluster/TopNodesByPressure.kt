package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.NodeResourceUsage

@Composable
internal fun TopNodesByPressure(
    nodes: List<NodeResourceUsage>,
    onNodeClick: (String) -> Unit,
) {
    Column {
        Text(
            "Top nodes by pressure",
            style = MaterialTheme.typography.labelLarge,
            color = KdTextPrimary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        nodes.forEach { node ->
            TopNodeRow(node, onClick = { onNodeClick(node.nodeName) })
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TopNodeRow(node: NodeResourceUsage, onClick: () -> Unit) {
    val showCpu = node.cpuFraction >= node.memoryFraction
    val frac = if (showCpu) node.cpuFraction else node.memoryFraction
    val pct = (frac * 100).toInt().coerceAtLeast(0)
    val barColor = when {
        frac > 0.85f -> KdError
        frac > 0.70f -> KdWarning
        else -> KdSuccess
    }
    val metricLabel = if (showCpu) "CPU $pct%" else "MEM $pct%"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            node.nodeName,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.weight(0.5f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(0.5f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(KdSurfaceVariant.copy(alpha = 0.4f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(barColor),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            metricLabel,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(72.dp),
        )
    }
}
