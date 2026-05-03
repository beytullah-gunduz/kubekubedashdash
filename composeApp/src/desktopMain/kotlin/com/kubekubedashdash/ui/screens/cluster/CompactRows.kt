package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.info
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.ui.components.StatusCell
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun CompactNodeRow(node: NodeInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.weight(3f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        StatusCell(
            status = node.status,
            modifier = Modifier.width(70.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            node.pods,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(48.dp),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            node.age,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun CompactPodRow(pod: PodInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pod.name,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.weight(3f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        StatusCell(
            status = pod.status,
            modifier = Modifier.width(100.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            pod.namespace,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(120.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            pod.age,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun CompactEventRow(event: EventInfo, onClick: () -> Unit) {
    val (icon, tint) = when (event.type) {
        "Warning" -> Res.drawable.warning_filled to KdWarning
        "Error" -> Res.drawable.error_filled to KdError
        else -> Res.drawable.info to KdSuccess
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = event.type,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            event.message,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.weight(2f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            event.namespace.ifBlank { "-" },
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(120.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            event.lastSeen,
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}
