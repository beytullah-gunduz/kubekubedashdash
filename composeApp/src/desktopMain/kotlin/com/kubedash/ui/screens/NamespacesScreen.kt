package com.kubedash.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubedash.GenericResourceInfo
import com.kubedash.KdPrimary
import com.kubedash.KubeClient
import com.kubedash.ResourceState
import com.kubedash.ui.CellData
import com.kubedash.ui.ColumnDef
import com.kubedash.ui.ResizeHandle
import com.kubedash.ui.ResourceCountHeader
import com.kubedash.ui.ResourceErrorMessage
import com.kubedash.ui.ResourceLoadingIndicator
import com.kubedash.ui.ResourceTable
import com.kubedash.ui.TableRow
import com.kubedash.ui.statusColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun NamespacesScreen(
    kubeClient: KubeClient,
    searchQuery: String,
) {
    var state by remember { mutableStateOf<ResourceState<List<GenericResourceInfo>>>(ResourceState.Loading) }
    var selected by remember { mutableStateOf<GenericResourceInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(650f) }

    LaunchedEffect(Unit) {
        state = ResourceState.Loading
        selected = null
        while (true) {
            state = try {
                val items = withContext(Dispatchers.IO) { kubeClient.getNamespacesGeneric() }
                ResourceState.Success(items)
            } catch (e: Exception) {
                if (state is ResourceState.Loading) {
                    ResourceState.Error(e.message ?: "Unknown error")
                } else {
                    state
                }
            }
            delay(5_000)
        }
    }

    LaunchedEffect(state) {
        val current = (state as? ResourceState.Success)?.data ?: return@LaunchedEffect
        selected = selected?.let { sel -> current.find { it.uid == sel.uid } }
    }

    when (val s = state) {
        is ResourceState.Loading -> ResourceLoadingIndicator()

        is ResourceState.Error -> ResourceErrorMessage(s.message)

        is ResourceState.Success -> {
            val filtered = s.data.filter { ns ->
                searchQuery.isBlank() ||
                    ns.name.contains(searchQuery, ignoreCase = true) ||
                    (ns.status?.contains(searchQuery, ignoreCase = true) ?: false)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ResourceCountHeader(filtered.size, "Namespaces")
                    NamespaceTable(
                        namespaces = filtered,
                        selectedUid = selected?.uid,
                        onClick = { ns -> selected = if (selected?.uid == ns.uid) null else ns },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceAtLeast(280f) }
                        selected?.let { ns ->
                            ResourceDetailPanel(
                                kind = "Namespace",
                                name = ns.name,
                                namespace = null,
                                status = ns.status,
                                fields = listOf(
                                    DetailField("Status", ns.status ?: "Unknown", ns.status?.let { statusColor(it) }),
                                    DetailField("Age", ns.age),
                                ),
                                labels = ns.labels,
                                kubeClient = kubeClient,
                                onClose = { selected = null },
                                modifier = Modifier.width(panelWidthDp.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Adaptive table ──────────────────────────────────────────────────────────────

private class NsColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (GenericResourceInfo) -> CellData,
)

private val nsColumns = listOf(
    NsColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) },
    NsColumn("Status", 1.0f, 0.dp) { CellData(it.status ?: "", it.status?.let { statusColor(it) }) },
    NsColumn("Age", 0.7f, 0.dp) { CellData(it.age) },
)

@Composable
private fun NamespaceTable(
    namespaces: List<GenericResourceInfo>,
    selectedUid: String?,
    onClick: (GenericResourceInfo) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = nsColumns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = namespaces.map { ns ->
            TableRow(id = ns.uid, cells = visible.map { it.cell(ns) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> namespaces.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No namespaces found",
        )
    }
}
