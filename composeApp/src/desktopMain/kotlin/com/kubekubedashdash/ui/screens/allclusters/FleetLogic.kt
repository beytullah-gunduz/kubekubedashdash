package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel
import java.util.Locale

/** Narrowest width a fleet-strip panel may take before the strip scrolls. */
internal val FLEET_PANEL_MIN_WIDTH: Dp = 240.dp

/** Gap between fleet-strip panels. */
internal val FLEET_PANEL_GAP: Dp = 12.dp

/** Table header plus about six comfortable rows; the event table never gets less. */
internal val MIN_EVENT_TABLE_HEIGHT: Dp = 220.dp

/**
 * Fewest clusters for which the heatmap opens by itself: with two, its two rows repeat what the
 * grouped table's Clusters column already shows.
 */
internal const val HEATMAP_AUTO_OPEN_MIN_CLUSTERS = 3

/** How long after the tab appears data may still open the heatmap; later data only marks the toggle. */
internal const val HEATMAP_ARRIVAL_WINDOW_MS = 1_500L

/** The heatmap is worth opening unasked when 3+ clusters have warnings across 2+ reasons. */
internal fun heatmapWorthOpening(data: HeatmapData): Boolean = data.clusters.size >= HEATMAP_AUTO_OPEN_MIN_CLUSTERS && data.reasons.size >= 2

/**
 * The heatmap's height for [clusters] rows, as AllClustersReasonHeatmap lays it out: 8 dp padding
 * top and bottom, a 16 dp header line, then 28 dp rows 4 dp apart, the content capped at 240 dp.
 */
internal fun estimatedHeatmapHeight(clusters: Int): Dp = minOf(256.dp, 32.dp + 32.dp * clusters)

/** Whether opening the heatmap still leaves the event table its minimum below [chrome] in [viewport]. */
internal fun heatmapFits(viewport: Dp, chrome: Dp, clusters: Int): Boolean = viewport - chrome - estimatedHeatmapHeight(clusters) >= MIN_EVENT_TABLE_HEIGHT

/** True when [panelCount] panels fit side by side at their minimum width. */
internal fun fleetStripFits(available: Dp, panelCount: Int): Boolean = panelCount <= 0 || available >= FLEET_PANEL_MIN_WIDTH * panelCount + FLEET_PANEL_GAP * (panelCount - 1)

/** The [limit] most-pressured nodes over every tab's nodes. */
internal fun rankTopNodes(
    perTab: List<List<AllClustersViewModel.ClusterNodeUsage>>,
    limit: Int = 3,
): List<AllClustersViewModel.ClusterNodeUsage> = perTab.flatten().sortedByDescending { it.usage.pressureFraction }.take(limit)

/**
 * The table filter behind a warnings chip: Warning and Error events of [contextName] (all
 * clusters when null), with Namespace, Reason and Search cleared so the rows match the count.
 */
internal fun warningsFilter(current: EventTriageFilters, contextName: String?): EventTriageFilters = current.copy(
    types = EventTriageFilters.DEFAULT_TYPES,
    clusters = contextName?.let { setOf(it) } ?: emptySet(),
    namespaces = emptySet(),
    reasons = emptySet(),
    searchText = "",
)

/** A warnings chip's text; the count covers Warning and Error events in [window]. */
internal fun warningsLabel(count: Int, window: TimeWindow): String = when (count) {
    0 -> "No warnings in ${window.label}"
    1 -> "1 warning in ${window.label}"
    else -> "$count warnings in ${window.label}"
}

/** One line under a panel's pod bar: what is not running, or that nothing is failing. */
internal fun phaseSummary(phases: PodPhaseCounts?): String {
    if (phases == null) return "—"
    if (phases.running + phases.pending + phases.failed + phases.succeeded == 0) return "No pods"
    val parts = listOfNotNull(
        phases.failed.takeIf { it > 0 }?.let { "$it failed" },
        phases.pending.takeIf { it > 0 }?.let { "$it pending" },
    )
    return if (parts.isEmpty()) "None failed or pending" else parts.joinToString(" · ")
}

/** Every phase count, for the hover text of a panel's pod bar and phase line. */
internal fun phaseBreakdown(phases: PodPhaseCounts?): String = if (phases == null) {
    "Loading"
} else {
    "${phases.running} running · ${phases.pending} pending · ${phases.failed} failed · ${phases.succeeded} succeeded"
}

/** used / capacity, or null when the capacity is unknown. */
internal fun usageFraction(used: Long, capacity: Long): Float? = if (capacity > 0) used.toFloat() / capacity else null

/** The gauges' percentage rule (Charts.kt), locale-independent: "0%", "9.4%", "42%". */
internal fun percentText(fraction: Float): String {
    val clamped = fraction.coerceIn(0f, 1f)
    return when {
        clamped == 0f -> "0%"
        clamped < 0.1f -> String.format(Locale.ROOT, "%.1f%%", clamped * 100)
        else -> "${(clamped * 100).toInt()}%"
    }
}

/** The Type chip reads as a filter only when it narrows the table beyond the default. */
internal fun typeFilterActive(types: Set<String>, available: Set<String>): Boolean = types.isNotEmpty() && types != available && types != EventTriageFilters.DEFAULT_TYPES

/** A trend needs two points: one non-empty bucket draws a lone bar that reads as a glitch. */
internal fun showsTrend(buckets: List<Int>): Boolean = buckets.count { it > 0 } >= 2

/** Where clicking [ev] goes: its own cluster tab's Events screen with it selected; null without a tab. */
internal fun eventJumpTarget(ev: EventInfo): Pair<SessionId, Screen.Main.Events>? = ev.sessionId?.takeIf { it.isNotBlank() }?.let { SessionId(it) to Screen.Main.Events(selectEventUid = ev.uid) }

/** Tooltip for a grouped row spanning several clusters: "name: count" per line, largest first. */
internal fun clusterCountsTooltip(perCluster: Map<String, Int>): String = perCluster.entries.sortedByDescending { it.value }.joinToString("\n") { (name, count) -> "$name: $count" }
