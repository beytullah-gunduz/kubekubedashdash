package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The removable "active filter" row rendered below a resource list's count
 * header: one pill per label entry, one per annotation entry — each click
 * removes just that entry via [removeSelectorEntry] — plus at most one
 * aggregate status pill and any boolean filters passed through [extraPills].
 * Renders nothing when no filter is active. There is deliberately no
 * "Clear all" pill here: that is [ClearFiltersChip], already in the header.
 *
 * [statusFilter] and [onClearStatus] together drive a single pill —
 * `Status: <value>` for one status, `Status: <n>` otherwise — never one pill
 * per status: a KPI-strip click can set an entire status set at once, and
 * un-highlighting that click on removal of just one status would be wrong.
 *
 * [extraPills] carries the boolean filters that have no key=value form (e.g.
 * Nodes' "Under pressure", Deployments' "Degraded only"), each paired with
 * the action that clears it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilterPills(
    labelQuery: String,
    onLabelQueryChange: (String) -> Unit,
    annotationQuery: String,
    onAnnotationQueryChange: (String) -> Unit,
    statusFilter: Set<String>? = null,
    onClearStatus: (() -> Unit)? = null,
    extraPills: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val labelEntries = remember(labelQuery) { parseMapSelector(labelQuery).toList() }
    val annotationEntries = remember(annotationQuery) { parseMapSelector(annotationQuery).toList() }

    if (labelEntries.isEmpty() && annotationEntries.isEmpty() && statusFilter == null && extraPills.isEmpty()) {
        return
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labelEntries.forEach { (key, value) ->
            LabelChip(
                key = key,
                value = value,
                active = true,
                onClick = { onLabelQueryChange(removeSelectorEntry(labelQuery, key)) },
            )
        }
        annotationEntries.forEach { (key, value) ->
            LabelChip(
                key = key,
                value = value,
                active = true,
                onClick = { onAnnotationQueryChange(removeSelectorEntry(annotationQuery, key)) },
            )
        }
        if (statusFilter != null) {
            val statusText = if (statusFilter.size == 1) statusFilter.first() else "${statusFilter.size}"
            LabelChip(
                key = "Status",
                value = statusText,
                active = true,
                onClick = onClearStatus,
            )
        }
        extraPills.forEach { (text, onRemove) ->
            LabelChip(
                key = "Filter",
                value = text,
                active = true,
                onClick = onRemove,
            )
        }
    }
}
