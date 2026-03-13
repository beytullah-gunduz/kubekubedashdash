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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.ui.graphics.vector.ImageVector
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(
    currentScreen: Screen,
    selectedContext: String,
    isConnected: Boolean,
    onNavigate: (Screen) -> Unit,
    onClusterSelectorClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KdSidebarBg),
    ) {
        ClusterHeader(selectedContext, isConnected, onClusterSelectorClick)

        HorizontalDivider(color = KdBorder, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            SidebarItem(Icons.Default.Dashboard, "Cluster", currentScreen is Screen.Main.ClusterOverview) {
                onNavigate(Screen.Main.ClusterOverview)
            }
            SidebarItem(Icons.Default.Dns, "Nodes", currentScreen is Screen.Main.Nodes) {
                onNavigate(Screen.Main.Nodes())
            }
            SidebarItem(Icons.Default.FolderSpecial, "Namespaces", currentScreen is Screen.Main.Namespaces) {
                onNavigate(Screen.Main.Namespaces)
            }
            SidebarItem(Icons.Default.Notifications, "Events", currentScreen is Screen.Main.Events) {
                onNavigate(Screen.Main.Events)
            }

            SidebarSection("Workloads") {
                SidebarItem(Icons.Default.ViewInAr, "Pods", currentScreen is Screen.Main.Pods) { onNavigate(Screen.Main.Pods()) }
                SidebarItem(Icons.Default.Layers, "Deployments", currentScreen is Screen.Main.Deployments) { onNavigate(Screen.Main.Deployments) }
                SidebarItem(Icons.Default.Storage, "StatefulSets", currentScreen is Screen.Main.StatefulSets) { onNavigate(Screen.Main.StatefulSets) }
                SidebarItem(Icons.Default.DynamicFeed, "DaemonSets", currentScreen is Screen.Main.DaemonSets) { onNavigate(Screen.Main.DaemonSets) }
                SidebarItem(Icons.Default.ContentCopy, "ReplicaSets", currentScreen is Screen.Main.ReplicaSets) { onNavigate(Screen.Main.ReplicaSets) }
                SidebarItem(Icons.Default.Work, "Jobs", currentScreen is Screen.Main.Jobs) { onNavigate(Screen.Main.Jobs) }
                SidebarItem(Icons.Default.Schedule, "CronJobs", currentScreen is Screen.Main.CronJobs) { onNavigate(Screen.Main.CronJobs) }
            }

            SidebarSection("Config") {
                SidebarItem(Icons.Default.Description, "ConfigMaps", currentScreen is Screen.Main.ConfigMaps) { onNavigate(Screen.Main.ConfigMaps) }
                SidebarItem(Icons.Default.Lock, "Secrets", currentScreen is Screen.Main.Secrets) { onNavigate(Screen.Main.Secrets) }
            }

            SidebarSection("Network") {
                SidebarItem(Icons.Default.Cloud, "Services", currentScreen is Screen.Main.Services) { onNavigate(Screen.Main.Services) }
                SidebarItem(Icons.Default.Language, "Ingresses", currentScreen is Screen.Main.Ingresses) { onNavigate(Screen.Main.Ingresses) }
                SidebarItem(Icons.Default.SettingsEthernet, "Endpoints", currentScreen is Screen.Main.Endpoints) { onNavigate(Screen.Main.Endpoints) }
                SidebarItem(Icons.Default.Security, "Network Policies", currentScreen is Screen.Main.NetworkPolicies) { onNavigate(Screen.Main.NetworkPolicies) }
            }

            SidebarSection("Storage") {
                SidebarItem(Icons.Default.Save, "Persistent Volumes", currentScreen is Screen.Main.PersistentVolumes) { onNavigate(Screen.Main.PersistentVolumes) }
                SidebarItem(Icons.Default.FolderOpen, "PV Claims", currentScreen is Screen.Main.PersistentVolumeClaims) { onNavigate(Screen.Main.PersistentVolumeClaims) }
                SidebarItem(Icons.AutoMirrored.Filled.List, "Storage Classes", currentScreen is Screen.Main.StorageClasses) { onNavigate(Screen.Main.StorageClasses) }
            }
        }

        HorizontalDivider(color = KdBorder, thickness = 1.dp)

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            SidebarItem(Icons.AutoMirrored.Filled.List, "Logs", currentScreen is Screen.Main.Logs) {
                onNavigate(Screen.Main.Logs)
            }
            SidebarItem(Icons.Default.Settings, "Settings", currentScreen is Screen.Main.Settings) {
                onNavigate(Screen.Main.Settings)
            }
        }
    }
}

@Composable
private fun ClusterHeader(
    selectedContext: String,
    isConnected: Boolean,
    onClusterSelectorClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "KubeKubeDashDash",
                        style = MaterialTheme.typography.labelLarge,
                        color = KdTextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) KdSuccess else KdError),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.labelSmall,
                            color = KdTextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onClusterSelectorClick,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
                border = BorderStroke(1.dp, KdBorder),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(Icons.Default.SwapHoriz, null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    selectedContext.ifEmpty { "No context" },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ExpandMore, null, Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarItem(
    icon: ImageVector,
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
            icon,
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
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
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
