package com.kubekubedashdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The always-visible cluster chip that anchors a session.
 *
 * Single chip lives inline in the title bar at N=1; chips lift into a
 * [WindowTabStrip] at N≥2. The chip is also the AWT drag source for
 * tear-out and merge in Phase 4 — that wiring is not done yet, but the
 * chip's visual identity (color avatar + name) is the handle the user
 * will eventually grab.
 */
@Composable
fun ClusterChip(
    label: String,
    color: ClusterColor,
    initial: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val background = if (isActive) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color.composeColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
            color = if (isActive) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            },
        )

        if (onClose != null) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close $label",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
