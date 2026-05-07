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
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.AnnotationSelectorChip
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.matchesMapSelector
import com.kubekubedashdash.ui.components.parseMapSelector
import com.kubekubedashdash.ui.screens.deployments.viewmodel.DeploymentsScreenViewModel

@Composable
fun DeploymentsScreen(
    searchQuery: String,
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: DeploymentsScreenViewModel = viewModel { DeploymentsScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    var selectedUid by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val labelSelector = remember(labelQuery) { parseMapSelector(labelQuery) }
            val annotationSelector = remember(annotationQuery) { parseMapSelector(annotationQuery) }
            val filtered = s.data.filter { dep ->
                val passesSearch = searchQuery.isBlank() ||
                    dep.name.contains(searchQuery, ignoreCase = true) ||
                    dep.namespace.contains(searchQuery, ignoreCase = true)
                val passesLabels = labelSelector.isEmpty() || matchesMapSelector(dep.labels, labelSelector)
                val passesAnnotations = annotationSelector.isEmpty() ||
                    matchesMapSelector(dep.annotations, annotationSelector)
                passesSearch && passesLabels && passesAnnotations
            }

            Column(modifier = Modifier.fillMaxSize()) {
                ResourceCountHeader(
                    count = filtered.size,
                    kind = "Deployments",
                    actions = {
                        LabelSelectorChip(
                            query = labelQuery,
                            onQueryChange = onLabelQueryChange,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        AnnotationSelectorChip(
                            query = annotationQuery,
                            onQueryChange = onAnnotationQueryChange,
                            modifier = Modifier.padding(end = 8.dp),
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
                DeploymentTable(
                    deployments = filtered,
                    selectedUid = selectedUid,
                    onClick = { dep ->
                        selectedUid = dep.uid
                        onNavigate(Screen.Detail.DeploymentDetail(dep))
                    },
                )
            }
        }
    }
}
