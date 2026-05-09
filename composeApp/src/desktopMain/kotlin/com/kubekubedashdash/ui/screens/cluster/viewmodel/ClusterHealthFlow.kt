package com.kubekubedashdash.ui.screens.cluster.viewmodel

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.scan
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
//
// internal so NodesScreen can reuse the threshold for its "Pressure only"
// filter — the count in the banner and the rows shown after the click must
// agree, otherwise users see "3 nodes under pressure" then a list of 5.
internal const val NODE_PRESSURE_THRESHOLD = 0.90f

// How long a deployment has to stay at ready < desired before it counts as
// "degraded" for the banner. Without this, every `kubectl rollout restart` or
// `kubectl scale` would flicker the banner amber for the few seconds it takes
// the new ReplicaSet to come up. 60s is empirically past most rolling
// updates' replica-replacement window without being so long that genuine
// outages stay invisible.
internal const val HEALTH_DEPLOYMENT_STABLE_SECONDS = 60L

private const val HEALTH_TICK_INTERVAL_MS = 10_000L

/**
 * Cluster-wide health summary derived from the live informers. Owned by
 * the helper so both [SessionViewModel.clusterHealth] (drives the sidebar
 * dot, lives for the session lifetime) and the cluster overview banner
 * (which subscribes to the same SessionViewModel value via the router)
 * compute identical values from the same combine logic.
 *
 * The flow includes its own age ticker so the warning-window count and the
 * deployment stable-threshold both decrement even if no informer emits.
 *
 * Emits null until any of the four primary informers (pods/nodes/events/
 * deployments) has synced — that avoids flashing "All healthy" during the
 * initial connect, which would be a false positive. nodeUsages is excluded
 * from the gate because metrics-server may legitimately be absent on a
 * cluster; we shouldn't block forever waiting for it.
 */
internal fun ReactiveKubeClient.clusterHealthFlow(
    stableThresholdSeconds: Long = HEALTH_DEPLOYMENT_STABLE_SECONDS,
    now: () -> Instant = { Instant.now() },
): Flow<ClusterHealthSummary?> {
    val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(HEALTH_TICK_INTERVAL_MS)
        }
    }
    val tickedEvents = combine(events, ticker) { e, _ -> e }
    val tickedDeployments = combine(deployments, ticker) { d, _ -> d }

    // Per-deployment first-degraded timestamps. Drives "is this still
    // degraded after stableThresholdSeconds" without leaking mutable state
    // — scan threads the accumulator through every emission for us.
    val degradationFlow: Flow<DegradationState> = tickedDeployments.scan(
        DegradationState.initial,
    ) { acc, state ->
        reduceDegradation(acc, state, now(), stableThresholdSeconds)
    }

    return combine(
        pods,
        nodes,
        tickedEvents,
        degradationFlow,
        nodeUsages,
    ) { podsState, nodesState, eventsState, degradation, usages ->
        val pods = (podsState as? ResourceState.Success)?.data
        val nodes = (nodesState as? ResourceState.Success)?.data
        val events = (eventsState as? ResourceState.Success)?.data
        if (pods == null && nodes == null && events == null && degradation.stableCount == null) {
            null
        } else {
            val cutoff = now().minusSeconds(HEALTH_WARNING_WINDOW_SECONDS)
            ClusterHealthSummary(
                podsInError = pods?.count { podStatusSeverity(it.status) == HealthSeverity.ERROR } ?: 0,
                nodesNotReady = nodes?.count { nodeStatusSeverity(it.status) == HealthSeverity.ERROR } ?: 0,
                deploymentsDegraded = degradation.stableCount ?: 0,
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

/**
 * Pure-function step of the deployment-degradation scan. Extracted from
 * [clusterHealthFlow] so the stable-threshold semantics can be unit-tested
 * without spinning up a real flow + ticker.
 *
 * Returns the previous accumulator unchanged when the upstream informer
 * isn't in [ResourceState.Success] — `stableCount` stays null (= "not yet
 * synced") until the first successful payload arrives.
 */
internal fun reduceDegradation(
    acc: DegradationState,
    deploymentsState: ResourceState<List<DeploymentInfo>>,
    now: Instant,
    stableThresholdSeconds: Long,
): DegradationState {
    if (deploymentsState !is ResourceState.Success) return acc
    val current = deploymentsState.data.filter(::deploymentDegraded).map { it.uid }.toSet()
    // Carry forward the original first-degraded timestamp for any deployment
    // that's still degraded; entries whose deployments recovered or vanished
    // are dropped, so the next regression starts a fresh clock.
    val updated = current.associateWith { uid -> acc.firstSeen[uid] ?: now }
    val cutoff = now.minusSeconds(stableThresholdSeconds)
    val stable = updated.count { (_, t) -> !t.isAfter(cutoff) }
    return DegradationState(firstSeen = updated, stableCount = stable)
}

/**
 * Accumulator for [reduceDegradation]. `firstSeen` keys by deployment UID;
 * `stableCount` is the number of currently-degraded deployments whose
 * first-degraded timestamp is at least `stableThresholdSeconds` old (i.e.
 * past the rollout-noise window). Null `stableCount` means the deployments
 * informer hasn't produced a Success state yet.
 */
internal data class DegradationState(
    val firstSeen: Map<String, Instant>,
    val stableCount: Int?,
) {
    companion object {
        val initial = DegradationState(emptyMap(), null)
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
