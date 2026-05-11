package com.kubekubedashdash.ui.screens.services

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
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
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: ServicesScreenViewModel = viewModel { ServicesScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = s.data.filter { svc ->
                val passesSearch = searchQuery.isBlank() ||
                    svc.name.contains(searchQuery, ignoreCase = true) ||
                    svc.namespace.contains(searchQuery, ignoreCase = true) ||
                    svc.type.contains(searchQuery, ignoreCase = true)
                val passesLabels = labelSelector.isEmpty() || matchesMapSelector(svc.labels, labelSelector)
                val passesAnnotations = annotationSelector.isEmpty() ||
                    matchesMapSelector(svc.annotations, annotationSelector)
                passesSearch && passesLabels && passesAnnotations
            }

            Column(modifier = Modifier.fillMaxSize()) {
                ResourceCountHeader(
                    count = filtered.size,
                    kind = "Services",
                    liveDot = {
                        LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                    },
                    actions = {
                        LabelSelectorChip(
                            query = labelQuery,
                            onQueryChange = onLabelQueryChange,
                            modifier = Modifier.padding(end = 8.dp),
                            pulseOnEntry = pulseLabelsOnEntry,
                        )
                        AnnotationSelectorChip(
                            query = annotationQuery,
                            onQueryChange = onAnnotationQueryChange,
                            modifier = Modifier.padding(end = 8.dp),
                            pulseOnEntry = pulseAnnotationsOnEntry,
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
                            )
                        }
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
}
