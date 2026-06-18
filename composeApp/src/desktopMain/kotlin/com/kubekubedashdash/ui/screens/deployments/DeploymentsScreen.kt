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
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
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

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = remember(s.data, searchQuery, labelSelector, annotationSelector, degradedOnly) {
                s.data.filter { dep ->
                    val passesSearch = searchQuery.isBlank() ||
                        dep.name.contains(searchQuery, ignoreCase = true) ||
                        dep.namespace.contains(searchQuery, ignoreCase = true)
                    val passesLabels = labelSelector.isEmpty() || matchesMapSelector(dep.labels, labelSelector)
                    val passesAnnotations = annotationSelector.isEmpty() ||
                        matchesMapSelector(dep.annotations, annotationSelector)
                    val passesDegraded = !degradedOnly || deploymentDegraded(dep)
                    passesSearch && passesLabels && passesAnnotations && passesDegraded
                }
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
