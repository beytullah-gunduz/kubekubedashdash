package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.screens.cluster.viewmodel.deploymentDegraded
import com.kubekubedashdash.ui.screens.deployments.viewmodel.DeploymentsScreenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<DeploymentInfo?>(null) }
    var deleteInFlight by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

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
                        LabelSelectorChip(
                            query = labelQuery,
                            onQueryChange = onLabelQueryChange,
                            modifier = Modifier.padding(end = 8.dp),
                            pulseOnEntry = pulseLabelsOnEntry,
                            compact = compact,
                        )
                        AnnotationSelectorChip(
                            query = annotationQuery,
                            onQueryChange = onAnnotationQueryChange,
                            modifier = Modifier.padding(end = 8.dp),
                            pulseOnEntry = pulseAnnotationsOnEntry,
                            compact = compact,
                        )
                        AnimatedVisibility(
                            visible = labelQuery.isNotBlank() || annotationQuery.isNotBlank() || degradedOnly,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut(),
                        ) {
                            ClearFiltersChip(
                                onClick = {
                                    onLabelQueryChange("")
                                    onAnnotationQueryChange("")
                                    degradedOnly = false
                                },
                                modifier = Modifier.padding(end = 8.dp),
                                compact = compact,
                            )
                        }
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
                        deleteError = null
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
            inFlight = deleteInFlight,
            errorMessage = deleteError,
            body = "Delete Deployment \"${dep.name}\" in namespace ${dep.namespace}? " +
                "Pods owned by this Deployment will be deleted as well. This cannot be undone.",
            onConfirm = {
                deleteInFlight = true
                deleteError = null
                scope.launch(Dispatchers.IO) {
                    val result = reactiveClient.deleteResource("deployment", dep.name, dep.namespace)
                    deleteInFlight = false
                    result.fold(
                        onSuccess = { pendingDelete = null },
                        onFailure = { deleteError = it.message ?: "Delete failed" },
                    )
                }
            },
            onDismiss = {
                pendingDelete = null
                deleteError = null
            },
        )
    }
}
