package com.kubekubedashdash.ui.screens.namespaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.ResourceListScaffold
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberConfirmableAction
import com.kubekubedashdash.ui.components.rememberResourceFilter
import com.kubekubedashdash.ui.screens.namespaces.viewmodel.NamespacesScreenViewModel

@Composable
fun NamespacesScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    onNavigate: (Screen) -> Unit,
    onCaptureLogs: (String) -> Unit = {},
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: NamespacesScreenViewModel = viewModel { NamespacesScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<GenericResourceInfo?>(null) }
    val delete = rememberConfirmableAction()

    ResourceListScaffold(state) { data ->
        val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
        val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
        val filtered = rememberResourceFilter(data, searchQuery, labelSelector, annotationSelector) { ns, q, labels, anns ->
            val passesSearch = q.isBlank() ||
                ns.name.contains(q, ignoreCase = true) ||
                (ns.status?.contains(q, ignoreCase = true) ?: false)
            val passesLabels = labels.isEmpty() || matchesMapSelector(ns.labels, labels)
            val passesAnnotations = anns.isEmpty() || matchesMapSelector(ns.annotations, anns)
            passesSearch && passesLabels && passesAnnotations
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ResourceCountHeader(
                count = filtered.size,
                kind = "Namespaces",
                liveDot = {
                    LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                },
                actions = { compact ->
                    ResourceFilterChips(
                        labelQuery = labelQuery,
                        onLabelQueryChange = onLabelQueryChange,
                        annotationQuery = annotationQuery,
                        onAnnotationQueryChange = onAnnotationQueryChange,
                        compact = compact,
                        pulseLabelsOnEntry = pulseLabelsOnEntry,
                        pulseAnnotationsOnEntry = pulseAnnotationsOnEntry,
                    )
                },
            )
            NamespaceTable(
                namespaces = filtered,
                selectedUid = selectedUid,
                onClick = { ns ->
                    selectedUid = ns.uid
                    onNavigate(Screen.Detail.NamespaceDetail(ns))
                },
                onDelete = { ns ->
                    pendingDelete = ns
                    delete.clearError()
                },
                onCaptureLogs = { ns -> onCaptureLogs(ns.name) },
            )
        }
    }

    pendingDelete?.let { ns ->
        DeleteConfirmDialog(
            kind = "Namespace",
            name = ns.name,
            namespace = null,
            requireTypedConfirm = true,
            inFlight = delete.inFlight,
            errorMessage = delete.error,
            body = "Delete Namespace \"${ns.name}\"? This deletes every resource in the namespace " +
                "and may stay in Terminating for a while if any resource has stuck finalizers. " +
                "This cannot be undone.",
            onConfirm = {
                delete.run(
                    failureMessage = "Delete failed",
                    block = { reactiveClient.actions.deleteResource("namespace", ns.name, namespace = null) },
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
