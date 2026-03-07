package com.kubedash.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kubedash.EventInfo
import com.kubedash.KdPrimary
import com.kubedash.KdSuccess
import com.kubedash.KdTextSecondary
import com.kubedash.KdWarning
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import com.kubedash.ui.CellData
import com.kubedash.ui.ColumnDef
import com.kubedash.ui.ResourceCountHeader
import com.kubedash.ui.ResourceErrorMessage
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.ResourceTable
import com.kubedash.ui.TableRow
import com.kubedash.ui.screens.viewmodel.EventsScreenViewModel

@Composable
fun EventsScreen(
    kubeClient: KubeClient,
    namespace: String?,
    searchQuery: String,
    viewModel: EventsScreenViewModel = viewModel { EventsScreenViewModel(kubeClient) },
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(namespace) {
        viewModel.setNamespace(namespace)
    }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { ev ->
                searchQuery.isBlank() ||
                    ev.reason.contains(searchQuery, ignoreCase = true) ||
                    ev.message.contains(searchQuery, ignoreCase = true) ||
                    ev.objectRef.contains(searchQuery, ignoreCase = true) ||
                    ev.type.contains(searchQuery, ignoreCase = true)
            }
            Column(modifier = Modifier.fillMaxSize()) {
                ResourceCountHeader(filtered.size, "Events")
                EventTable(filtered)
            }
        }
    }
}

@Composable
private fun EventTable(events: List<EventInfo>) {
    val columns = listOf(
        ColumnDef("Type", 0.6f),
        ColumnDef("Reason", 1f),
        ColumnDef("Object", 1.5f),
        ColumnDef("Message", 3f),
        ColumnDef("Count", 0.5f),
        ColumnDef("Last Seen", 0.7f),
        ColumnDef("Namespace", 1f),
    )

    val rows = events.map { ev ->
        val typeColor = when (ev.type) {
            "Warning" -> KdWarning
            "Normal" -> KdSuccess
            else -> KdTextSecondary
        }
        TableRow(
            id = ev.uid,
            cells = listOf(
                CellData(ev.type, typeColor),
                CellData(ev.reason),
                CellData(ev.objectRef, KdPrimary),
                CellData(ev.message),
                CellData("${ev.count}"),
                CellData(ev.lastSeen),
                CellData(ev.namespace),
            ),
        )
    }

    ResourceTable(
        columns = columns,
        rows = rows,
        emptyMessage = "No events found",
    )
}
