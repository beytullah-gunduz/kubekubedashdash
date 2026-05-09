package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_circle_filled
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HealthLevel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private const val HEALTHY_TINT_ALPHA = 0.06f
private const val ALERT_TINT_ALPHA = 0.10f
private const val BORDER_ALPHA = 0.35f

/**
 * Identifies which slice of [ClusterHealthSummary] a banner segment refers
 * to. The cluster overview translates the click into a navigation target —
 * the banner doesn't know about Screen, only what the user pointed at.
 */
internal enum class HealthSegment {
    PODS_IN_ERROR,
    NODES_NOT_READY,
    DEPLOYMENTS_DEGRADED,
    NODES_UNDER_PRESSURE,
    RECENT_WARNINGS,
}

/**
 * Cluster-wide health pill shown above the summary cards. Three states:
 * green (no signal), amber (recent warnings), red (failing pods or
 * NotReady nodes). Drives off [ClusterHealthSummary] alone so the visual
 * mapping is the only thing this composable owns.
 *
 * Each segment in the count breakdown is independently clickable —
 * [onSegmentClick] is the routing hook back to the cluster screen.
 *
 * `health == null` means at least one informer hasn't synced — render a
 * fixed-height placeholder so the cards below don't shift when the first
 * value lands.
 */
@Composable
internal fun ClusterHealthBanner(
    health: ClusterHealthSummary?,
    onSegmentClick: (HealthSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (health == null) {
        Box(modifier.fillMaxWidth().height(BANNER_HEIGHT))
        return
    }

    val visual = bannerVisual(health.level)
    val a11yDescription = health.accessibilityDescription()
    Surface(
        // liveRegion + a synthesized contentDescription on the parent
        // surface — when the cluster goes critical/warning/healthy, the
        // screen reader announces the new state without the user having to
        // find the banner. Polite (not Assertive) so it doesn't interrupt
        // the user mid-sentence; this isn't a modal alert.
        modifier = modifier.fillMaxWidth().height(BANNER_HEIGHT)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = a11yDescription
            },
        shape = RoundedCornerShape(10.dp),
        color = visual.accent.copy(
            alpha = if (health.level == HealthLevel.HEALTHY) HEALTHY_TINT_ALPHA else ALERT_TINT_ALPHA,
        ),
        border = BorderStroke(1.dp, visual.accent.copy(alpha = BORDER_ALPHA)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(visual.icon),
                contentDescription = null,
                tint = visual.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                visual.title,
                style = MaterialTheme.typography.titleSmall,
                color = visual.accent,
                fontWeight = FontWeight.SemiBold,
            )
            val segments = health.segments()
            if (segments.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "—",
                    style = MaterialTheme.typography.titleSmall,
                    color = KdTextSecondary,
                )
                segments.forEachIndexed { i, (segment, label) ->
                    if (i == 0) Spacer(Modifier.width(12.dp)) else BulletSeparator()
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KdTextPrimary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onSegmentClick(segment) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BulletSeparator() {
    Spacer(Modifier.width(8.dp))
    Text(
        "·",
        style = MaterialTheme.typography.bodyMedium,
        color = KdTextSecondary,
    )
    Spacer(Modifier.width(8.dp))
}

private val BANNER_HEIGHT = 48.dp

private data class BannerVisual(
    val accent: Color,
    val icon: DrawableResource,
    val title: String,
)

@Composable
private fun bannerVisual(level: HealthLevel): BannerVisual = when (level) {
    HealthLevel.HEALTHY -> BannerVisual(KdSuccess, Res.drawable.check_circle_filled, "All systems healthy")
    HealthLevel.WARNING -> BannerVisual(KdWarning, Res.drawable.warning_filled, "Warnings detected")
    HealthLevel.CRITICAL -> BannerVisual(KdError, Res.drawable.error_filled, "Issues detected")
}

// Sentence form of the same data, used as the banner's contentDescription
// for screen readers. Matches the visual order in [segments].
private fun ClusterHealthSummary.accessibilityDescription(): String = when (level) {
    HealthLevel.HEALTHY -> "Cluster health: all systems healthy"

    else -> {
        val prefix = if (level == HealthLevel.CRITICAL) {
            "Cluster health critical"
        } else {
            "Cluster health warning"
        }
        val parts = segments().map { (_, label) -> label }
        if (parts.isEmpty()) prefix else "$prefix: ${parts.joinToString(", ")}"
    }
}

// Order: CRITICAL signals first (failing pods/nodes), then WARNING (degraded
// deployments, hot nodes, recent events). Matches the eye-tracking order
// users expect when triaging — most-actionable thing first.
private fun ClusterHealthSummary.segments(): List<Pair<HealthSegment, String>> = buildList {
    if (podsInError > 0) {
        add(HealthSegment.PODS_IN_ERROR to "$podsInError ${plural(podsInError, "pod", "pods")} in error")
    }
    if (nodesNotReady > 0) {
        add(HealthSegment.NODES_NOT_READY to "$nodesNotReady ${plural(nodesNotReady, "node", "nodes")} not ready")
    }
    if (deploymentsDegraded > 0) {
        add(
            HealthSegment.DEPLOYMENTS_DEGRADED to
                "$deploymentsDegraded ${plural(deploymentsDegraded, "deployment", "deployments")} degraded",
        )
    }
    if (nodesUnderPressure > 0) {
        add(
            HealthSegment.NODES_UNDER_PRESSURE to
                "$nodesUnderPressure ${plural(nodesUnderPressure, "node", "nodes")} under pressure",
        )
    }
    if (recentWarnings > 0) {
        add(
            HealthSegment.RECENT_WARNINGS to
                "$recentWarnings warning ${plural(recentWarnings, "event", "events")} (last 15m)",
        )
    }
}

private fun plural(n: Int, singular: String, plural: String): String = if (n == 1) singular else plural
