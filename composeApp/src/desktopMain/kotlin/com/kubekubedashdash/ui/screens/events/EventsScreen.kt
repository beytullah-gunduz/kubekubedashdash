package com.kubekubedashdash.ui.screens.events

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.kubekubedashdash.ui.components.ResourceCountHeader
import com.kubekubedashdash.ui.components.ResourceErrorMessage
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.screens.events.viewmodel.EventsScreenViewModel

@Composable
fun EventsScreen(
    searchQuery: String,
    onNavigate: (Screen) -> Unit,
    viewModel: EventsScreenViewModel = viewModel { EventsScreenViewModel() },
) {
    val state by viewModel.state.collectAsState()
    var selectedEventUid by rememberSaveable { mutableStateOf<String?>(null) }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val availableTypes = remember(s.data) { s.data.map { it.type }.toSet() }
            var selectedTypes by remember(availableTypes) { mutableStateOf(availableTypes) }

            val availableNodes = remember(s.data) {
                s.data.map { it.node.ifEmpty { "-" } }.toSet()
            }
            var selectedNodes by remember(availableNodes) { mutableStateOf(availableNodes) }

            val filtered = s.data.filter { ev ->
                ev.type in selectedTypes &&
                    (ev.node.ifEmpty { "-" }) in selectedNodes &&
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
                    selectedTypes = selectedTypes,
                    onToggleType = { type ->
                        selectedTypes = if (type in selectedTypes) {
                            selectedTypes - type
                        } else {
                            selectedTypes + type
                        }
                    },
                    onSelectAllTypes = { selectedTypes = availableTypes },
                    onSelectNoTypes = { selectedTypes = emptySet() },
                    availableNodes = availableNodes,
                    selectedNodes = selectedNodes,
                    onToggleNode = { node ->
                        selectedNodes = if (node in selectedNodes) {
                            selectedNodes - node
                        } else {
                            selectedNodes + node
                        }
                    },
                    onSelectAllNodes = { selectedNodes = availableNodes },
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
