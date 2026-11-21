package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Always shown above a selectable table so ticking a row never moves the table.
 * With nothing ticked it shows a hint and its verbs are disabled; its height
 * never depends on the count. Caller provides the verb buttons (use
 * [BulkVerbButton] so every verb is icon-labelled and self-describing) and
 * receives whether the selection is non-empty.
 */
@Composable
fun BulkSelectionBar(
    selectedCount: Int,
    kind: String,
    onClear: () -> Unit,
    actions: @Composable RowScope.(enabled: Boolean) -> Unit,
) {
    val active = selectedCount > 0
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (active) KdPrimary.copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 4.dp.orCompact(2.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (active) "$selectedCount $kind selected" else "Tick rows to act on several $kind",
                style = MaterialTheme.typography.labelMedium,
                color = if (active) KdTextPrimary else KdTextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            actions(active)
            BulkVerbButton(
                icon = Res.drawable.close_filled,
                label = "Clear",
                tooltipTitle = "Clear selection",
                description = "Deselect all — the $kind themselves are not affected.",
                tint = KdTextSecondary,
                enabled = active,
                onClick = onClear,
            )
        }
    }
}

/**
 * A bulk-bar verb button: text label with a leading icon, plus a hover tooltip
 * ([tooltipTitle] in bold, defaulting to [label], and a plain-language
 * [description] of what the action does to the selected items) — so destructive
 * scope is explained before the confirm dialog ever opens. Matches the
 * [ActionTooltip] convention used by the detail-panel header actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulkVerbButton(
    icon: DrawableResource,
    label: String,
    description: String,
    tint: Color,
    tooltipTitle: String = label,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipArea(
        tooltip = { ActionTooltip(tooltipTitle, description) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        TextButton(onClick = onClick, enabled = enabled, shape = kdRoundShape) {
            val shown = if (enabled) tint else tint.copy(alpha = 0.38f)
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = shown)
            Spacer(Modifier.width(4.dp))
            Text(label, color = shown, maxLines = 1, softWrap = false)
        }
    }
}
