package com.kubekubedashdash.ui.screens.topology

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBackground
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.models.ResourceGraphNode
import com.kubekubedashdash.ui.components.kindColor
import com.kubekubedashdash.ui.components.kindStatusColor
import com.kubekubedashdash.ui.components.namespaceAccentColor

@Composable
internal fun GraphNodeCard(
    node: ResourceGraphNode,
    selected: Boolean,
    dimmed: Boolean,
    showNamespace: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = kindColor(node.kind)
    val sColor = kindStatusColor(node.kind, node.status)
    val alpha = if (dimmed) 0.35f else 1f
    val borderWidth = if (selected) 2.dp else 1.dp
    val borderAlpha = if (selected) 0.8f else 0.25f
    val nsToShow = node.namespace?.takeIf { showNamespace && it.isNotBlank() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (selected) 0.15f else 0.08f).compositeOver(KdBackground),
        border = BorderStroke(borderWidth, color.copy(alpha = borderAlpha * alpha)),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nsToShow != null) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(namespaceAccentColor(nsToShow).copy(alpha = alpha)),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background((sColor ?: color).copy(alpha = alpha)))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        node.kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = alpha),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (node.kind == "External") {
                        // LB hostnames like a1b2c3d4-foo.elb.us-east-1.amazonaws.com get
                        // the most-useful suffix (region/zone) chopped off by end-ellipsis,
                        // and they're not naturally word-wrappable (no spaces). Use
                        // monospace + a slightly smaller size + 2 lines so the full
                        // hostname is readable without a tooltip.
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            ),
                            color = KdTextPrimary.copy(alpha = alpha),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true,
                        )
                    } else {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = KdTextPrimary.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (nsToShow != null) {
                        Text(
                            nsToShow,
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (node.status != null) {
                        Text(
                            node.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = (sColor ?: KdTextSecondary).copy(alpha = alpha),
                        )
                    }
                    val restarts = node.restartCount ?: 0
                    if (restarts > 0) {
                        Text(
                            if (restarts == 1) "1 restart" else "$restarts restarts",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdWarning.copy(alpha = alpha),
                        )
                    }
                }
            }
        }
    }
}

internal fun shortImage(image: String): String {
    val lastSlash = image.lastIndexOf('/')
    return if (lastSlash >= 0) image.substring(lastSlash + 1) else image
}

@Composable
internal fun WorkloadGroupCard(
    node: ResourceGraphNode,
    podCount: Int,
    expanded: Boolean,
    canExpand: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    showNamespace: Boolean,
    onClick: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Card color routes through subKind (Deployment / StatefulSet / DaemonSet / CronJob /
    // Job) so the card visually distinguishes workload types at a glance. Status routing
    // keeps the "WorkloadGroup" key — the topology-specific "X/Y ready" / breakdown
    // format only makes sense under that key, regardless of which workload kind is
    // underneath.
    val color = kindColor(node.subKind ?: node.kind)
    val sColor = kindStatusColor(node.kind, node.status)
    val alpha = if (dimmed) 0.35f else 1f
    val borderWidth = if (selected) 2.dp else 1.dp
    val borderAlpha = if (selected) 0.8f else 0.25f
    val nsToShow = node.namespace?.takeIf { showNamespace && it.isNotBlank() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (selected) 0.15f else 0.08f).compositeOver(KdBackground),
        border = BorderStroke(borderWidth, color.copy(alpha = borderAlpha * alpha)),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (nsToShow != null) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(namespaceAccentColor(nsToShow).copy(alpha = alpha)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background((sColor ?: color).copy(alpha = alpha)))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            node.subKind ?: node.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = color.copy(alpha = alpha),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = KdTextPrimary.copy(alpha = alpha),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (nsToShow != null) {
                            Text(
                                nsToShow,
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary.copy(alpha = alpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!node.image.isNullOrBlank()) {
                            Text(
                                shortImage(node.image),
                                style = MaterialTheme.typography.labelSmall,
                                color = KdTextSecondary.copy(alpha = alpha),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (node.status != null) {
                            Text(
                                node.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = (sColor ?: KdTextSecondary).copy(alpha = alpha),
                            )
                        }
                    }
                }
                if (podCount > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleExpand),
                        color = color.copy(alpha = if (canExpand) 0.06f else 0.02f),
                    ) {
                        Text(
                            text = if (expanded) "▾ $podCount pods" else "▸ $podCount pods",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (canExpand) color.copy(alpha = alpha) else KdTextSecondary.copy(alpha = alpha),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
