package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * A 28 dp icon button that reveals a hover tooltip with [label] after a short
 * delay — so icon-only header actions (Scale, Restart, Approve, Cordon, …) are
 * self-describing. [label] doubles as the icon's accessibility description.
 *
 * Matches the app's existing [TooltipArea] convention (see ClusterActionTooltip /
 * SidebarItemTooltip). The tooltip shows on hover regardless of [enabled], so a
 * disabled action still explains itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipIconButton(
    icon: DrawableResource,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipArea(
        tooltip = { ActionTooltip(label) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp), enabled = enabled) {
            Icon(painterResource(icon), label, Modifier.size(16.dp), tint = tint)
        }
    }
}

@Composable
private fun ActionTooltip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
        )
    }
}
