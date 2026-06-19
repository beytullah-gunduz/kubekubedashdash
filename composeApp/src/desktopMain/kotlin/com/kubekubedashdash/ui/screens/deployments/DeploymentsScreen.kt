package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.ResourceListScaffold
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.components.rememberResourceFilter
import com.kubekubedashdash.ui.screens.cluster.viewmodel.deploymentDegraded
import com.kubekubedashdash.ui.screens.deployments.viewmodel.DeploymentsScreenViewModel

@Composable
fun DeploymentsScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    onNavigate: (Screen) -> Unit,
    // When true, hide deployments at quorum on entry. The user can clear
    // via the chip that appears above the table once active. Driven by the
    // DEPLOYMENTS_DEGRADED banner click.
    initialDegradedOnly: Boolean = false,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: DeploymentsScreenViewModel = viewModel { DeploymentsScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var degradedOnly by rememberSaveable { mutableStateOf(initialDegradedOnly) }
    LaunchedEffect(initialDegradedOnly) {
        // Re-applies the seed if the user clicks the banner again after
        // having cleared the chip. Without this, navigating from
        // banner → here twice in a row only filters the first time.
        if (initialDegradedOnly) degradedOnly = true
    }
    var pendingDelete by remember { mutableStateOf<DeploymentInfo?>(null) }
    val delete = rememberConfirmableAction()

    ResourceListScaffold(state, loading = { ResourceLoadingIndicator() }) { data ->
        val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
        val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
        val filtered = rememberResourceFilter(
            data,
            searchQuery,
            labelSelector,
            annotationSelector,
            degradedOnly,
        ) { dep, q, labels, anns ->
            val passesSearch = q.isBlank() ||
                dep.name.contains(q, ignoreCase = true) ||
                dep.namespace.contains(q, ignoreCase = true)
            val passesLabels = labels.isEmpty() || matchesMapSelector(dep.labels, labels)
            val passesAnnotations = anns.isEmpty() || matchesMapSelector(dep.annotations, anns)
            val passesDegraded = !degradedOnly || deploymentDegraded(dep)
            passesSearch && passesLabels && passesAnnotations && passesDegraded
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ResourceCountHeader(
                count = filtered.size,
                kind = "Deployments",
                actions = { compact ->
                    ResourceFilterChips(
                        labelQuery = labelQuery,
                        onLabelQueryChange = onLabelQueryChange,
                        annotationQuery = annotationQuery,
                        onAnnotationQueryChange = onAnnotationQueryChange,
                        compact = compact,
                        pulseLabelsOnEntry = pulseLabelsOnEntry,
                        pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                        clearVisible = labelQuery.isNotBlank() || annotationQuery.isNotBlank() || degradedOnly,
                        onClear = {
                            onLabelQueryChange("")
                            onAnnotationQueryChange("")
                            degradedOnly = false
                        },
                    )
                },
            )
            DeploymentTable(
                deployments = filtered,
                selectedUid = selectedUid,
                onClick = { dep ->
                    selectedUid = dep.uid
                    onNavigate(Screen.Detail.DeploymentDetail(dep))
                },
                onDelete = { dep ->
                    pendingDelete = dep
                    delete.clearError()
                },
            )
        }
    }

    pendingDelete?.let { dep ->
        DeleteConfirmDialog(
            kind = "Deployment",
            name = dep.name,
            namespace = dep.namespace,
            inFlight = delete.inFlight,
            errorMessage = delete.error,
            body = "Delete Deployment \"${dep.name}\" in namespace ${dep.namespace}? " +
                "Pods owned by this Deployment will be deleted as well. This cannot be undone.",
            onConfirm = {
                delete.run(
                    failureMessage = "Delete failed",
                    block = { reactiveClient.actions.deleteResource("deployment", dep.name, dep.namespace) },
                    onSuccess = { pendingDelete = null },
                )
            },
            onDismiss = {
                pendingDelete = null
                delete.clearError()
            },
        )
    }
}
