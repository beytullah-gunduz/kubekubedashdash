package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPlaceholder
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.list_filled
import com.kubekubedashdash.resources.search_filled
import com.kubekubedashdash.ui.components.ColumnFilterDropdown
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AllClustersEventsFilterBar(
    filters: EventTriageFilters,
    availableTypes: Set<String>,
    availableClusters: Set<String>,
    availableNamespaces: Set<String>,
    availableReasons: Set<String>,
    onUpdateFilters: ((EventTriageFilters) -> EventTriageFilters) -> Unit,
    presetMenuSlot: @Composable () -> Unit = {},
) {
    var showTypeFilter by remember { mutableStateOf(false) }
    var showClusterFilter by remember { mutableStateOf(false) }
    var showNamespaceFilter by remember { mutableStateOf(false) }
    var showReasonFilter by remember { mutableStateOf(false) }

    val effectiveTypes = filters.types.ifEmpty { availableTypes }
    val effectiveClusters = filters.clusters.ifEmpty { availableClusters }
    val effectiveNamespaces = filters.namespaces.ifEmpty { availableNamespaces }
    val effectiveReasons = filters.reasons.ifEmpty { availableReasons }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdSurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Type filter
        FilterChip(
            label = "Type",
            active = filters.types.isNotEmpty() && filters.types != availableTypes,
            summary = if (filters.types.isEmpty()) "All" else filters.types.joinToString(", "),
        ) {
            ColumnFilterDropdown(
                expanded = showTypeFilter,
                active = filters.types.isNotEmpty() && filters.types != availableTypes,
                onToggle = { showTypeFilter = !showTypeFilter },
                onDismiss = { showTypeFilter = false },
                availableValues = availableTypes.ifEmpty { setOf("Normal", "Warning", "Error") },
                selectedValues = effectiveTypes,
                onToggleValue = { type ->
                    onUpdateFilters { f ->
                        val current = f.types.ifEmpty { availableTypes }
                        val next = if (type in current) current - type else current + type
                        f.copy(types = next)
                    }
                },
                onSelectAll = { onUpdateFilters { f -> f.copy(types = emptySet()) } },
                onSelectNone = { onUpdateFilters { f -> f.copy(types = emptySet()) } },
            )
        }

        // Cluster filter (only shown when multiple clusters are available)
        if (availableClusters.size > 1) {
            FilterChip(
                label = "Cluster",
                active = filters.clusters.isNotEmpty(),
                summary = when {
                    filters.clusters.isEmpty() -> "All (${availableClusters.size})"
                    filters.clusters.size == 1 -> filters.clusters.first().substringAfterLast("/").take(16)
                    else -> "${filters.clusters.size}/${availableClusters.size}"
                },
            ) {
                ColumnFilterDropdown(
                    expanded = showClusterFilter,
                    active = filters.clusters.isNotEmpty(),
                    onToggle = { showClusterFilter = !showClusterFilter },
                    onDismiss = { showClusterFilter = false },
                    availableValues = availableClusters,
                    selectedValues = effectiveClusters,
                    onToggleValue = { cluster ->
                        onUpdateFilters { f ->
                            val current = f.clusters.ifEmpty { availableClusters }
                            val next = if (cluster in current) current - cluster else current + cluster
                            // Empty set means "all selected"
                            f.copy(clusters = if (next == availableClusters) emptySet() else next)
                        }
                    },
                    onSelectAll = { onUpdateFilters { f -> f.copy(clusters = emptySet()) } },
                    onSelectNone = { onUpdateFilters { f -> f.copy(clusters = emptySet()) } },
                )
            }
        }

        // Namespace filter
        if (availableNamespaces.isNotEmpty()) {
            FilterChip(
                label = "Namespace",
                active = filters.namespaces.isNotEmpty(),
                summary = when {
                    filters.namespaces.isEmpty() -> "All"
                    filters.namespaces.size == 1 -> filters.namespaces.first().take(12)
                    else -> "${filters.namespaces.size}/${availableNamespaces.size}"
                },
            ) {
                ColumnFilterDropdown(
                    expanded = showNamespaceFilter,
                    active = filters.namespaces.isNotEmpty(),
                    onToggle = { showNamespaceFilter = !showNamespaceFilter },
                    onDismiss = { showNamespaceFilter = false },
                    availableValues = availableNamespaces,
                    selectedValues = effectiveNamespaces,
                    onToggleValue = { ns ->
                        onUpdateFilters { f ->
                            val current = f.namespaces.ifEmpty { availableNamespaces }
                            val next = if (ns in current) current - ns else current + ns
                            f.copy(namespaces = if (next == availableNamespaces) emptySet() else next)
                        }
                    },
                    onSelectAll = { onUpdateFilters { f -> f.copy(namespaces = emptySet()) } },
                    onSelectNone = { onUpdateFilters { f -> f.copy(namespaces = emptySet()) } },
                )
            }
        }

        // Reason filter
        if (availableReasons.isNotEmpty()) {
            FilterChip(
                label = "Reason",
                active = filters.reasons.isNotEmpty(),
                summary = when {
                    filters.reasons.isEmpty() -> "All"
                    filters.reasons.size == 1 -> filters.reasons.first().take(14)
                    else -> "${filters.reasons.size} selected"
                },
            ) {
                ColumnFilterDropdown(
                    expanded = showReasonFilter,
                    active = filters.reasons.isNotEmpty(),
                    onToggle = { showReasonFilter = !showReasonFilter },
                    onDismiss = { showReasonFilter = false },
                    availableValues = availableReasons,
                    selectedValues = effectiveReasons,
                    onToggleValue = { reason ->
                        onUpdateFilters { f ->
                            val current = f.reasons.ifEmpty { availableReasons }
                            val next = if (reason in current) current - reason else current + reason
                            f.copy(reasons = if (next == availableReasons) emptySet() else next)
                        }
                    },
                    onSelectAll = { onUpdateFilters { f -> f.copy(reasons = emptySet()) } },
                    onSelectNone = { onUpdateFilters { f -> f.copy(reasons = emptySet()) } },
                )
            }
        }

        // Free-text search. Sized to match the surrounding filter chips
        // (~28dp). BasicTextField + DecorationBox lets us pass a small
        // contentPadding; M3's high-level OutlinedTextField bakes in ~16dp
        // vertical padding which would clip bodySmall at this height.
        SearchTextField(
            value = filters.searchText,
            onValueChange = { text -> onUpdateFilters { f -> f.copy(searchText = text) } },
            modifier = Modifier.weight(1f).height(28.dp),
        )

        // Time window segmented control
        TimeWindowSelector(
            selected = filters.timeWindow,
            onSelect = { tw -> onUpdateFilters { f -> f.copy(timeWindow = tw) } },
        )

        Spacer(Modifier.width(4.dp))

        // Saved filter presets menu
        presetMenuSlot()

        Spacer(Modifier.width(4.dp))

        // Raw / Grouped toggle
        ViewModeToggle(
            mode = filters.mode,
            onToggle = { onUpdateFilters { f -> f.copy(mode = if (f.mode == ViewMode.GROUPED) ViewMode.RAW else ViewMode.GROUPED) } },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = KdPrimary,
        unfocusedBorderColor = KdBorder,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = KdTextPrimary),
        cursorBrush = SolidColor(KdPrimary),
        interactionSource = interactionSource,
        modifier = modifier,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        "Search object, message, reason…",
                        style = MaterialTheme.typography.bodySmall,
                        color = KdTextPlaceholder,
                    )
                },
                leadingIcon = {
                    Icon(
                        painterResource(Res.drawable.search_filled),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = KdTextPlaceholder,
                    )
                },
                colors = colors,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                    )
                },
            )
        },
    )
}

@Composable
private fun FilterChip(
    label: String,
    active: Boolean,
    summary: String,
    filterDropdown: @Composable () -> Unit,
) {
    Box(contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = if (active) KdPrimary else KdBorder,
                    shape = RoundedCornerShape(4.dp),
                )
                .background(
                    if (active) KdPrimary.copy(alpha = 0.08f) else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "$label: $summary",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) KdPrimary else KdTextSecondary,
            )
            filterDropdown()
        }
    }
}

@Composable
private fun TimeWindowSelector(
    selected: TimeWindow,
    onSelect: (TimeWindow) -> Unit,
) {
    Row(
        modifier = Modifier
            .border(1.dp, KdBorder, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeWindow.entries.forEachIndexed { i, tw ->
            val isSelected = tw == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) KdPrimary.copy(alpha = 0.15f) else Color.Transparent,
                    )
                    .clickable { onSelect(tw) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tw.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) KdPrimary else KdTextSecondary,
                )
            }
            if (i < TimeWindow.entries.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(KdBorder),
                )
            }
        }
    }
}

@Composable
private fun ViewModeToggle(
    mode: ViewMode,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .border(1.dp, KdBorder, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(Res.drawable.list_filled),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (mode == ViewMode.GROUPED) KdPrimary else KdTextSecondary,
        )
        Text(
            text = if (mode == ViewMode.GROUPED) "Grouped" else "Raw",
            style = MaterialTheme.typography.labelSmall,
            color = if (mode == ViewMode.GROUPED) KdPrimary else KdTextSecondary,
        )
    }
}
