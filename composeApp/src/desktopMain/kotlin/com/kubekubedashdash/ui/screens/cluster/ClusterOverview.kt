package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.PodStatusBar
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.SummaryCard
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterOverviewViewModel

@Composable
fun ClusterOverviewScreen(
    onNavigate: (Screen) -> Unit,
    viewModel: ClusterOverviewViewModel = viewModel { ClusterOverviewViewModel() },
) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        is ResourceState.Error -> ResourceErrorMessage(s.message)
        is ResourceState.Success -> OverviewContent(s.data, onNavigate)
        else -> ResourceLoadingIndicator()
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard("Nodes", "${info.nodesCount}", Icons.Default.Dns, KdPrimary, Modifier.weight(1f)) { onNavigate(Screen.Main.Nodes()) }
            SummaryCard("Namespaces", "${info.namespacesCount}", Icons.Default.FolderSpecial, KdInfo, Modifier.weight(1f)) { onNavigate(Screen.Main.Namespaces) }
            SummaryCard("Pods", "${info.podsCount}", Icons.Default.ViewInAr, KdSuccess, Modifier.weight(1f)) { onNavigate(Screen.Main.Pods()) }
            SummaryCard("Deployments", "${info.deploymentsCount}", Icons.Default.Layers, KdWarning, Modifier.weight(1f)) { onNavigate(Screen.Main.Deployments) }
            SummaryCard("Services", "${info.servicesCount}", Icons.Default.Cloud, Color(0xFF9C27B0), Modifier.weight(1f)) { onNavigate(Screen.Main.Services) }
        }

        Spacer(Modifier.height(24.dp))

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
    }
}
