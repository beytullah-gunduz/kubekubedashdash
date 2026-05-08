package com.kubekubedashdash.ui.screens.cluster.viewmodel

import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.format.DateTimeParseException

// Window over which Warning/Error events count toward cluster health.
// Older events stay visible in the Recent Events card but stop driving the
// banner/badge so a single past blip doesn't pin the indicator amber forever.
private const val HEALTH_WARNING_WINDOW_SECONDS = 15L * 60L

// Per-node CPU-or-memory utilisation that flips it into "under pressure".
// 0.90 is intentionally past the comfortable-burst zone — at 0.85 many bursty
// workloads (CI runners, batch jobs) would stay flagged continuously. Soft
// signal → WARNING tier, never CRITICAL.
private const val NODE_PRESSURE_THRESHOLD = 0.90f

private const val HEALTH_TICK_INTERVAL_MS = 10_000L

/**
 * Cluster-wide health summary derived from the live informers. Owned by
 * the helper so both [SessionViewModel.clusterHealth] (drives the sidebar
 * dot, lives for the session lifetime) and
 * [ClusterOverviewViewModel.health] (drives the banner + summary card
 * badges, only emits while that screen is visible) compute identical
 * values from the same combine logic.
 *
 * Includes its own age ticker so the warning-window count decrements
 * even if no informer emits. Otherwise an event aging past the 15-min
 * cutoff wouldn't drop off until the next event arrived.
 *
 * Emits null until any of the four primary informers (pods/nodes/events/
 * deployments) has synced — that avoids flashing "All healthy" during the
 * initial connect, which would be a false positive. nodeUsages is
 * excluded from the gate because metrics-server may legitimately be
 * absent on a cluster; we shouldn't block forever waiting for it.
 */
internal fun ReactiveKubeClient.clusterHealthFlow(): Flow<ClusterHealthSummary?> {
    val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(HEALTH_TICK_INTERVAL_MS)
        }
    }
    val tickedEvents = combine(events, ticker) { e, _ -> e }

    return combine(
        pods,
        nodes,
        tickedEvents,
        deployments,
        nodeUsages,
    ) { podsState, nodesState, eventsState, deploymentsState, usages ->
        val pods = (podsState as? ResourceState.Success)?.data
        val nodes = (nodesState as? ResourceState.Success)?.data
        val events = (eventsState as? ResourceState.Success)?.data
        val deployments = (deploymentsState as? ResourceState.Success)?.data
        if (pods == null && nodes == null && events == null && deployments == null) {
            null
        } else {
            val cutoff = Instant.now().minusSeconds(HEALTH_WARNING_WINDOW_SECONDS)
            ClusterHealthSummary(
                podsInError = pods?.count { podStatusSeverity(it.status) == HealthSeverity.ERROR } ?: 0,
                nodesNotReady = nodes?.count { nodeStatusSeverity(it.status) == HealthSeverity.ERROR } ?: 0,
                deploymentsDegraded = deployments?.count(::deploymentDegraded) ?: 0,
                nodesUnderPressure = usages.values.count { it.pressureFraction >= NODE_PRESSURE_THRESHOLD },
                recentWarnings = events?.count { e ->
                    val sev = eventTypeSeverity(e.type)
                    if (sev == HealthSeverity.OK) {
                        false
                    } else {
                        val ts = parseInstantOrNull(e.lastSeenTimestamp)
                        ts != null && ts.isAfter(cutoff)
                    }
                } ?: 0,
            )
        }
    }
}

internal fun parseInstantOrNull(s: String): Instant? = if (s.isBlank()) {
    null
} else {
    try {
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}
