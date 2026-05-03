package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.info_filled
import com.kubekubedashdash.resources.schedule_filled
import com.kubekubedashdash.resources.warning_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Icon + color pair for a Kubernetes status string. Pairs a *shape* with the
 * color so the state is conveyed even when the user can't distinguish the
 * color (WCAG SC 1.4.1).
 */
data class StatusVisual(val icon: DrawableResource, val color: Color)

fun statusVisual(status: String): StatusVisual = when (status.lowercase()) {
    "running", "active", "ready", "bound", "available", "true" ->
        StatusVisual(Res.drawable.check_circle_filled, KdSuccess)

    "succeeded", "completed", "complete" ->
        StatusVisual(Res.drawable.check_circle_filled, KdInfo)

    "pending", "waiting", "containercreating", "terminating" ->
        StatusVisual(Res.drawable.schedule_filled, KdWarning)

    "warning" ->
        StatusVisual(Res.drawable.warning_filled, KdWarning)

    "failed", "error", "crashloopbackoff", "imagepullbackoff",
    "errimagepull", "oomkilled", "notready", "terminated",
    -> StatusVisual(Res.drawable.error_filled, KdError)

    else -> StatusVisual(Res.drawable.info_filled, KdTextSecondary)
}

fun statusColor(status: String): Color = statusVisual(status).color

/**
 * Chip-style badge with a tinted surface, glyph, and label. Used in detail
 * panels where the status is the row's most important signal.
 */
@Composable
fun StatusBadge(status: String, color: Color = statusColor(status)) {
    val visual = statusVisual(status)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(visual.icon),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color,
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

/**
 * Inline icon + colored label, no chrome. Used inside dense table cells where
 * the row already provides background/zebra and a chip would feel heavy.
 * Keeps the existing visual rhythm of the resource lists while making state
 * recognizable without color.
 */
@Composable
fun StatusCell(
    status: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle? = null,
) {
    if (status.isBlank()) return
    val visual = statusVisual(status)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(visual.icon),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = visual.color,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            status,
            style = style ?: MaterialTheme.typography.bodySmall,
            color = visual.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
