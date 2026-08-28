package com.kubekubedashdash.ui.screens.services

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
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceFilterChips
import com.kubekubedashdash.ui.components.ResourceListScaffold
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.components.rememberResourceFilter
import com.kubekubedashdash.ui.screens.services.viewmodel.ServicesScreenViewModel

@Composable
fun ServicesScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    pulseLabelsOnEntry: Boolean = false,
    pulseAnnotationsOnEntry: Boolean = false,
    onNavigate: (Screen) -> Unit,
    // Seeds the row highlight when the screen is (re)created while its
    // detail pane is already open (Back/Forward restore, tab switch).
    initialSelectedUid: String? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: ServicesScreenViewModel = viewModel { ServicesScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf(initialSelectedUid) }

    ResourceListScaffold(state) { data ->
        val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
        val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
        val filtered = rememberResourceFilter(data, searchQuery, labelSelector, annotationSelector) { svc, q, labels, anns ->
            val passesSearch = q.isBlank() ||
                svc.name.contains(q, ignoreCase = true) ||
                svc.namespace.contains(q, ignoreCase = true) ||
                svc.type.contains(q, ignoreCase = true)
            val passesLabels = labels.isEmpty() || matchesMapSelector(svc.labels, labels)
            val passesAnnotations = anns.isEmpty() || matchesMapSelector(svc.annotations, anns)
            passesSearch && passesLabels && passesAnnotations
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ResourceCountHeader(
                count = filtered.size,
                kind = "Services",
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
            ServiceTable(
                services = filtered,
                selectedUid = selectedUid,
                onClick = { svc ->
                    selectedUid = svc.uid
                    onNavigate(Screen.Detail.ServiceDetail(svc))
                },
            )
        }
    }
}
