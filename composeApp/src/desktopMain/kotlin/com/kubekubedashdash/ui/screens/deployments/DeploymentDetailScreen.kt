package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ExtraTab
import com.kubekubedashdash.ui.screens.ResourceDetailPanel

@Composable
fun DeploymentDetailScreen(
    deployment: DeploymentInfo,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
) {
    val readyParts = deployment.ready.split("/")
    val isReady = readyParts.size == 2 && readyParts[0] == readyParts[1] && readyParts[0] != "0"

    ResourceDetailPanel(
        kind = "Deployment",
        name = deployment.name,
        namespace = deployment.namespace,
        status = if (isReady) "Available" else "Progressing",
        fields = listOf(
            DetailField("Namespace", deployment.namespace),
            DetailField("Ready", deployment.ready, if (isReady) KdSuccess else KdWarning),
            DetailField("Up-to-date", "${deployment.upToDate}"),
            DetailField("Available", "${deployment.available}", if (deployment.available > 0) KdSuccess else KdWarning),
            DetailField("Strategy", deployment.strategy),
            DetailField("Age", deployment.age),
            *deployment.conditions.map { DetailField("Condition", it) }.toTypedArray(),
        ),
        labels = deployment.labels,
        onClose = onClose,
        modifier = Modifier.fillMaxSize(),
        extraTabs = listOf(
            ExtraTab(
                label = "Graph",
                icon = Icons.Default.AccountTree,
            ) {
                DeploymentResourceGraphTab(
                    deploymentName = deployment.name,
                    namespace = deployment.namespace,
                )
            },
        ),
    )
}
