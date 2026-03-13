package com.kubekubedashdash.ui.screens.generic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.components.ResizeHandle
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import com.kubekubedashdash.ui.screens.generic.viewmodel.GenericResourceScreenViewModel
import kotlinx.coroutines.flow.StateFlow

@Composable
fun GenericResourceScreen(
    kind: String,
    searchQuery: String,
    namespacedKind: Boolean = true,
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
) {
    val viewModel = remember(kind, sourceFlow) { GenericResourceScreenViewModel(sourceFlow) }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    var panelWidthDp by remember { mutableFloatStateOf(650f) }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { r ->
                searchQuery.isBlank() ||
                    r.name.contains(searchQuery, ignoreCase = true) ||
                    (r.namespace?.contains(searchQuery, ignoreCase = true) ?: false) ||
                    (r.status?.contains(searchQuery, ignoreCase = true) ?: false)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(filtered.size, kind + "s")
                    GenericTable(
                        resources = filtered,
                        namespacedKind = namespacedKind,
                        selectedUid = selected?.uid,
                        onClick = { res -> viewModel.selectItem(res) },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceAtLeast(280f) }
                        selected?.let { res ->
                            val fields = buildList {
                                if (namespacedKind && res.namespace != null) {
                                    add(DetailField("Namespace", res.namespace))
                                }
                                if (res.status != null) {
                                    add(DetailField("Status", res.status, statusColor(res.status)))
                                }
                                res.extraColumns.forEach { (key, value) ->
                                    add(DetailField(key, value))
                                }
                                add(DetailField("Age", res.age))
                            }
                            ResourceDetailPanel(
                                kind = kind,
                                name = res.name,
                                namespace = res.namespace,
                                status = res.status,
                                fields = fields,
                                labels = res.labels,
                                onClose = { viewModel.clearSelection() },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}
