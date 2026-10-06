package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.ui.components.UsageBar
import com.kubekubedashdash.ui.components.color
import com.kubekubedashdash.ui.components.usageTier

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
internal fun TopNodeRow(node: NodeResourceUsage, onClick: () -> Unit, leading: (@Composable RowScope.() -> Unit)? = null) {
    val showCpu = node.cpuFraction >= node.memoryFraction
    val frac = if (showCpu) node.cpuFraction else node.memoryFraction
    val pct = (frac * 100).toInt().coerceAtLeast(0)
    val barColor = usageTier(frac).color()
    val metricLabel = if (showCpu) "CPU $pct%" else "MEM $pct%"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Text(
            node.nodeName,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.weight(0.5f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        UsageBar(frac, barColor, Modifier.weight(0.5f), height = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            metricLabel,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(72.dp),
        )
    }
}
