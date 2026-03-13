package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning

@Composable
fun StatusBadge(status: String, color: Color = statusColor(status)) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

fun statusColor(status: String): Color = when (status.lowercase()) {
    "running", "active", "ready", "bound", "available", "true" -> KdSuccess

    "pending", "waiting", "containercreating" -> KdWarning

    "failed", "error", "crashloopbackoff", "imagepullbackoff",
    "errimagepull", "oomkilled", "notready", "terminated",
    -> KdError

    "succeeded", "completed", "complete" -> KdInfo

    "terminating" -> KdWarning

    "unknown" -> KdTextSecondary

    else -> KdTextSecondary
}

@Composable
fun LabelChip(key: String, value: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = KdSurfaceVariant,
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(key, style = MaterialTheme.typography.labelSmall, color = KdPrimary)
            Text("=", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            Text(value, style = MaterialTheme.typography.labelSmall, color = KdTextPrimary)
        }
    }
}
