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
