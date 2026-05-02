package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.clusterInitial
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel

/** Small summary card showing health and resource counts for a single open cluster. */
@Composable
internal fun ClusterSummaryCard(
    summary: AllClustersViewModel.ClusterSummary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val clusterColor = ClusterColor.fromContext(summary.contextName)

    Card(
        modifier = modifier
            .width(220.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: colored initial circle + context name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(clusterColor.composeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = clusterInitial(summary.contextName),
                        style = MaterialTheme.typography.labelMedium,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = summary.contextName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Connection status badge
            val (statusLabel, statusColor) = when {
                summary.isConnecting -> "Connecting…" to KdWarning
                summary.isConnected -> "Connected" to KdSuccess
                else -> "Disconnected" to KdError
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )

            Spacer(Modifier.height(8.dp))

            // Stats grid
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                StatRow(label = "Nodes", value = summary.nodeCount.toString())
                StatRow(label = "Namespaces", value = summary.namespaceCount.toString())
                StatRow(
                    label = "Errors / Warnings",
                    value = summary.recentErrorCount.toString(),
                    valueColor = if (summary.recentErrorCount > 0) KdWarning else null,
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
