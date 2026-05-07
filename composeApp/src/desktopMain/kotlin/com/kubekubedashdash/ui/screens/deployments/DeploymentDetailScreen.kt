package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ExtraTab
import com.kubekubedashdash.ui.screens.ResourceDetailPanel

@Composable
fun DeploymentDetailScreen(
    deployment: DeploymentInfo,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    labelQuery: String = "",
    onToggleLabel: (String, String) -> Unit = { _, _ -> },
    annotationQuery: String = "",
    onToggleAnnotation: (String, String) -> Unit = { _, _ -> },
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
        annotations = deployment.annotations,
        onClose = onClose,
        modifier = Modifier.fillMaxSize(),
        extraTabs = listOf(
            ExtraTab(
                label = "Graph",
                icon = Res.drawable.account_tree_filled,
            ) {
                DeploymentResourceGraphTab(
                    deploymentName = deployment.name,
                    namespace = deployment.namespace,
                )
            },
        ),
        labelQuery = labelQuery,
        onToggleLabel = onToggleLabel,
        annotationQuery = annotationQuery,
        onToggleAnnotation = onToggleAnnotation,
    )
}
