package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdInfo
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdOutlineWidth
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.resources.open_in_new_filled
import com.kubekubedashdash.resources.warning_filled
import com.kubekubedashdash.ui.components.PodStatusBar
import com.kubekubedashdash.ui.components.UsageHistoryBar
import com.kubekubedashdash.ui.components.UsageTierGlyph
import com.kubekubedashdash.ui.components.color
import com.kubekubedashdash.ui.components.horizontalScrollFade
import com.kubekubedashdash.ui.components.usageTier
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel
import com.kubekubedashdash.ui.screens.cluster.TopNodeRow
import com.kubekubedashdash.ui.screens.cluster.UsageScope
import com.kubekubedashdash.util.formatCpuCores
import com.kubekubedashdash.util.formatMemorySize
import org.jetbrains.compose.resources.painterResource

/**
 * The All Clusters usage band: a Total panel, one panel per open cluster tab (tab order) and the
 * top nodes, side by side at equal width; when they do not fit at [FLEET_PANEL_MIN_WIDTH] the
 * cluster panels scroll between a pinned Total and pinned Top nodes. Collapsed, one line.
 */
@Composable
internal fun FleetStrip(
    clusterInfo: ClusterInfo?,
    usage: ResourceUsageSummary?,
    cpuHistory: List<Float>,
    memHistory: List<Float>,
    podsCapacity: Int,
    podsHistory: List<Float>,
    summaries: List<AllClustersViewModel.ClusterSummary>,
    topNodes: List<AllClustersViewModel.ClusterNodeUsage>,
    timeWindow: TimeWindow,
    scope: UsageScope?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenCluster: (SessionId) -> Unit,
    onShowWarnings: (contextName: String?) -> Unit,
    onNodeClick: (AllClustersViewModel.ClusterNodeUsage) -> Unit,
) {
    val colorOf = rememberClusterColorOf()
    val totalWarnings = summaries.sumOf { it.recentErrorCount }
    val phases = clusterInfo?.let { PodPhaseCounts(it.runningPods, it.pendingPods, it.failedPods, it.succeededPods) }

    if (!expanded) {
        CollapsedFleetBar(
            clusterCount = summaries.size,
            usage = usage,
            podsCount = clusterInfo?.podsCount,
            podsCapacity = podsCapacity,
            totalWarnings = totalWarnings,
            timeWindow = timeWindow,
            onToggleExpanded = onToggleExpanded,
            onShowWarnings = onShowWarnings,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showClusters = summaries.size >= 2
        val showTopNodes = topNodes.isNotEmpty()
        val panelCount = 1 + (if (showClusters) summaries.size else 0) + (if (showTopNodes) 1 else 0)
        val fits = fleetStripFits(maxWidth, panelCount)
        val scrollbarThickness = LocalScrollbarStyle.current.thickness
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(FLEET_PANEL_GAP),
        ) {
            // Scrolling: the pinned panels leave the scrollbar's band empty so every footer lines up.
            val pinned = if (fits) {
                Modifier.weight(1f)
            } else {
                Modifier.width(FLEET_PANEL_MIN_WIDTH).padding(bottom = SCROLLBAR_GAP + scrollbarThickness)
            }
            TotalPanel(
                clusterCount = summaries.size,
                usage = usage,
                podsCount = clusterInfo?.podsCount,
                podsCapacity = podsCapacity,
                cpuHistory = cpuHistory,
                memHistory = memHistory,
                podsHistory = podsHistory,
                phases = phases,
                totalWarnings = totalWarnings,
                timeWindow = timeWindow,
                scope = scope,
                onToggleExpanded = onToggleExpanded,
                onShowWarnings = onShowWarnings,
                modifier = pinned.fillMaxHeight(),
            )
            if (showClusters) {
                if (fits) {
                    summaries.forEach { summary ->
                        key(summary.sessionId.value) {
                            ClusterPanel(summary, colorOf, timeWindow, onOpenCluster, onShowWarnings, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.weight(1f).horizontalScrollFade(scrollState).horizontalScroll(scrollState),
                            horizontalArrangement = Arrangement.spacedBy(FLEET_PANEL_GAP),
                        ) {
                            summaries.forEach { summary ->
                                key(summary.sessionId.value) {
                                    ClusterPanel(summary, colorOf, timeWindow, onOpenCluster, onShowWarnings, Modifier.width(FLEET_PANEL_MIN_WIDTH).fillMaxHeight())
                                }
                            }
                        }
                        // In a fixed-height box: the bare scrollbar adds no intrinsic height, so the
                        // IntrinsicSize.Min row would squeeze the panels above it by its thickness.
                        Box(Modifier.fillMaxWidth().padding(top = SCROLLBAR_GAP).height(scrollbarThickness)) {
                            HorizontalScrollbar(rememberScrollbarAdapter(scrollState), Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (showTopNodes) {
                TopNodesPanel(topNodes, colorOf, onNodeClick, pinned.fillMaxHeight())
            }
        }
    }
}

/** Space between the scrolling cluster panels and their scrollbar. */
private val SCROLLBAR_GAP = 4.dp

@Composable
private fun CollapsedFleetBar(
    clusterCount: Int,
    usage: ResourceUsageSummary?,
    podsCount: Int?,
    podsCapacity: Int,
    totalWarnings: Int,
    timeWindow: TimeWindow,
    onToggleExpanded: () -> Unit,
    onShowWarnings: (String?) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = 8.dp.kdCorner,
        color = KdSurface,
        border = BorderStroke(kdOutlineWidth, KdBorder),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Expand", onClick = onToggleExpanded)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "All $clusterCount clusters",
                style = MaterialTheme.typography.titleSmall,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text("CPU ${cpuValue(usage)}", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary, maxLines = 1)
            Text("Memory ${memValue(usage)}", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary, maxLines = 1)
            Text("Pods ${podsValue(podsCount, podsCapacity)}", style = MaterialTheme.typography.labelSmall, color = KdTextSecondary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            WarningsChip(totalWarnings, timeWindow) { onShowWarnings(null) }
            Icon(
                painter = painterResource(Res.drawable.keyboard_arrow_down_filled),
                contentDescription = "Expand",
                modifier = Modifier.size(16.dp),
                tint = KdTextSecondary,
            )
        }
    }
}

@Composable
private fun FleetPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = 8.dp.kdCorner, color = KdSurface, border = BorderStroke(kdOutlineWidth, KdBorder)) {
        Column(
            Modifier.fillMaxHeight().padding(12.dp.orCompact(8.dp)),
            verticalArrangement = Arrangement.spacedBy(6.dp.orCompact(4.dp)),
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MetricRow(label: String, fraction: Float?, valueText: String, detail: String, history: List<Float>?) {
    TooltipArea(
        tooltip = { TriageTooltip(detail) },
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary, maxLines = 1, modifier = Modifier.width(52.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(3.dp.kdCorner)
                    .background(KdSurfaceVariant.copy(alpha = 0.4f)),
            ) {
                if (fraction != null) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(usageTier(fraction.coerceIn(0f, 1f)).color()),
                    )
                }
            }
            if (history != null) {
                Spacer(Modifier.width(6.dp))
                // 20 bars plus their gaps overflow 44 dp and clip the oldest, so draw the last 12.
                UsageHistoryBar(history, Modifier.width(44.dp).height(14.dp), maxEntries = 12)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) {
                if (fraction != null) UsageTierGlyph(usageTier(fraction.coerceIn(0f, 1f)))
            }
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = KdTextPrimary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

@Composable
private fun MetricRows(
    usage: ResourceUsageSummary?,
    podsCount: Int?,
    podsCapacity: Int,
    cpuHistory: List<Float>?,
    memHistory: List<Float>?,
    podsHistory: List<Float>?,
) {
    MetricRow(
        label = "CPU",
        fraction = usage?.takeIf { it.metricsAvailable }?.let { usageFraction(it.cpuUsedMillis, it.cpuCapacityMillis) },
        valueText = cpuValue(usage),
        detail = when {
            usage == null -> "Loading"
            !usage.metricsAvailable -> "Metrics server unavailable"
            else -> "${formatCpuCores(usage.cpuUsedMillis)} of ${formatCpuCores(usage.cpuCapacityMillis)}"
        },
        history = cpuHistory,
    )
    MetricRow(
        label = "Memory",
        fraction = usage?.takeIf { it.metricsAvailable }?.let { usageFraction(it.memoryUsedBytes, it.memoryCapacityBytes) },
        valueText = memValue(usage),
        detail = when {
            usage == null -> "Loading"
            !usage.metricsAvailable -> "Metrics server unavailable"
            else -> "${formatMemorySize(usage.memoryUsedBytes)} of ${formatMemorySize(usage.memoryCapacityBytes)}"
        },
        history = memHistory,
    )
    MetricRow(
        label = "Pods",
        fraction = podsCount?.let { usageFraction(it.toLong(), podsCapacity.toLong()) },
        valueText = podsValue(podsCount, podsCapacity),
        detail = when {
            podsCount == null -> "Loading"
            podsCapacity <= 0 -> "$podsCount pods, capacity unknown"
            else -> "$podsCount of $podsCapacity pod slots"
        },
        history = podsHistory,
    )
}

private fun cpuValue(usage: ResourceUsageSummary?): String = when {
    usage == null -> "—"
    !usage.metricsAvailable -> "n/a"
    else -> usageFraction(usage.cpuUsedMillis, usage.cpuCapacityMillis)?.let(::percentText) ?: "—"
}

private fun memValue(usage: ResourceUsageSummary?): String = when {
    usage == null -> "—"
    !usage.metricsAvailable -> "n/a"
    else -> usageFraction(usage.memoryUsedBytes, usage.memoryCapacityBytes)?.let(::percentText) ?: "—"
}

private fun podsValue(count: Int?, capacity: Int): String = when {
    count == null -> "—"
    capacity <= 0 -> "$count"
    else -> percentText(count.toFloat() / capacity)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.PhaseFooter(phases: PodPhaseCounts?, warnings: Int, timeWindow: TimeWindow, onShowWarnings: () -> Unit) {
    // Pushes the footer to the panel bottom so footers align across panels.
    Spacer(Modifier.weight(1f))
    // The bar and the phase line only say what is wrong, and a narrow panel cuts the line: the
    // hover text gives every count.
    val breakdown = phaseBreakdown(phases)
    if (phases != null) {
        TooltipArea(tooltip = { TriageTooltip(breakdown) }) {
            PodStatusBar(phases.running, phases.pending, phases.failed, phases.succeeded)
        }
    } else {
        Spacer(Modifier.height(6.dp))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TooltipArea(tooltip = { TriageTooltip(breakdown) }, modifier = Modifier.weight(1f)) {
            Text(
                phaseSummary(phases),
                style = MaterialTheme.typography.labelSmall,
                color = if ((phases?.failed ?: 0) > 0) KdError else KdTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        WarningsChip(warnings, timeWindow, onShowWarnings)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WarningsChip(count: Int, window: TimeWindow, onClick: () -> Unit) {
    val active = count > 0
    val shape = 4.dp.kdCorner
    val chip = @Composable {
        Row(
            Modifier
                .heightIn(min = 28.dp)
                .clip(shape)
                .border(kdOutlineWidth, if (active) KdWarning else KdBorder, shape)
                .then(if (active) Modifier.clickable(role = Role.Button, onClickLabel = "Show these events", onClick = onClick) else Modifier)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (active) Icon(painterResource(Res.drawable.warning_filled), contentDescription = null, modifier = Modifier.size(12.dp), tint = KdWarning)
            Text(warningsLabel(count, window), style = MaterialTheme.typography.labelSmall, color = if (active) KdWarning else KdTextSecondary, maxLines = 1)
        }
    }
    if (active) {
        TooltipArea(tooltip = { TriageTooltip("Warning and Error events in the last ${window.label}. Click to list them below.") }) { chip() }
    } else {
        chip()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TotalPanel(
    clusterCount: Int,
    usage: ResourceUsageSummary?,
    podsCount: Int?,
    podsCapacity: Int,
    cpuHistory: List<Float>,
    memHistory: List<Float>,
    podsHistory: List<Float>,
    phases: PodPhaseCounts?,
    totalWarnings: Int,
    timeWindow: TimeWindow,
    scope: UsageScope?,
    onToggleExpanded: () -> Unit,
    onShowWarnings: (String?) -> Unit,
    modifier: Modifier,
) {
    FleetPanel(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Collapse", onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "All $clusterCount clusters",
                style = MaterialTheme.typography.titleSmall,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(Res.drawable.keyboard_arrow_up_filled),
                contentDescription = "Collapse",
                modifier = Modifier.size(16.dp),
                tint = KdTextSecondary,
            )
        }
        if (scope != null) {
            TooltipArea(tooltip = { TriageTooltip(scope.note) }) {
                Surface(shape = 4.dp.kdCorner, color = KdInfo.copy(alpha = 0.12f)) {
                    Text(
                        "Namespace-scoped",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdInfo,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        MetricRows(usage, podsCount, podsCapacity, cpuHistory, memHistory, podsHistory)
        PhaseFooter(phases, totalWarnings, timeWindow) { onShowWarnings(null) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClusterPanel(
    summary: AllClustersViewModel.ClusterSummary,
    colorOf: (String) -> Color,
    timeWindow: TimeWindow,
    onOpenCluster: (SessionId) -> Unit,
    onShowWarnings: (String?) -> Unit,
    modifier: Modifier,
) {
    FleetPanel(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open tab") { onOpenCluster(summary.sessionId) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val headerTooltip = if (summary.nodeCount > 0 || summary.namespaceCount > 0) {
                "${summary.contextName}\n${summary.nodeCount} nodes · ${summary.namespaceCount} namespaces"
            } else {
                summary.contextName
            }
            TooltipArea(tooltip = { TriageTooltip(headerTooltip) }, modifier = Modifier.weight(1f)) {
                ClusterNameLabel(
                    summary.contextName,
                    colorOf(summary.contextName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    dotSize = 10.dp,
                )
            }
            val status = when {
                summary.isConnecting -> "Connecting…" to KdWarning
                !summary.isConnected -> "Disconnected" to KdError
                else -> null
            }
            if (status != null) {
                Spacer(Modifier.width(6.dp))
                Text(status.first, style = MaterialTheme.typography.labelSmall, color = status.second, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(Res.drawable.open_in_new_filled),
                contentDescription = "Open ${summary.contextName} tab",
                modifier = Modifier.size(14.dp),
                tint = KdTextSecondary,
            )
        }
        summary.namespace?.let { ns ->
            Surface(shape = 4.dp.kdCorner, color = KdInfo.copy(alpha = 0.12f)) {
                Text(
                    "Namespace: $ns",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdInfo,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        MetricRows(summary.usage, summary.podsCount, summary.podsCapacity, null, null, null)
        PhaseFooter(summary.phaseCounts, summary.recentErrorCount, timeWindow) { onShowWarnings(summary.contextName) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopNodesPanel(
    topNodes: List<AllClustersViewModel.ClusterNodeUsage>,
    colorOf: (String) -> Color,
    onNodeClick: (AllClustersViewModel.ClusterNodeUsage) -> Unit,
    modifier: Modifier,
) {
    FleetPanel(modifier) {
        Text(
            "Top nodes by pressure",
            style = MaterialTheme.typography.titleSmall,
            color = KdTextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        topNodes.forEach { node ->
            // A narrow panel cuts the node name; the hover text gives it with its cluster.
            TooltipArea(tooltip = { TriageTooltip("${node.usage.nodeName}\n${node.contextName}") }) {
                TopNodeRow(
                    node.usage,
                    onClick = { onNodeClick(node) },
                    leading = {
                        ClusterDot(colorOf(node.contextName), contentDescription = node.contextName)
                        Spacer(Modifier.width(6.dp))
                    },
                )
            }
        }
    }
}
