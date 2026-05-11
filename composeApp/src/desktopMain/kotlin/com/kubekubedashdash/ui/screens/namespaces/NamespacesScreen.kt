package com.kubekubedashdash.ui.screens.namespaces

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.DeleteConfirmDialog
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.screens.namespaces.viewmodel.NamespacesScreenViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: NamespacesScreenViewModel = viewModel { NamespacesScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<GenericResourceInfo?>(null) }
    var deleteInFlight by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = s.data.filter { ns ->
                val passesSearch = searchQuery.isBlank() ||
                    ns.name.contains(searchQuery, ignoreCase = true) ||
                    (ns.status?.contains(searchQuery, ignoreCase = true) ?: false)
                val passesLabels = labelSelector.isEmpty() || matchesMapSelector(ns.labels, labelSelector)
                val passesAnnotations = annotationSelector.isEmpty() ||
                    matchesMapSelector(ns.annotations, annotationSelector)
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
                            visible = labelQuery.isNotBlank() || annotationQuery.isNotBlank(),
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut(),
                        ) {
                            ClearFiltersChip(
                                onClick = {
                                    onLabelQueryChange("")
                                    onAnnotationQueryChange("")
                                },
                                modifier = Modifier.padding(end = 8.dp),
                                compact = compact,
                            )
                        }
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
                        deleteError = null
                    },
                )
            }
        }
    }

    pendingDelete?.let { ns ->
        DeleteConfirmDialog(
            kind = "Namespace",
            name = ns.name,
            namespace = null,
            requireTypedConfirm = true,
            inFlight = deleteInFlight,
            errorMessage = deleteError,
            body = "Delete Namespace \"${ns.name}\"? This deletes every resource in the namespace " +
                "and may stay in Terminating for a while if any resource has stuck finalizers. " +
                "This cannot be undone.",
            onConfirm = {
                deleteInFlight = true
                deleteError = null
                scope.launch(Dispatchers.IO) {
                    val result = reactiveClient.deleteResource("namespace", ns.name, namespace = null)
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
