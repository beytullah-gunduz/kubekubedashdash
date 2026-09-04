package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.resources.sell_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The removable "active filter" row rendered below a resource list's count
 * header: one pill per label entry, one per annotation entry — each carries
 * a trailing × and the whole pill is the click target, removing just that
 * entry via [removeSelectorEntry] — plus at most one aggregate status pill
 * and any boolean filters passed through [extraPills]. Renders nothing when
 * no filter is active. There is deliberately no "Clear all" pill here: that
 * is [ClearFiltersChip], already in the header.
 *
 * A label or annotation query that is non-blank but parses to zero entries
 * (raw text with no `=`) still renders one pill carrying the raw query, so
 * the row never goes empty while the header's Clear chip is lit.
 *
 * [statusFilter] and [onClearStatus] together drive a single pill —
 * "Status: <value>" for one status, "Status: <n> selected" for several,
 * "Status: none" when the set is empty — never one pill per status: a
 * KPI-strip click can set an entire status set at once, and un-highlighting
 * that click on removal of just one status would be wrong.
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
    val rawLabel = labelQuery.isNotBlank() && labelEntries.isEmpty()
    val rawAnnotation = annotationQuery.isNotBlank() && annotationEntries.isEmpty()

    if (labelEntries.isEmpty() && annotationEntries.isEmpty() && !rawLabel && !rawAnnotation &&
        statusFilter == null && extraPills.isEmpty()
    ) {
        return
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (rawLabel) {
            FilterPill(
                icon = Res.drawable.sell_filled,
                tooltip = "Remove label filter",
                onRemove = { onLabelQueryChange("") },
            ) {
                Text(
                    labelQuery.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KdPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        labelEntries.forEach { (key, value) ->
            FilterPill(
                icon = Res.drawable.sell_filled,
                tooltip = "Remove label filter $key=$value",
                onRemove = { onLabelQueryChange(removeSelectorEntry(labelQuery, key)) },
            ) {
                KeyEqualsValuePillText(key, value)
            }
        }
        if (rawAnnotation) {
            FilterPill(
                icon = Res.drawable.description_filled,
                tooltip = "Remove annotation filter",
                onRemove = { onAnnotationQueryChange("") },
            ) {
                Text(
                    annotationQuery.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KdPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        annotationEntries.forEach { (key, value) ->
            FilterPill(
                icon = Res.drawable.description_filled,
                tooltip = "Remove annotation filter $key=$value",
                onRemove = { onAnnotationQueryChange(removeSelectorEntry(annotationQuery, key)) },
            ) {
                KeyEqualsValuePillText(key, value)
            }
        }
        if (statusFilter != null) {
            val statusText = when {
                statusFilter.isEmpty() -> "Status: none"
                statusFilter.size == 1 -> "Status: ${statusFilter.first()}"
                else -> "Status: ${statusFilter.size} selected"
            }
            val statusTooltip = if (statusFilter.isEmpty() || statusFilter.size > 4) {
                "Remove status filter"
            } else {
                "Remove status filter: " + statusFilter.sorted().joinToString(", ")
            }
            FilterPill(
                icon = null,
                tooltip = statusTooltip,
                onRemove = { onClearStatus?.invoke() },
            ) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        extraPills.forEach { (text, onRemove) ->
            FilterPill(
                icon = null,
                tooltip = "Remove filter: $text",
                onRemove = onRemove,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One removable filter pill: a tinted, rounded surface with an optional
 * leading glyph, [content], and a trailing × — the whole surface is the
 * click target for [onRemove], so the × is affordance only and carries no
 * `clickable` of its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterPill(
    icon: DrawableResource?,
    tooltip: String,
    onRemove: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = KdSurface,
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
            ) {
                Text(
                    tooltip,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = KdPrimary.copy(alpha = 0.18f),
            modifier = Modifier
                .clickable(onClick = onRemove)
                .pointerHoverIcon(PointerIcon.Hand)
                .widthIn(max = 360.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = KdPrimary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                content()
                Spacer(Modifier.width(4.dp))
                Icon(
                    painterResource(Res.drawable.close_filled),
                    contentDescription = tooltip,
                    modifier = Modifier.size(10.dp),
                    tint = KdPrimary.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** The `key = value` triple of [Text]s a label/annotation pill renders, matching [LabelChip]'s look. */
@Composable
private fun RowScope.KeyEqualsValuePillText(key: String, value: String) {
    Text(
        key,
        style = MaterialTheme.typography.labelSmall,
        color = KdPrimary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
    Text(
        "=",
        style = MaterialTheme.typography.labelSmall,
        color = KdPrimary.copy(alpha = 0.7f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        value,
        style = MaterialTheme.typography.labelSmall,
        color = KdPrimary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(2f, fill = false),
    )
}
