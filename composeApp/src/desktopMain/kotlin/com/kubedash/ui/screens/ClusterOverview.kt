package com.kubedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubedash.ClusterInfo
import com.kubedash.KdBorder
import com.kubedash.KdError
import com.kubedash.KdInfo
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdSurface
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import com.kubedash.Screen
import com.kubedash.ui.PodStatusBar
import com.kubedash.ui.ResourceErrorMessage
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.SummaryCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ClusterOverviewScreen(
    kubeClient: KubeClient,
    namespace: String?,
    onNavigate: (Screen) -> Unit,
) {
    var state by remember { mutableStateOf<ResourceState<ClusterInfo>>(ResourceState.Loading) }

    LaunchedEffect(namespace) {
        state = ResourceState.Loading
        while (true) {
            state = try {
                val info = withContext(Dispatchers.IO) { kubeClient.getClusterInfo(namespace) }
                ResourceState.Success(info)
            } catch (e: Exception) {
                if (state is ResourceState.Loading) {
                    ResourceState.Error(e.message ?: "Unknown error")
                } else {
                    state
                }
            }
            delay(10_000)
        }
    }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()
        is ResourceState.Error -> ResourceErrorMessage(s.message)
        is ResourceState.Success -> OverviewContent(s.data, onNavigate)
    }
}

@Composable
private fun OverviewContent(info: ClusterInfo, onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            "Cluster Overview",
            style = MaterialTheme.typography.headlineMedium,
            color = KdTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(info.name, style = MaterialTheme.typography.bodyMedium, color = KdPrimary)
            Spacer(Modifier.width(12.dp))
            Text("v${info.version}", style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
            Spacer(Modifier.width(12.dp))
            Text(info.server, style = MaterialTheme.typography.labelMedium, color = KdTextSecondary)
        }

        Spacer(Modifier.height(24.dp))

        // Summary cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard("Nodes", "${info.nodesCount}", Icons.Default.Dns, KdPrimary, Modifier.weight(1f))
            SummaryCard("Namespaces", "${info.namespacesCount}", Icons.Default.FolderSpecial, KdInfo, Modifier.weight(1f))
            SummaryCard("Pods", "${info.podsCount}", Icons.Default.ViewInAr, KdSuccess, Modifier.weight(1f))
            SummaryCard("Deployments", "${info.deploymentsCount}", Icons.Default.Layers, KdWarning, Modifier.weight(1f))
            SummaryCard("Services", "${info.servicesCount}", Icons.Default.Cloud, Color(0xFF9C27B0), Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        // Pod status
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = KdSurface,
            border = ButtonDefaults.outlinedButtonBorder(true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Pod Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(16.dp))
                PodStatusBar(info.runningPods, info.pendingPods, info.failedPods, info.succeededPods)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    StatusLegend("Running", info.runningPods, KdSuccess)
                    StatusLegend("Pending", info.pendingPods, KdWarning)
                    StatusLegend("Failed", info.failedPods, KdError)
                    StatusLegend("Succeeded", info.succeededPods, KdInfo)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Quick navigation
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = KdSurface,
            border = ButtonDefaults.outlinedButtonBorder(true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Quick Navigation",
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QuickNavButton("Pods", Icons.Default.ViewInAr, Modifier.weight(1f)) { onNavigate(Screen.Pods) }
                    QuickNavButton("Deployments", Icons.Default.Layers, Modifier.weight(1f)) { onNavigate(Screen.Deployments) }
                    QuickNavButton("Services", Icons.Default.Cloud, Modifier.weight(1f)) { onNavigate(Screen.Services) }
                    QuickNavButton("Nodes", Icons.Default.Dns, Modifier.weight(1f)) { onNavigate(Screen.Nodes) }
                    QuickNavButton("Events", Icons.Default.Notifications, Modifier.weight(1f)) { onNavigate(Screen.Events) }
                }
            }
        }
    }
}

@Composable
private fun StatusLegend(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$label: $count",
            style = MaterialTheme.typography.bodySmall,
            color = KdTextSecondary,
        )
    }
}

@Composable
private fun QuickNavButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
        ),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = KdPrimary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
