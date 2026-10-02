package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.retroChrome
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.max

@Composable
fun SummaryCard(
    title: String,
    value: String,
    icon: DrawableResource,
    color: Color = KdPrimary,
    modifier: Modifier = Modifier,
    // Optional pill rendered at the trailing edge of the card, or under the
    // title when the card is too narrow for both. Used by the cluster
    // overview to surface per-resource issue counts (failed pods, NotReady
    // nodes, etc.) inline on the count card. The card stays domain-agnostic —
    // callers compose whatever pill content they need.
    trailingBadge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier,
        ),
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = 10.dp.kdCorner,
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(
            brush = SolidColor(KdBorder),
        ),
    ) {
        // fillMaxHeight centres the content when a row of cards is evened out
        // to its tallest card (a badge under the title adds a line).
        Row(
            modifier = Modifier.fillMaxHeight().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(10.dp.kdCorner)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            if (trailingBadge == null) {
                SummaryFigures(value, title)
            } else {
                // weight(1f) only when there's a badge, so cards without a badge
                // keep their wrap-content layout (the badge variant pushes the
                // badge to the right edge).
                FiguresWithBadge(Modifier.weight(1f)) {
                    SummaryFigures(value, title)
                    trailingBadge()
                }
            }
        }
    }
}

// One line each. A wrapped count read as a different number: "302" broke into
// "30" over "2" in a narrow card.
@Composable
private fun SummaryFigures(value: String, title: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall
                .copy(fontWeight = FontWeight.SemiBold)
                .retroChrome(14.sp),
            color = KdTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = KdTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BADGE_GAP = 8.dp
private val BADGE_STACK_GAP = 4.dp

/**
 * Lays out its two children, the figures and the badge: side by side with the
 * badge at the trailing edge while both fit, otherwise the badge under the
 * figures. A Row gave the badge its width first, so a narrow card squeezed the
 * count into one digit per line.
 *
 * Answers intrinsic measurements itself, so a row of cards can be evened out
 * with `IntrinsicSize.Max`. The default intrinsics would measure the badge at
 * the full width and always report the stacked height.
 */
@Composable
private fun FiguresWithBadge(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier, measurePolicy = FiguresWithBadgePolicy)
}

private object FiguresWithBadgePolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val (figures, badge) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = BADGE_GAP.roundToPx()
        val badgePlaceable = badge.measure(loose)
        val inline = !constraints.hasBoundedWidth ||
            figures.maxIntrinsicWidth(Constraints.Infinity) + gap + badgePlaceable.width <= constraints.maxWidth
        if (inline) {
            val figuresMaxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - gap - badgePlaceable.width).coerceAtLeast(0) else Constraints.Infinity
            val figuresPlaceable = figures.measure(loose.copy(maxWidth = figuresMaxWidth))
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else figuresPlaceable.width + gap + badgePlaceable.width
            val height = max(figuresPlaceable.height, badgePlaceable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
            return layout(width, height) {
                figuresPlaceable.place(0, (height - figuresPlaceable.height) / 2)
                badgePlaceable.place(width - badgePlaceable.width, (height - badgePlaceable.height) / 2)
            }
        }
        val figuresPlaceable = figures.measure(loose)
        val content = figuresPlaceable.height + BADGE_STACK_GAP.roundToPx() + badgePlaceable.height
        val height = content.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(constraints.maxWidth, height) {
            val top = (height - content) / 2
            figuresPlaceable.place(0, top)
            badgePlaceable.place(0, top + figuresPlaceable.height + BADGE_STACK_GAP.roundToPx())
        }
    }

    private fun IntrinsicMeasureScope.height(measurables: List<IntrinsicMeasurable>, width: Int, useMax: Boolean): Int {
        val (figures, badge) = measurables
        fun IntrinsicMeasurable.h(w: Int) = if (useMax) maxIntrinsicHeight(w) else minIntrinsicHeight(w)
        val gap = BADGE_GAP.roundToPx()
        val badgeWidth = badge.maxIntrinsicWidth(Constraints.Infinity)
        return if (figures.maxIntrinsicWidth(Constraints.Infinity) + gap + badgeWidth <= width) {
            max(figures.h((width - gap - badgeWidth).coerceAtLeast(0)), badge.h(badgeWidth))
        } else {
            figures.h(width) + BADGE_STACK_GAP.roundToPx() + badge.h(width)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = height(measurables, width, useMax = false)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = height(measurables, width, useMax = true)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = measurables.maxOf { it.minIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val (figures, badge) = measurables
        return figures.maxIntrinsicWidth(height) + BADGE_GAP.roundToPx() + badge.maxIntrinsicWidth(height)
    }
}
