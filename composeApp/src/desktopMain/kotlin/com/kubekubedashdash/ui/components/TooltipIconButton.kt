package com.kubekubedashdash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * A 28 dp icon button that reveals a hover tooltip after a short delay — so
 * icon-only header actions (Scale, Restart, Cordon, …) are self-describing.
 *
 * The tooltip shows [label] in bold and, when given, a plain-language
 * [description] line beneath it (what the action does and why you'd use it) —
 * so the action is understandable without prior Kubernetes knowledge. [label]
 * also serves as the icon's accessibility description, and the tooltip shows
 * regardless of [enabled] so a disabled action still explains itself.
 *
 * Matches the app's existing TooltipArea convention (see ClusterActionTooltip /
 * SidebarItemTooltip).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TooltipIconButton(
    icon: DrawableResource,
    label: String,
    tint: Color,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipArea(
        tooltip = { ActionTooltip(label, description) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp), enabled = enabled) {
            Icon(painterResource(icon), label, Modifier.size(16.dp), tint = tint)
        }
    }
}

@Composable
internal fun ActionTooltip(label: String, description: String?) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = KdSurface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = KdTextPrimary,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
