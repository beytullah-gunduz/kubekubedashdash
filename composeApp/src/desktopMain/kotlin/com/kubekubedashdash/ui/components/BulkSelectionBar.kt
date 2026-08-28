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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Shown while a table selection is non-empty; caller provides the verb buttons
 * (use [BulkVerbButton] so every verb is icon-labelled and self-describing).
 */
@Composable
fun BulkSelectionBar(
    selectedCount: Int,
    kind: String,
    onClear: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KdPrimary.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$selectedCount $kind selected", style = MaterialTheme.typography.labelMedium, color = KdTextPrimary)
        Spacer(Modifier.weight(1f))
        actions()
        BulkVerbButton(
            icon = Res.drawable.close_filled,
            label = "Clear",
            tooltipTitle = "Clear selection",
            description = "Deselect all — the $kind themselves are not affected.",
            tint = KdTextSecondary,
            onClick = onClear,
        )
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
    onClick: () -> Unit,
) {
    TooltipArea(
        tooltip = { ActionTooltip(tooltipTitle, description) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        TextButton(onClick = onClick) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
            Text(label, color = tint)
        }
    }
}
