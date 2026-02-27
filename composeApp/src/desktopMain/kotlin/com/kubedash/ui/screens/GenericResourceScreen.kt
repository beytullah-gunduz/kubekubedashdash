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
fun GenericResourceScreen(
    kind: String,
    searchQuery: String,
    kubeClient: KubeClient,
    namespacedKind: Boolean = true,
    fetcher: () -> List<GenericResourceInfo>,
) {
    var state by remember(kind) { mutableStateOf<ResourceState<List<GenericResourceInfo>>>(ResourceState.Loading) }
    var selected by remember(kind) { mutableStateOf<GenericResourceInfo?>(null) }
    var panelWidthDp by remember { mutableFloatStateOf(480f) }

    LaunchedEffect(kind) {
        state = ResourceState.Loading
        selected = null
        while (true) {
            state = try {
                val items = withContext(Dispatchers.IO) { fetcher() }
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
                        onClick = { res -> selected = if (selected?.uid == res.uid) null else res },
                    )
                }

                AnimatedVisibility(
                    visible = selected != null,
                    enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        ResizeHandle { panelWidthDp = (panelWidthDp - it).coerceIn(280f, 900f) }
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

private class GenericColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (GenericResourceInfo) -> CellData,
)

@Composable
private fun GenericTable(
    resources: List<GenericResourceInfo>,
    namespacedKind: Boolean,
    selectedUid: String?,
    onClick: (GenericResourceInfo) -> Unit,
) {
    val extraKeys = resources.flatMap { it.extraColumns.keys }.distinct()
    val hasStatus = resources.any { it.status != null }

    val columns = buildList<GenericColumn> {
        add(GenericColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) })
        if (namespacedKind) {
            add(GenericColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace ?: "") })
        }
        if (hasStatus) {
            add(
                GenericColumn("Status", 0.8f, 0.dp) { r ->
                    CellData(r.status ?: "", r.status?.let { statusColor(it) })
                },
            )
        }
        extraKeys.forEachIndexed { i, key ->
            val minW = when {
                extraKeys.size <= 1 -> 0.dp
                i == extraKeys.lastIndex -> (500 + i * 100).dp
                else -> (450 + i * 100).dp
            }
            add(GenericColumn(key, 1f, minW) { r -> CellData(r.extraColumns[key] ?: "") })
        }
        add(GenericColumn("Age", 0.7f, 0.dp) { CellData(it.age) })
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = columns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = resources.map { r ->
            TableRow(id = r.uid, cells = visible.map { it.cell(r) })
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> resources.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No resources found",
        )
    }
}
