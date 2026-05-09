package com.kubekubedashdash.ui.screens.events

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.ui.LocalReactiveKubeClient
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
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
    var selectedEventUid by rememberSaveable { mutableStateOf<String?>(null) }

    // null = "all types" (default behavior). A non-null set is an explicit
    // allowlist — set by the route param or by the user toggling chips.
    // Hoisted out of the Success branch so the seed survives state
    // transitions (Loading → Success won't wipe it).
    var selectedTypes by rememberSaveable { mutableStateOf<Set<String>?>(initialTypeFilter) }
    LaunchedEffect(initialTypeFilter) {
        if (initialTypeFilter != null) selectedTypes = initialTypeFilter
    }
    var selectedNodes by rememberSaveable { mutableStateOf<Set<String>?>(null) }

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

            val effectiveTypes = selectedTypes ?: availableTypes
            val effectiveNodes = selectedNodes ?: availableNodes

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
                ResourceCountHeader(filtered.size, "Events")
                EventTable(
                    events = filtered,
                    availableTypes = availableTypes,
                    selectedTypes = effectiveTypes,
                    onToggleType = { type ->
                        val current = selectedTypes ?: availableTypes
                        selectedTypes = if (type in current) current - type else current + type
                    },
                    onSelectAllTypes = { selectedTypes = null },
                    onSelectNoTypes = { selectedTypes = emptySet() },
                    availableNodes = availableNodes,
                    selectedNodes = effectiveNodes,
                    onToggleNode = { node ->
                        val current = selectedNodes ?: availableNodes
                        selectedNodes = if (node in current) current - node else current + node
                    },
                    onSelectAllNodes = { selectedNodes = null },
                    onSelectNoNodes = { selectedNodes = emptySet() },
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
