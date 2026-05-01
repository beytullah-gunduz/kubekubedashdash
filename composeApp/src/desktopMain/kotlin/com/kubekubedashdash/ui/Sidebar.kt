package com.kubekubedashdash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdHover
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSelected
import com.kubekubedashdash.KdSidebarBg
import com.kubekubedashdash.KdSuccess
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
import com.kubekubedashdash.resources.settings_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import com.kubekubedashdash.resources.view_in_ar_filled
import com.kubekubedashdash.resources.work_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
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
            SidebarItem(Res.drawable.dashboard_filled, "Cluster", currentScreen is Screen.Main.ClusterOverview) {
                onNavigate(Screen.Main.ClusterOverview)
            }
            SidebarItem(Res.drawable.dns_filled, "Nodes", currentScreen is Screen.Main.Nodes) {
                onNavigate(Screen.Main.Nodes())
            }
            SidebarItem(Res.drawable.folder_special_filled, "Namespaces", currentScreen is Screen.Main.Namespaces) {
                onNavigate(Screen.Main.Namespaces)
            }
            SidebarItem(Res.drawable.notifications_filled, "Events", currentScreen is Screen.Main.Events) {
                onNavigate(Screen.Main.Events())
            }

            SidebarSection("Workloads") {
                SidebarItem(Res.drawable.view_in_ar_filled, "Pods", currentScreen is Screen.Main.Pods) { onNavigate(Screen.Main.Pods()) }
                SidebarItem(Res.drawable.layers_filled, "Deployments", currentScreen is Screen.Main.Deployments) { onNavigate(Screen.Main.Deployments) }
                SidebarItem(Res.drawable.storage_filled, "StatefulSets", currentScreen is Screen.Main.StatefulSets) { onNavigate(Screen.Main.StatefulSets) }
                SidebarItem(Res.drawable.dynamic_feed_filled, "DaemonSets", currentScreen is Screen.Main.DaemonSets) { onNavigate(Screen.Main.DaemonSets) }
                SidebarItem(Res.drawable.content_copy_filled, "ReplicaSets", currentScreen is Screen.Main.ReplicaSets) { onNavigate(Screen.Main.ReplicaSets) }
                SidebarItem(Res.drawable.work_filled, "Jobs", currentScreen is Screen.Main.Jobs) { onNavigate(Screen.Main.Jobs) }
                SidebarItem(Res.drawable.schedule_filled, "CronJobs", currentScreen is Screen.Main.CronJobs) { onNavigate(Screen.Main.CronJobs) }
            }

            SidebarSection("Config") {
                SidebarItem(Res.drawable.description_filled, "ConfigMaps", currentScreen is Screen.Main.ConfigMaps) { onNavigate(Screen.Main.ConfigMaps) }
                SidebarItem(Res.drawable.lock_filled, "Secrets", currentScreen is Screen.Main.Secrets) { onNavigate(Screen.Main.Secrets) }
            }

            SidebarSection("Network") {
                SidebarItem(Res.drawable.cloud_filled, "Services", currentScreen is Screen.Main.Services) { onNavigate(Screen.Main.Services) }
                SidebarItem(Res.drawable.language_filled, "Ingresses", currentScreen is Screen.Main.Ingresses) { onNavigate(Screen.Main.Ingresses) }
                SidebarItem(Res.drawable.settings_ethernet_filled, "Endpoints", currentScreen is Screen.Main.Endpoints) { onNavigate(Screen.Main.Endpoints) }
                SidebarItem(Res.drawable.security_filled, "Network Policies", currentScreen is Screen.Main.NetworkPolicies) { onNavigate(Screen.Main.NetworkPolicies) }
            }

            SidebarSection("Storage") {
                SidebarItem(Res.drawable.save_filled, "Persistent Volumes", currentScreen is Screen.Main.PersistentVolumes) { onNavigate(Screen.Main.PersistentVolumes) }
                SidebarItem(Res.drawable.folder_open_filled, "PV Claims", currentScreen is Screen.Main.PersistentVolumeClaims) { onNavigate(Screen.Main.PersistentVolumeClaims) }
                SidebarItem(Res.drawable.list_filled, "Storage Classes", currentScreen is Screen.Main.StorageClasses) { onNavigate(Screen.Main.StorageClasses) }
            }
        }

        HorizontalDivider(color = KdBorder, thickness = 1.dp)

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            SidebarItem(Res.drawable.settings_filled, "Settings", currentScreen is Screen.Main.Settings) {
                onNavigate(Screen.Main.Settings)
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
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        selected -> KdSelected
        hovered -> KdHover
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (selected) KdPrimary else KdTextSecondary,
        )
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

@Composable
fun SidebarSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(if (expanded) Res.drawable.expand_more_filled else Res.drawable.chevron_right_filled),
                contentDescription = null,
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

        AnimatedVisibility(visible = expanded) {
            Column(content = content)
        }
    }
}
