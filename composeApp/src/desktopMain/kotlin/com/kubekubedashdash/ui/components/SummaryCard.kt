package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun SummaryCard(
    title: String,
    value: String,
    icon: DrawableResource,
    color: Color = KdPrimary,
    modifier: Modifier = Modifier,
    // Optional pill rendered at the trailing edge of the card. Used by the
    // cluster overview to surface per-resource issue counts (failed pods,
    // NotReady nodes, etc.) inline on the count card. The card stays
    // domain-agnostic — callers compose whatever pill content they need.
    trailingBadge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier,
        ),
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(10.dp),
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = SolidColor(KdBorder),
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            // weight(1f) only when there's a badge, so cards without a badge
            // keep their existing wrap-content layout (the badge variant
            // pushes the badge to the right edge).
            val columnModifier = if (trailingBadge != null) Modifier.weight(1f) else Modifier
            Column(modifier = columnModifier) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = KdTextSecondary,
                )
            }
            if (trailingBadge != null) {
                Spacer(Modifier.width(8.dp))
                trailingBadge()
            }
        }
    }
}
