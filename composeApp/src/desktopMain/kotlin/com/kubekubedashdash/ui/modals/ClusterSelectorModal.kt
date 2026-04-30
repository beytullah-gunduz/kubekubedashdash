package com.kubekubedashdash.ui.modals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.util.EksClusterDiscoverer
import com.kubekubedashdash.util.MockClusterProvider

private val EksOrange = Color(0xFFFF9900)
private val MockTeal = Color(0xFF00BFA5)

private val eksPattern = Regex("""arn:aws:eks:([^:]+):(\d+):cluster/(.+)""")

private data class ParsedContext(
    val rawName: String,
    val isEks: Boolean,
    val isMock: Boolean = false,
    val clusterName: String,
    val awsAccount: String? = null,
    val awsRegion: String? = null,
    val awsProfile: String? = null,
)

private fun parseContext(ctx: String, awsProfile: String?): ParsedContext {
    if (ctx == MockClusterProvider.MOCK_CONTEXT_NAME) {
        return ParsedContext(
            rawName = ctx,
            isEks = false,
            isMock = true,
            clusterName = "Demo Cluster",
        )
    }
    val match = eksPattern.matchEntire(ctx)
    return if (match != null) {
        ParsedContext(
            rawName = ctx,
            isEks = true,
            clusterName = match.groupValues[3],
            awsRegion = match.groupValues[1],
            awsAccount = match.groupValues[2],
            awsProfile = awsProfile,
        )
    } else {
        ParsedContext(
            rawName = ctx,
            isEks = false,
            clusterName = ctx,
            awsProfile = awsProfile,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClusterSelectorModal(
    contexts: List<String>,
    selectedContext: String,
    onOpenCluster: (String, OpenTarget) -> Unit,
    onDismiss: () -> Unit,
    onDiscoverEks: () -> Unit = {},
    dismissable: Boolean = true,
    canAddTab: Boolean = false,
    defaultTarget: OpenTarget = OpenTarget.CURRENT_VIEW,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val bindings = remember(contexts, reactiveClient) {
        runCatching { reactiveClient.getContextBindings() }
            .getOrElse { emptyList() }
            .associateBy { it.name }
    }
    val awsCliAvailable = remember { EksClusterDiscoverer.isAwsCliAvailable() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .then(
                if (dismissable) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 700.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(12.dp),
            color = KdSurface,
            border = BorderStroke(1.dp, KdBorder),
            shadowElevation = 16.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(KdPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = KdPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Select Cluster",
                            style = MaterialTheme.typography.titleMedium,
                            color = KdTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (contexts.isEmpty()) {
                                "Loading…"
                            } else {
                                "${contexts.size} context${if (contexts.size != 1) "s" else ""} available"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                    if (dismissable) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = KdTextSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onDismiss),
                        )
                    }
                }

                HorizontalDivider(color = KdBorder, thickness = 1.dp)

                if (contexts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = KdPrimary,
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Loading clusters…",
                                style = MaterialTheme.typography.bodySmall,
                                color = KdTextSecondary,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 700.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        items(contexts, key = { it }) { ctx ->
                            val isSelected = ctx == selectedContext
                            val awsProfile = bindings[ctx]?.awsProfile
                            val parsed = remember(ctx, awsProfile) { parseContext(ctx, awsProfile) }
                            var hovered by remember { mutableStateOf(false) }
                            val bg = when {
                                isSelected -> KdSelected
                                hovered -> KdHover
                                else -> Color.Transparent
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .clickable {
                                        onOpenCluster(ctx, defaultTarget)
                                        onDismiss()
                                    }
                                    .onPointerEvent(PointerEventType.Enter) { hovered = true }
                                    .onPointerEvent(PointerEventType.Exit) { hovered = false }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (parsed.isMock) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MockTeal),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Science,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else if (parsed.isEks) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(EksOrange),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "EKS",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isSelected) {
                                                    KdPrimary.copy(alpha = 0.15f)
                                                } else {
                                                    KdSurfaceVariant
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Dns,
                                            contentDescription = null,
                                            tint = if (isSelected) KdPrimary else KdTextSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        parsed.clusterName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) KdPrimary else KdTextPrimary,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (parsed.isMock) {
                                        Text(
                                            "In-memory mock cluster with sample data",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = KdTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else if (parsed.isEks) {
                                        val base = "${parsed.awsAccount} · ${parsed.awsRegion}"
                                        val subtitle = parsed.awsProfile?.let { "$base · profile: $it" } ?: base
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = KdTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (parsed.awsProfile == null) {
                                            Text(
                                                "no profile bound — uses default credentials",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = KdTextSecondary.copy(alpha = 0.75f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                if (canAddTab) {
                                    Spacer(Modifier.width(8.dp))
                                    // Hide the per-row "+" when row-click already
                                    // does NEW_TAB (i.e. opened from the tab strip's
                                    // + button); it would just duplicate the action.
                                    if (defaultTarget != OpenTarget.NEW_TAB) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (hovered) KdSurfaceVariant else Color.Transparent)
                                                .clickable {
                                                    onOpenCluster(ctx, OpenTarget.NEW_TAB)
                                                    onDismiss()
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Add,
                                                contentDescription = "Open $ctx in new tab",
                                                tint = KdTextSecondary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (hovered) KdSurfaceVariant else Color.Transparent)
                                            .clickable {
                                                onOpenCluster(ctx, OpenTarget.NEW_WINDOW)
                                                onDismiss()
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Open $ctx in new window",
                                            tint = KdTextSecondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = KdPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = KdBorder, thickness = 1.dp)

                var footerHovered by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (awsCliAvailable) {
                                Modifier
                                    .clickable { onDiscoverEks() }
                                    .onPointerEvent(PointerEventType.Enter) { footerHovered = true }
                                    .onPointerEvent(PointerEventType.Exit) { footerHovered = false }
                            } else {
                                Modifier
                            },
                        )
                        .background(if (footerHovered) KdHover else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (awsCliAvailable) {
                                    EksOrange.copy(alpha = 0.15f)
                                } else {
                                    KdSurfaceVariant
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = if (awsCliAvailable) EksOrange else KdTextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Discover EKS clusters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (awsCliAvailable) KdTextPrimary else KdTextSecondary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (awsCliAvailable) {
                                "Add EKS clusters from your AWS account to kubeconfig"
                            } else {
                                "Requires AWS CLI on PATH"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }
            }
        }
    }
}
