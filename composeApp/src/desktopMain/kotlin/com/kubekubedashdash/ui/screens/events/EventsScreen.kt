package com.kubekubedashdash.ui.screens.events

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ClearFiltersChip
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.StatusFilterMenu
import com.kubekubedashdash.ui.screens.events.viewmodel.EventsScreenViewModel
import kotlinx.coroutines.flow.first

@Composable
fun EventsScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    selectEventUid: String? = null,
    // When a navigation target supplies an allowlist of types to focus on
    // (e.g. setOf("Warning", "Error") from the cluster-health banner), seed
    // the type-filter chip with it on entry. User can broaden via the menu.
    initialTypeFilter: Set<String>? = null,
) {
    val reactiveClient = LocalReactiveKubeClient.current
    val viewModel: EventsScreenViewModel = viewModel { EventsScreenViewModel(reactiveClient) }
    val state by viewModel.state.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()
    val nodeFilter by viewModel.nodeFilter.collectAsState()
    var selectedEventUid by rememberSaveable { mutableStateOf<String?>(null) }

    // Nav-driven seed: if the route arrives with an explicit type allowlist
    // (e.g. from a banner click), write it into VM state so the chip shows it.
    LaunchedEffect(initialTypeFilter) {
        if (initialTypeFilter != null) viewModel.setTypeFilter(initialTypeFilter)
    }

    LaunchedEffect(selectEventUid) {
        viewModel.setParams(selectEventUid)
        if (selectEventUid != null) {
            viewModel.selected.first { it != null }?.let {
                selectedEventUid = it.uid
                onNavigate(Screen.Detail.EventDetail(it))
            }
        }
    }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val availableTypes = remember(s.data) { s.data.map { it.type }.toSet() }
            val availableNodes = remember(s.data) {
                s.data.map { it.node.ifEmpty { "-" } }.toSet()
            }

            val effectiveTypes = typeFilter ?: availableTypes
            val effectiveNodes = nodeFilter ?: availableNodes

            val filtered = s.data.filter { ev ->
                ev.type in effectiveTypes &&
                    (ev.node.ifEmpty { "-" }) in effectiveNodes &&
                    (
                        searchQuery.isBlank() ||
                            ev.reason.contains(searchQuery, ignoreCase = true) ||
                            ev.message.contains(searchQuery, ignoreCase = true) ||
                            ev.objectRef.contains(searchQuery, ignoreCase = true) ||
                            ev.type.contains(searchQuery, ignoreCase = true)
                        )
            }
            Column(modifier = Modifier.fillMaxSize()) {
                ResourceCountHeader(
                    count = filtered.size,
                    kind = "Events",
                    actions = {
                        StatusFilterMenu(
                            available = availableTypes,
                            selected = effectiveTypes,
                            onToggle = { type ->
                                val current = typeFilter ?: availableTypes
                                viewModel.setTypeFilter(if (type in current) current - type else current + type)
                            },
                            onSelectAll = { viewModel.setTypeFilter(null) },
                            onSelectNone = { viewModel.setTypeFilter(emptySet()) },
                            label = "Type",
                            itemContent = { value ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    EventTypeIcon(value)
                                    Spacer(Modifier.width(6.dp))
                                    Text(value)
                                }
                            },
                            pulseOnEntry = typeFilter != null,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusFilterMenu(
                            available = availableNodes,
                            selected = effectiveNodes,
                            onToggle = { node ->
                                val current = nodeFilter ?: availableNodes
                                viewModel.setNodeFilter(if (node in current) current - node else current + node)
                            },
                            onSelectAll = { viewModel.setNodeFilter(null) },
                            onSelectNone = { viewModel.setNodeFilter(emptySet()) },
                            label = "Node",
                            itemContent = { Text(it) },
                            pulseOnEntry = nodeFilter != null,
                        )
                        AnimatedVisibility(
                            visible = typeFilter != null || nodeFilter != null,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut(),
                        ) {
                            ClearFiltersChip(
                                onClick = {
                                    viewModel.setTypeFilter(null)
                                    viewModel.setNodeFilter(null)
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    },
                )
                EventTable(
                    events = filtered,
                    selectedUid = selectedEventUid,
                    onEventClick = { event ->
                        selectedEventUid = event.uid
                        onNavigate(Screen.Detail.EventDetail(event))
                    },
                )
            }
        }
    }
}
