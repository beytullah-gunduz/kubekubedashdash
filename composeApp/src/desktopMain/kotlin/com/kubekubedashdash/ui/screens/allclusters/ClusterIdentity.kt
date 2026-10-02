package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.kdDotShape
import com.kubekubedashdash.ui.ClusterColor

/** Resolves a context name to its cluster colour; read once per table or strip, never per row. */
@Composable
internal fun rememberClusterColorOf(): (String) -> Color {
    val overrides by PreferenceRepository.clusterColorOverrides.collectAsState()
    return remember(overrides) { { context: String -> ClusterColor.effectiveColor(context, overrides).composeColor } }
}

/** A cluster's identity colour, matching its tab chip; square under Retro. */
@Composable
internal fun ClusterDot(color: Color, size: Dp = 8.dp, contentDescription: String? = null) {
    Box(
        Modifier
            .size(size)
            .clip(kdDotShape)
            .background(color)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    )
}

/**
 * A cluster's dot and full context name on one line: the one way this tab shows a cluster name.
 * Never cut in code; the start ellipsizes so the distinguishing tail (an EKS ARN's cluster name,
 * a GKE context's zone and name) stays visible.
 */
@Composable
internal fun ClusterNameLabel(
    contextName: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    textColor: Color = KdTextPrimary,
    fontWeight: FontWeight? = null,
    dotSize: Dp = 8.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ClusterDot(color, dotSize)
        Spacer(Modifier.width(6.dp))
        Text(
            contextName,
            style = style,
            color = textColor,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The plain hover tooltip used across the All Clusters tab. */
@Composable
internal fun TriageTooltip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = KdSurface, shadowElevation = 4.dp, tonalElevation = 2.dp) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
        )
    }
}
