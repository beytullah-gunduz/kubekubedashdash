package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.open_in_new_filled
import com.kubekubedashdash.services.portforward.PortForwardEntry
import com.kubekubedashdash.services.portforward.PortForwardRegistry
import com.kubekubedashdash.services.portforward.PortForwardStatus
import com.kubekubedashdash.ui.LogTabBadge
import com.kubekubedashdash.ui.LogTabClusterBadge
import com.kubekubedashdash.ui.portforward.portForwardRouteText
import com.kubekubedashdash.ui.portforward.portForwardStatusText
import java.awt.Desktop
import java.net.URI

@Composable
fun DrawerPortForwardsPane(clusterBadges: Map<String, LogTabBadge>, modifier: Modifier = Modifier) {
    val entries by PortForwardRegistry.forwards.collectAsState()

    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No port forwards. Start one from a pod or service: the Port forward button in its detail header, or its row menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    val running = entries.count { it.isRunning }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$running running", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = { entries.filter { it.isRunning }.forEach { PortForwardRegistry.stop(it.id) } },
                enabled = running > 0,
            ) {
                Text("Stop all")
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.id }) { entry ->
                PortForwardRow(entry, clusterBadges)
            }
        }
    }
}

@Composable
private fun PortForwardRow(e: PortForwardEntry, clusterBadges: Map<String, LogTabBadge>) {
    val copyToClipboard = rememberCopyToClipboard()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dotColor = when (e.status) {
            PortForwardStatus.Active -> KdSuccess
            PortForwardStatus.Disconnected -> KdWarning
            is PortForwardStatus.Stopped -> KdTextSecondary
        }
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badge = clusterBadges[e.sessionId]
                if (badge != null) {
                    LogTabClusterBadge(badge)
                } else {
                    Text(e.context, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                }
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    portForwardRouteText(e),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(portForwardStatusText(e), style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
            e.lastError?.let {
                Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = KdError, maxLines = 2)
            }
        }
        if (e.isRunning) {
            TooltipIconButton(
                icon = Res.drawable.open_in_new_filled,
                label = "Open http://${e.localAddress} in the browser",
                tint = KdTextSecondary,
                onClick = {
                    runCatching {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI("http://${e.localAddress}"))
                        }
                    }
                },
            )
            TooltipIconButton(
                icon = Res.drawable.content_copy_filled,
                label = "Copy ${e.localAddress}",
                tint = KdTextSecondary,
                onClick = { copyToClipboard(e.localAddress, "Copied address") },
            )
            TextButton(onClick = { PortForwardRegistry.stop(e.id) }) {
                Text("Stop")
            }
        } else {
            TextButton(onClick = { PortForwardRegistry.remove(e.id) }) {
                Text("Remove")
            }
        }
    }
}
