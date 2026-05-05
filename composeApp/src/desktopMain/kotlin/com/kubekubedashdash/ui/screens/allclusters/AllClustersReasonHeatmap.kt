package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary

@Composable
internal fun AllClustersReasonHeatmap(
    data: HeatmapData,
    onCellClick: (cluster: String, reason: String) -> Unit,
) {
    if (data.clusters.isEmpty() || data.reasons.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(0.dp))
        return
    }

    val allNonZeroValues = data.cells.values.filter { it > 0 }
    val clusterColumnWidth = 180.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdSurfaceVariant)
            .padding(8.dp)
            .heightIn(max = 240.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Header row: empty leading column + reason labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading empty column (120dp)
            Box(modifier = Modifier.width(clusterColumnWidth))

            // Reason header cells
            data.reasons.forEach { reason ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(80.dp),
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // Data rows: one per cluster
        data.clusters.forEach { cluster ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cluster name label (120dp)
                Text(
                    text = cluster,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(clusterColumnWidth),
                )

                // Cell per reason
                data.reasons.forEach { reason ->
                    val count = data.cells[cluster to reason] ?: 0
                    val bgColor = quantileColor(count, allNonZeroValues)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .background(bgColor)
                            .clickable { onCellClick(cluster, reason) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (count > 0) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
