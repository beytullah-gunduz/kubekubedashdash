package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.BulkActionDialog
import com.kubekubedashdash.ui.components.BulkRunState
import com.kubekubedashdash.ui.components.BulkSelectionBar
import com.kubekubedashdash.ui.components.BulkVerb
import com.kubekubedashdash.ui.components.BulkVerbs
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
    // Ordering constraint (do not reorder): this reset effect must appear
    // lexically before the LaunchedEffect(visibleUids) below — Compose runs
    // launched effects in composition order, and a reset that lands after
    // setVisible empties the funnel's visible set and silently disables all
    // selection until the row set next changes.
    LaunchedEffect(viewModel) {
        viewModel.selection.reset()
        viewModel.bulkRunner.clear()
    }
    val state by viewModel.state.collectAsState()
    val selectedUids by viewModel.selection.selected.collectAsState()
    val bulkState by viewModel.bulkRunner.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var degradedOnly by rememberSaveable { mutableStateOf(initialDegradedOnly) }
    LaunchedEffect(initialDegradedOnly) {
        // Re-applies the seed if the user clicks the banner again after
        // having cleared the chip. Without this, navigating from
        // banner → here twice in a row only filters the first time.
        if (initialDegradedOnly) degradedOnly = true
    }
    var pendingDelete by remember { mutableStateOf<DeploymentInfo?>(null) }
    var bulkVerb by remember { mutableStateOf<BulkVerb?>(null) }
    var bulkItems by remember { mutableStateOf<List<DeploymentInfo>>(emptyList()) }
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

        val visibleUids = remember(filtered) { filtered.map { it.uid }.toSet() }
        LaunchedEffect(visibleUids) { viewModel.selection.setVisible(visibleUids) }

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
            // Exit-animation latch: the bar stays composed while it shrinks
            // away, so without holding the last non-zero count it would
            // flash "0 deployments selected" on every Clear.
            var lastSelectedCount by remember { mutableStateOf(0) }
            if (selectedUids.isNotEmpty()) lastSelectedCount = selectedUids.size
            AnimatedVisibility(selectedUids.isNotEmpty()) {
                BulkSelectionBar(
                    selectedCount = lastSelectedCount,
                    kind = "deployments",
                    onClear = { viewModel.selection.set(emptySet()) },
                ) {
                    TextButton(onClick = {
                        val snapshot = filtered.filter { it.uid in selectedUids }
                        viewModel.bulkRunner.armOrReattach(BulkVerbs.Restart, snapshot) { v, items ->
                            bulkItems = items
                            bulkVerb = v
                        }
                    }) { Text("Restart", color = KdTextPrimary) }
                    TextButton(onClick = {
                        val snapshot = filtered.filter { it.uid in selectedUids }
                        viewModel.bulkRunner.armOrReattach(BulkVerbs.Delete, snapshot) { v, items ->
                            bulkItems = items
                            bulkVerb = v
                        }
                    }) { Text("Delete", color = KdError) }
                }
            }
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
                selectedUids = selectedUids,
                onSelectionChange = viewModel.selection::set,
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

    bulkVerb?.let { verb ->
        val label: (DeploymentInfo) -> String = { "${it.namespace}/${it.name}" }
        BulkActionDialog(
            verb = verb,
            items = bulkItems,
            itemLabel = label,
            kindSingular = "Deployment",
            kindPlural = "Deployments",
            confirmBody = if (verb == BulkVerbs.Delete) {
                "Delete ${bulkItems.size} deployments? Pods owned by them will be deleted as well. " +
                    "This cannot be undone."
            } else {
                "Restart ${bulkItems.size} deployments? Each gets a rolling restart — pods are " +
                    "replaced gradually with no downtime for healthy workloads."
            },
            runState = bulkState,
            onConfirm = {
                viewModel.bulkRunner.start(verb, bulkItems, label) { dep ->
                    when (verb) {
                        BulkVerbs.Restart -> reactiveClient.actions.restartWorkload("deployment", dep.name, dep.namespace)

                        BulkVerbs.Delete -> reactiveClient.actions.deleteResource("deployment", dep.name, dep.namespace)

                        // Destructive verbs are named explicitly — a future verb
                        // must fail loudly here, never fall through to delete.
                        else -> Result.failure(IllegalStateException("Unsupported bulk verb: ${verb.actionLabel}"))
                    }
                }
            },
            onCancelRun = { viewModel.bulkRunner.cancel() },
            onDismiss = {
                val finished = bulkState as? BulkRunState.Finished
                if (finished != null) {
                    viewModel.selection.set(finished.failures.map { it.item.uid }.toSet())
                    viewModel.bulkRunner.clear()
                }
                bulkVerb = null
                bulkItems = emptyList()
            },
        )
    }
}
