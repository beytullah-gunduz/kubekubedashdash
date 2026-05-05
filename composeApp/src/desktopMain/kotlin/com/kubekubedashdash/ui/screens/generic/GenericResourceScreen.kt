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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.article_filled
import com.kubekubedashdash.resources.dns_filled
import com.kubekubedashdash.resources.dynamic_feed_filled
import com.kubekubedashdash.resources.language_filled
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.lock_filled
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.resources.settings_ethernet_filled
import com.kubekubedashdash.resources.storage_filled
import com.kubekubedashdash.ui.LocalConnectionError
import com.kubekubedashdash.ui.LocalIsConnected
import com.kubekubedashdash.ui.components.EmptyState
import com.kubekubedashdash.ui.components.LabelSelectorChip
import com.kubekubedashdash.ui.components.LiveDataDot
import com.kubekubedashdash.ui.components.ResizeHandle
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.SkeletonRows
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.components.matchesLabelSelector
import com.kubekubedashdash.ui.components.parseLabelSelector
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import com.kubekubedashdash.ui.screens.generic.viewmodel.GenericResourceScreenViewModel
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.DrawableResource

/**
 * English plural for a Kubernetes Kind. Handles the cases that "+s" gets wrong
 * for the kinds this app exposes (Ingress, NetworkPolicy, StorageClass).
 */
private fun pluralizeKind(kind: String): String = when {
    kind.endsWith("y", ignoreCase = true) -> kind.dropLast(1) + "ies"

    kind.endsWith("s", ignoreCase = true) ||
        kind.endsWith("x", ignoreCase = true) ||
        kind.endsWith("ch", ignoreCase = true) ||
        kind.endsWith("sh", ignoreCase = true) -> kind + "es"

    else -> kind + "s"
}

private fun kindIcon(kind: String): DrawableResource = when (kind.lowercase()) {
    "configmap" -> Res.drawable.article_filled
    "secret" -> Res.drawable.lock_filled
    "ingress" -> Res.drawable.language_filled
    "networkpolicy" -> Res.drawable.security_filled
    "persistentvolume", "persistentvolumeclaim" -> Res.drawable.storage_filled
    "storageclass" -> Res.drawable.storage_filled
    "endpoint", "endpoints" -> Res.drawable.settings_ethernet_filled
    "deployment" -> Res.drawable.dynamic_feed_filled
    "service" -> Res.drawable.dns_filled
    else -> Res.drawable.list_filled
}

@Composable
fun GenericResourceScreen(
    kind: String,
    searchQuery: String,
    namespacedKind: Boolean = true,
    sourceFlow: StateFlow<ResourceState<List<GenericResourceInfo>>>,
) {
    val viewModel = viewModel(key = kind) { GenericResourceScreenViewModel(sourceFlow) }
    val state by viewModel.state.collectAsState()
    val selected by viewModel.selected.collectAsState()
    var panelWidthDp by remember { mutableFloatStateOf(650f) }
    var statusFilter by rememberSaveable(kind) { mutableStateOf<Set<String>?>(null) }
    var labelQuery by rememberSaveable(kind) { mutableStateOf("") }

    when (val s = state) {
        is ResourceState.Loading -> SkeletonRows()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val availableStatuses = remember(s.data) {
                s.data.mapNotNull { it.status }.filter { it.isNotBlank() }.toSortedSet()
            }
            val activeStatusFilter = statusFilter
            val labelSelector = remember(labelQuery) { parseLabelSelector(labelQuery) }
            val filtered = s.data.filter { r ->
                val passesSearch = searchQuery.isBlank() ||
                    r.name.contains(searchQuery, ignoreCase = true) ||
                    (r.namespace?.contains(searchQuery, ignoreCase = true) ?: false) ||
                    (r.status?.contains(searchQuery, ignoreCase = true) ?: false)
                val passesStatus = activeStatusFilter == null ||
                    (r.status != null && r.status in activeStatusFilter)
                val passesLabels = labelSelector.isEmpty() || matchesLabelSelector(r.labels, labelSelector)
                passesSearch && passesStatus && passesLabels
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(
                        count = filtered.size,
                        kind = pluralizeKind(kind),
                        liveDot = {
                            LiveDataDot(LocalIsConnected.current, LocalConnectionError.current, Modifier.padding(start = 4.dp))
                        },
                        actions = {
                            LabelSelectorChip(
                                query = labelQuery,
                                onQueryChange = { labelQuery = it },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            if (availableStatuses.isNotEmpty()) {
                                StatusFilterMenu(
                                    available = availableStatuses,
                                    selected = activeStatusFilter ?: availableStatuses,
                                    onToggle = { value ->
                                        val current = activeStatusFilter ?: availableStatuses
                                        statusFilter = if (value in current) current - value else current + value
                                    },
                                    onSelectAll = { statusFilter = null },
                                    onSelectNone = { statusFilter = emptySet() },
                                )
                            }
                        },
                    )
                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = kindIcon(kind),
                            kind = pluralizeKind(kind),
                        )
                    } else {
                        GenericTable(
                            resources = filtered,
                            namespacedKind = namespacedKind,
                            selectedUid = selected?.uid,
                            onClick = { res -> viewModel.selectItem(res) },
                        )
                    }
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
