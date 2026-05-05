package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSidebarBg
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.chevron_right_filled
import com.kubekubedashdash.resources.cloud_filled
import com.kubekubedashdash.resources.content_copy_filled
import com.kubekubedashdash.resources.dashboard_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.dynamic_feed_filled
import com.kubekubedashdash.resources.expand_more_filled
import com.kubekubedashdash.resources.folder_open_filled
import com.kubekubedashdash.resources.folder_special_filled
import com.kubekubedashdash.resources.language_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.notifications_filled
import com.kubekubedashdash.resources.save_filled
import com.kubekubedashdash.resources.schedule_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.resources.work_filled
import com.kubekubedashdash.ui.components.kdFocusRing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    collapsed: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdSidebarBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            SidebarItem(Res.drawable.dashboard_filled, "Cluster", currentScreen is Screen.Main.ClusterOverview, collapsed) {
                onNavigate(Screen.Main.ClusterOverview)
            }
            SidebarItem(Res.drawable.dns_filled, "Nodes", currentScreen is Screen.Main.Nodes, collapsed) {
                onNavigate(Screen.Main.Nodes())
            }
            SidebarItem(Res.drawable.folder_special_filled, "Namespaces", currentScreen is Screen.Main.Namespaces, collapsed) {
                onNavigate(Screen.Main.Namespaces)
            }
            SidebarItem(Res.drawable.notifications_filled, "Events", currentScreen is Screen.Main.Events, collapsed) {
                onNavigate(Screen.Main.Events())
            }

            SidebarSection("Workloads", collapsed) {
                SidebarItem(Res.drawable.view_in_ar_filled, "Pods", currentScreen is Screen.Main.Pods, collapsed) { onNavigate(Screen.Main.Pods()) }
                SidebarItem(Res.drawable.layers_filled, "Deployments", currentScreen is Screen.Main.Deployments, collapsed) { onNavigate(Screen.Main.Deployments) }
                SidebarItem(Res.drawable.storage_filled, "StatefulSets", currentScreen is Screen.Main.StatefulSets, collapsed) { onNavigate(Screen.Main.StatefulSets) }
                SidebarItem(Res.drawable.dynamic_feed_filled, "DaemonSets", currentScreen is Screen.Main.DaemonSets, collapsed) { onNavigate(Screen.Main.DaemonSets) }
                SidebarItem(Res.drawable.content_copy_filled, "ReplicaSets", currentScreen is Screen.Main.ReplicaSets, collapsed) { onNavigate(Screen.Main.ReplicaSets) }
                SidebarItem(Res.drawable.work_filled, "Jobs", currentScreen is Screen.Main.Jobs, collapsed) { onNavigate(Screen.Main.Jobs) }
                SidebarItem(Res.drawable.schedule_filled, "CronJobs", currentScreen is Screen.Main.CronJobs, collapsed) { onNavigate(Screen.Main.CronJobs) }
            }

            SidebarSection("Config", collapsed) {
                SidebarItem(Res.drawable.description_filled, "ConfigMaps", currentScreen is Screen.Main.ConfigMaps, collapsed) { onNavigate(Screen.Main.ConfigMaps) }
                SidebarItem(Res.drawable.lock_filled, "Secrets", currentScreen is Screen.Main.Secrets, collapsed) { onNavigate(Screen.Main.Secrets) }
            }

            SidebarSection("Network", collapsed) {
                SidebarItem(Res.drawable.cloud_filled, "Services", currentScreen is Screen.Main.Services, collapsed) { onNavigate(Screen.Main.Services) }
                SidebarItem(Res.drawable.language_filled, "Ingresses", currentScreen is Screen.Main.Ingresses, collapsed) { onNavigate(Screen.Main.Ingresses) }
                SidebarItem(Res.drawable.settings_ethernet_filled, "Endpoints", currentScreen is Screen.Main.Endpoints, collapsed) { onNavigate(Screen.Main.Endpoints) }
                SidebarItem(Res.drawable.security_filled, "Network Policies", currentScreen is Screen.Main.NetworkPolicies, collapsed) { onNavigate(Screen.Main.NetworkPolicies) }
            }

            SidebarSection("Storage", collapsed) {
                SidebarItem(Res.drawable.save_filled, "Persistent Volumes", currentScreen is Screen.Main.PersistentVolumes, collapsed) { onNavigate(Screen.Main.PersistentVolumes) }
                SidebarItem(Res.drawable.folder_open_filled, "PV Claims", currentScreen is Screen.Main.PersistentVolumeClaims, collapsed) { onNavigate(Screen.Main.PersistentVolumeClaims) }
                SidebarItem(Res.drawable.list_filled, "Storage Classes", currentScreen is Screen.Main.StorageClasses, collapsed) { onNavigate(Screen.Main.StorageClasses) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarItem(
    icon: DrawableResource,
    label: String,
    selected: Boolean,
    collapsed: Boolean = false,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        selected -> KdSelected
        hovered -> KdHover
        else -> Color.Transparent
    }

    val row: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = if (collapsed) 4.dp else 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .kdFocusRing()
                .background(bg)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false },
        ) {
            // Selection indicator: a 3 dp primary bar hugging the leading
            // edge of the row. Visible in both collapsed and expanded modes
            // so the active page is identifiable at a glance.
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .height(18.dp)
                        .width(3.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(KdPrimary),
                )
            }
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = if (collapsed) 0.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = if (collapsed) label else null,
                    modifier = Modifier.size(16.dp),
                    tint = if (selected) KdPrimary else KdTextSecondary,
                )
                if (!collapsed) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) KdTextPrimary else KdTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (collapsed) {
        TooltipArea(
            tooltip = { SidebarItemTooltip(label) },
            tooltipPlacement = TooltipPlacement.ComponentRect(
                anchor = Alignment.CenterEnd,
                alignment = Alignment.CenterEnd,
                offset = DpOffset(8.dp, 0.dp),
            ),
            content = row,
        )
    } else {
        row()
    }
}

@Composable
private fun SidebarItemTooltip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
        )
    }
}

@Composable
fun SidebarSection(
    title: String,
    collapsed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!collapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(if (expanded) Res.drawable.expand_more_filled else Res.drawable.chevron_right_filled),
                    contentDescription = if (expanded) "Collapse section" else "Expand section",
                    modifier = Modifier.size(14.dp),
                    tint = KdTextSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }
        }

        AnimatedVisibility(visible = collapsed || expanded) {
            Column(content = content)
        }
    }
}
