package com.kubedash.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdHover
import com.kubedash.KdPrimary
import com.kubedash.KdSelected
import com.kubedash.KdSidebarBg
import com.kubedash.KdSuccess
import com.kubedash.KdSurface
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(
    currentScreen: Screen,
    selectedContext: String,
    contexts: List<String>,
    isConnected: Boolean,
    onNavigate: (Screen) -> Unit,
    onContextSwitch: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(KdSidebarBg),
    ) {
        ClusterHeader(selectedContext, contexts, isConnected, onContextSwitch)

        HorizontalDivider(color = KdBorder, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            SidebarItem(Icons.Default.Dashboard, "Cluster", currentScreen is Screen.ClusterOverview) {
                onNavigate(Screen.ClusterOverview)
            }
            SidebarItem(Icons.Default.Dns, "Nodes", currentScreen is Screen.Nodes) {
                onNavigate(Screen.Nodes)
            }
            SidebarItem(Icons.Default.FolderSpecial, "Namespaces", currentScreen is Screen.Namespaces) {
                onNavigate(Screen.Namespaces)
            }
            SidebarItem(Icons.Default.Notifications, "Events", currentScreen is Screen.Events) {
                onNavigate(Screen.Events)
            }

            SidebarSection("Workloads") {
                SidebarItem(Icons.Default.ViewInAr, "Pods", currentScreen is Screen.Pods) { onNavigate(Screen.Pods()) }
                SidebarItem(Icons.Default.Layers, "Deployments", currentScreen is Screen.Deployments) { onNavigate(Screen.Deployments) }
                SidebarItem(Icons.Default.Storage, "StatefulSets", currentScreen is Screen.StatefulSets) { onNavigate(Screen.StatefulSets) }
                SidebarItem(Icons.Default.DynamicFeed, "DaemonSets", currentScreen is Screen.DaemonSets) { onNavigate(Screen.DaemonSets) }
                SidebarItem(Icons.Default.ContentCopy, "ReplicaSets", currentScreen is Screen.ReplicaSets) { onNavigate(Screen.ReplicaSets) }
                SidebarItem(Icons.Default.Work, "Jobs", currentScreen is Screen.Jobs) { onNavigate(Screen.Jobs) }
                SidebarItem(Icons.Default.Schedule, "CronJobs", currentScreen is Screen.CronJobs) { onNavigate(Screen.CronJobs) }
            }

            SidebarSection("Config") {
                SidebarItem(Icons.Default.Description, "ConfigMaps", currentScreen is Screen.ConfigMaps) { onNavigate(Screen.ConfigMaps) }
                SidebarItem(Icons.Default.Lock, "Secrets", currentScreen is Screen.Secrets) { onNavigate(Screen.Secrets) }
            }

            SidebarSection("Network") {
                SidebarItem(Icons.Default.Cloud, "Services", currentScreen is Screen.Services) { onNavigate(Screen.Services) }
                SidebarItem(Icons.Default.Language, "Ingresses", currentScreen is Screen.Ingresses) { onNavigate(Screen.Ingresses) }
                SidebarItem(Icons.Default.SettingsEthernet, "Endpoints", currentScreen is Screen.Endpoints) { onNavigate(Screen.Endpoints) }
                SidebarItem(Icons.Default.Security, "Network Policies", currentScreen is Screen.NetworkPolicies) { onNavigate(Screen.NetworkPolicies) }
            }

            SidebarSection("Storage") {
                SidebarItem(Icons.Default.Save, "Persistent Volumes", currentScreen is Screen.PersistentVolumes) { onNavigate(Screen.PersistentVolumes) }
                SidebarItem(Icons.Default.FolderOpen, "PV Claims", currentScreen is Screen.PersistentVolumeClaims) { onNavigate(Screen.PersistentVolumeClaims) }
                SidebarItem(Icons.AutoMirrored.Filled.List, "Storage Classes", currentScreen is Screen.StorageClasses) { onNavigate(Screen.StorageClasses) }
            }
        }
    }
}

@Composable
private fun ClusterHeader(
    selectedContext: String,
    contexts: List<String>,
    isConnected: Boolean,
    onContextSwitch: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

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

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
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

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(KdSurface),
                ) {
                    contexts.forEach { ctx ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    ctx,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (ctx == selectedContext) KdPrimary else KdTextPrimary,
                                )
                            },
                            onClick = {
                                onContextSwitch(ctx)
                                expanded = false
                            },
                            leadingIcon = {
                                if (ctx == selectedContext) {
                                    Icon(Icons.Default.Check, null, tint = KdPrimary, modifier = Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
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
