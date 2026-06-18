package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.layers_filled
import com.kubekubedashdash.resources.rotate_right_filled
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ConfirmActionDialog
import com.kubekubedashdash.ui.components.ScaleDialog
import com.kubekubedashdash.ui.screens.DetailAction
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ExtraTab
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val client = LocalReactiveKubeClient.current
    val scope = rememberCoroutineScope()

    var showScaleDialog by remember(deployment.uid) { mutableStateOf(false) }
    var scaleInFlight by remember(deployment.uid) { mutableStateOf(false) }
    var scaleError by remember(deployment.uid) { mutableStateOf<String?>(null) }

    var showRestartDialog by remember(deployment.uid) { mutableStateOf(false) }
    var restartInFlight by remember(deployment.uid) { mutableStateOf(false) }
    var restartError by remember(deployment.uid) { mutableStateOf<String?>(null) }

    val readyParts = deployment.ready.split("/")
    val isReady = readyParts.size == 2 && readyParts[0] == readyParts[1] && readyParts[0] != "0"
    val currentReplicas = readyParts.getOrNull(1)?.toIntOrNull() ?: 1

    val deploymentActions = listOf(
        DetailAction(
            label = "Scale",
            icon = Res.drawable.layers_filled,
            destructive = false,
            description = "Set how many replica pods run — scale up for more traffic, down to save resources.",
            enabled = !scaleInFlight,
            onClick = {
                showScaleDialog = true
                scaleError = null
            },
        ),
        DetailAction(
            label = "Rollout Restart",
            icon = Res.drawable.rotate_right_filled,
            destructive = false,
            description = "Recreate all pods in a rolling update — to pick up new config or secrets, with no downtime.",
            enabled = !restartInFlight,
            onClick = {
                showRestartDialog = true
                restartError = null
            },
        ),
    )

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
        actions = deploymentActions,
    )

    if (showScaleDialog) {
        ScaleDialog(
            name = deployment.name,
            currentReplicas = currentReplicas,
            inFlight = scaleInFlight,
            errorMessage = scaleError,
            onConfirm = { replicas ->
                scaleInFlight = true
                scaleError = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        client.actions.scaleWorkload(
                            kind = "Deployment",
                            name = deployment.name,
                            namespace = deployment.namespace,
                            replicas = replicas,
                        )
                    }
                    scaleInFlight = false
                    result.fold(
                        onSuccess = { showScaleDialog = false },
                        onFailure = { scaleError = it.message ?: "Scale failed" },
                    )
                }
            },
            onDismiss = {
                showScaleDialog = false
                scaleError = null
            },
        )
    }

    if (showRestartDialog) {
        ConfirmActionDialog(
            title = "Rollout Restart",
            body = "Restart all pods of Deployment \"${deployment.name}\"? Pods are recreated in a rolling update.",
            confirmLabel = "Restart",
            destructive = false,
            inFlight = restartInFlight,
            errorMessage = restartError,
            onConfirm = {
                restartInFlight = true
                restartError = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        client.actions.restartWorkload(
                            kind = "Deployment",
                            name = deployment.name,
                            namespace = deployment.namespace,
                        )
                    }
                    restartInFlight = false
                    result.fold(
                        onSuccess = { showRestartDialog = false },
                        onFailure = { restartError = it.message ?: "Restart failed" },
                    )
                }
            },
            onDismiss = {
                showRestartDialog = false
                restartError = null
            },
        )
    }
}
