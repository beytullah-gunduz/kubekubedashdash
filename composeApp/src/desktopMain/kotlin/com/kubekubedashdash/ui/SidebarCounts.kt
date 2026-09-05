package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.ui.screens.cluster.viewmodel.ClusterHealthSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HEALTH_WARNING_WINDOW_SECONDS
import com.kubekubedashdash.ui.screens.cluster.viewmodel.errorPodStatuses

/** Which color a [SidebarCount] renders in — KdError or KdWarning, resolved by the caller. */
enum class CountSeverity { ERROR, WARNING }

/** A trailing count on a rail item, and the pre-filtered screen it opens when clicked. */
data class SidebarCount(
    val value: Int,
    val severity: CountSeverity,
    val target: Screen.Main,
    val description: String,
)

/**
 * Counts worth showing on the rail, keyed by [NavKind.key]. Absent when zero
 * or when [health] has not loaded yet. Exactly the three signals the cluster
 * health banner already surfaces (`ClusterOverview.kt`) — the targets here
 * are built from the same instances so the click-to-prefilter behaviour
 * matches the banner exactly.
 */
fun sidebarCounts(health: ClusterHealthSummary?): Map<String, SidebarCount> {
    if (health == null) return emptyMap()
    val counts = mutableMapOf<String, SidebarCount>()

    if (health.podsInError > 0) {
        val n = health.podsInError
        counts["Pods"] = SidebarCount(
            value = n,
            severity = CountSeverity.ERROR,
            target = Screen.Main.Pods(statusFilter = errorPodStatuses()),
            description = "$n ${pluralize(n, "pod", "pods")} failing — click to filter",
        )
    }

    if (health.nodesNotReady > 0) {
        val n = health.nodesNotReady
        counts["Nodes"] = SidebarCount(
            value = n,
            severity = CountSeverity.ERROR,
            target = Screen.Main.Nodes(statusFilter = setOf("NotReady")),
            description = "$n ${pluralize(n, "node", "nodes")} not ready — click to filter",
        )
    }

    if (health.recentWarnings > 0) {
        val n = health.recentWarnings
        val windowMinutes = HEALTH_WARNING_WINDOW_SECONDS / 60
        counts["Events"] = SidebarCount(
            value = n,
            severity = CountSeverity.WARNING,
            target = Screen.Main.Events(typeFilter = setOf("Warning", "Error")),
            description = "$n ${pluralize(n, "warning event", "warning events")} " +
                "in the last $windowMinutes minutes — click to filter",
        )
    }

    return counts
}

private fun pluralize(n: Int, singular: String, plural: String): String = if (n == 1) singular else plural
