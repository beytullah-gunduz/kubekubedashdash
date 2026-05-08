package com.kubekubedashdash.ui.screens.cluster.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodPhaseCounts
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.formatAge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.format.DateTimeParseException

internal const val CLUSTER_OVERVIEW_RECENT_LIMIT = 10
internal const val CLUSTER_OVERVIEW_TOP_NODES = 3
internal const val CLUSTER_OVERVIEW_HISTORY_SIZE = 20

private const val AGE_TICK_INTERVAL_MS = 10_000L

// Window over which Warning/Error events count toward the cluster health
// banner. Older events stay visible in the Recent Events card but stop
// driving the banner so a single past blip doesn't pin it amber forever.
private const val HEALTH_WARNING_WINDOW_SECONDS = 15L * 60L

// Per-node CPU-or-memory utilisation that flips it into "under pressure" on
// the banner. 0.90 is intentionally past the comfortable-burst zone — at 0.85
// many bursty workloads (CI runners, batch jobs) would stay flagged
// continuously. Soft signal → WARNING tier, never CRITICAL.
private const val NODE_PRESSURE_THRESHOLD = 0.90f

class ClusterOverviewViewModel(
    private val reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    /**
     * Aggregate `clusterInfo` state — used for surfacing connection errors
     * via [ResourceErrorMessage]. The screen no longer gates its scaffold
     * on this, so `Loading` is a normal pre-sync state, not a "show
     * spinner" trigger. Per-card flows below populate the scaffold
     * incrementally as each informer syncs.
     */
    val state: StateFlow<ResourceState<ClusterInfo>> = reactiveClient.clusterInfo
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    // ── Per-card counts (each fires as soon as its informer syncs) ──────────────

    val nodesCount: StateFlow<Int?> = reactiveClient.nodes
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val namespacesCount: StateFlow<Int?> = reactiveClient.namespaceNames
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val deploymentsCount: StateFlow<Int?> = reactiveClient.deployments
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val servicesCount: StateFlow<Int?> = reactiveClient.services
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podPhaseCounts: StateFlow<PodPhaseCounts?> = reactiveClient.pods
        .map { s ->
            if (s is ResourceState.Success) {
                PodPhaseCounts(
                    running = s.data.count { it.phase == "Running" },
                    pending = s.data.count { it.phase == "Pending" },
                    failed = s.data.count { it.phase == "Failed" },
                    succeeded = s.data.count { it.phase == "Succeeded" },
                )
            } else {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Usage card data ─────────────────────────────────────────────────────────

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _podsHistory = MutableStateFlow<List<Float>>(emptyList())
    val podsHistory: StateFlow<List<Float>> = _podsHistory.asStateFlow()

    val resourceUsage: StateFlow<ResourceUsageSummary?> = reactiveClient.resourceUsage
        .onEach { s ->
            val u = if (s is ResourceState.Success) s.data else null
            if (u != null && u.metricsAvailable) {
                val cpuF = if (u.cpuCapacityMillis > 0) u.cpuUsedMillis.toFloat() / u.cpuCapacityMillis else 0f
                val memF = if (u.memoryCapacityBytes > 0) u.memoryUsedBytes.toFloat() / u.memoryCapacityBytes else 0f
                _cpuHistory.update { (it + cpuF).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE) }
                _memHistory.update { (it + memF).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE) }
            }
        }
        .map { s -> if (s is ResourceState.Success) s.data else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podsCapacity: StateFlow<Int> = reactiveClient.nodes
        .map { s ->
            if (s is ResourceState.Success) {
                s.data.sumOf { it.pods.toIntOrNull() ?: 0 }
            } else {
                0
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val podsCount: StateFlow<Int?> = reactiveClient.pods
        .map { (it as? ResourceState.Success)?.data?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val podsLoaded: StateFlow<Boolean> = combine(podsCount, podsCapacity) { c, cap ->
        val cInt = c ?: 0
        if (cap > 0) {
            val frac = cInt.toFloat() / cap.toFloat()
            _podsHistory.update { (it + frac).takeLast(CLUSTER_OVERVIEW_HISTORY_SIZE) }
        }
        cap > 0 || cInt > 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val topNodesByPressure: StateFlow<List<NodeResourceUsage>> = reactiveClient.nodeUsages
        .map { byName ->
            byName.values
                .sortedByDescending { it.pressureFraction }
                .take(CLUSTER_OVERVIEW_TOP_NODES)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Recent activity lists ───────────────────────────────────────────────────

    // Wake-up signal that re-runs the combine blocks below every
    // AGE_TICK_INTERVAL_MS so age strings refresh on a wall-clock cadence.
    // The emitted value is intentionally Unit — `Instant.now()` is read
    // fresh inside each combine lambda instead, so a fresh event arriving
    // between ticks is formatted against an up-to-the-millisecond now,
    // not a stale tick instant (which would yield negative durations).
    private val ageTicker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(AGE_TICK_INTERVAL_MS)
        }
    }

    // Recent lists are sorted **severity-first**, then timestamp. The cluster
    // overview is a triage surface — when something is wrong, the user wants
    // the failing pod / NotReady node / Warning event to surface in the 10
    // visible rows even if 50 healthy newer ones would otherwise fill the
    // slice. Severity tier descending, then creation/last-seen descending
    // within tier preserves "newest first" inside each severity band.

    val recentNodes: StateFlow<RecentSlice<NodeInfo>> = combine(reactiveClient.nodes, ageTicker) { s, _ ->
        val now = Instant.now()
        sliceRecent(s.refreshNodeAges(now)) {
            sortedWith(
                compareByDescending<NodeInfo> { nodeStatusSeverity(it.status) }
                    .thenByDescending { it.creationTimestamp },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    val recentPods: StateFlow<RecentSlice<PodInfo>> = combine(reactiveClient.pods, ageTicker) { s, _ ->
        val now = Instant.now()
        sliceRecent(s.refreshPodAges(now)) {
            sortedWith(
                compareByDescending<PodInfo> { podStatusSeverity(it.status) }
                    .thenByDescending { it.creationTimestamp },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    val recentEvents: StateFlow<RecentSlice<EventInfo>> = combine(reactiveClient.events, ageTicker) { s, _ ->
        val now = Instant.now()
        sliceRecent(s.refreshEventAges(now)) {
            sortedWith(
                compareByDescending<EventInfo> { eventTypeSeverity(it.type) }
                    .thenByDescending { it.lastSeenTimestamp },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecentSlice.empty())

    // ── Cluster health summary (drives the banner above summary cards) ──────────
    //
    // Combines pod statuses, node conditions, deployment availability, node
    // pressure, and recent Warning/Error events into a single
    // ClusterHealthSummary. The banner reads only this flow so adding new
    // signals is one combine source plus one field — UI doesn't change.
    //
    // ageTicker is folded into the events source so the warning-window
    // recompute fires every AGE_TICK_INTERVAL_MS even if no informer emits.
    // Otherwise an event aging past the 15-min cutoff wouldn't decrement the
    // count until the next event arrived.
    //
    // Emits null until any of the four informers (pods/nodes/events/
    // deployments) has synced. That avoids flashing "All healthy" during the
    // initial connect, which would be a false positive (we just haven't seen
    // the failing resources yet). nodeUsages is excluded from the gate
    // because metrics-server may legitimately be absent on a cluster — we
    // shouldn't block the banner forever waiting for it.

    private val tickedEvents: Flow<ResourceState<List<EventInfo>>> =
        combine(reactiveClient.events, ageTicker) { e, _ -> e }

    val health: StateFlow<ClusterHealthSummary?> = combine(
        reactiveClient.pods,
        reactiveClient.nodes,
        tickedEvents,
        reactiveClient.deployments,
        reactiveClient.nodeUsages,
    ) { podsState, nodesState, eventsState, deploymentsState, nodeUsages ->
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
                nodesUnderPressure = nodeUsages.values.count { it.pressureFraction >= NODE_PRESSURE_THRESHOLD },
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun ResourceState<List<NodeInfo>>.refreshNodeAges(now: Instant): ResourceState<List<NodeInfo>> = if (this is ResourceState.Success) {
        ResourceState.Success(data.map { it.copy(age = formatAge(it.creationTimestamp, now)) })
    } else {
        this
    }

    private fun ResourceState<List<PodInfo>>.refreshPodAges(now: Instant): ResourceState<List<PodInfo>> = if (this is ResourceState.Success) {
        ResourceState.Success(data.map { it.copy(age = formatAge(it.creationTimestamp, now)) })
    } else {
        this
    }

    private fun ResourceState<List<EventInfo>>.refreshEventAges(now: Instant): ResourceState<List<EventInfo>> = if (this is ResourceState.Success) {
        ResourceState.Success(data.map { it.copy(lastSeen = formatAge(it.lastSeenTimestamp, now)) })
    } else {
        this
    }

    private fun <T> sliceRecent(
        s: ResourceState<List<T>>,
        sort: List<T>.() -> List<T>,
    ): RecentSlice<T> = when (s) {
        is ResourceState.Success -> {
            val all = s.data.sort()
            RecentSlice(items = all.take(CLUSTER_OVERVIEW_RECENT_LIMIT), total = all.size, loading = false, errorMessage = null)
        }

        is ResourceState.Loading -> RecentSlice(emptyList(), total = 0, loading = true, errorMessage = null)

        is ResourceState.Error -> RecentSlice(emptyList(), total = 0, loading = false, errorMessage = s.message)
    }
}

data class RecentSlice<T>(
    val items: List<T>,
    val total: Int,
    val loading: Boolean,
    val errorMessage: String?,
) {
    companion object {
        fun <T> empty(): RecentSlice<T> = RecentSlice(emptyList(), 0, loading = true, errorMessage = null)
    }
}

/**
 * Aggregated cluster health for the overview banner. Counts come straight
 * from the underlying informers — no thresholding beyond the 15-min window
 * for warning events. `level` is derived so the banner doesn't have to
 * re-encode the precedence rules.
 */
data class ClusterHealthSummary(
    val podsInError: Int,
    val nodesNotReady: Int,
    val deploymentsDegraded: Int = 0,
    val nodesUnderPressure: Int = 0,
    val recentWarnings: Int = 0,
) {
    // CRITICAL = something is actively failing (pods crashing, nodes gone).
    // WARNING = soft signals (deployments under-replicated mid-rollout, hot
    // nodes, recent warning events) — the cluster is up but worth a look.
    val level: HealthLevel = when {
        podsInError > 0 || nodesNotReady > 0 -> HealthLevel.CRITICAL
        deploymentsDegraded > 0 || nodesUnderPressure > 0 || recentWarnings > 0 -> HealthLevel.WARNING
        else -> HealthLevel.HEALTHY
    }
}

enum class HealthLevel { HEALTHY, WARNING, CRITICAL }

// Order matters: ordinal drives the recent-activity sort. ERROR > WARNING > OK.
internal enum class HealthSeverity { OK, WARNING, ERROR }

internal fun podStatusSeverity(status: String): HealthSeverity = when (status.lowercase()) {
    "failed", "error", "crashloopbackoff", "imagepullbackoff",
    "errimagepull", "oomkilled", "terminated", "notready",
    -> HealthSeverity.ERROR

    "pending", "waiting", "containercreating", "terminating" -> HealthSeverity.WARNING

    else -> HealthSeverity.OK
}

/**
 * Canonical error-tier pod status strings, in the camel-cased form
 * `PodInfo.status` reports them. The PodsScreen status filter compares
 * against the literal status string (case-sensitive), so this set has to
 * mirror what's actually produced upstream — keep in sync with
 * [podStatusSeverity]'s ERROR branch above when adding new states.
 */
fun errorPodStatuses(): Set<String> = setOf(
    "Failed",
    "Error",
    "CrashLoopBackOff",
    "ImagePullBackOff",
    "ErrImagePull",
    "OOMKilled",
    "Terminated",
    "NotReady",
)

internal fun nodeStatusSeverity(status: String): HealthSeverity = when (status.lowercase()) {
    "ready", "true" -> HealthSeverity.OK
    "notready", "false" -> HealthSeverity.ERROR
    else -> HealthSeverity.WARNING
}

internal fun eventTypeSeverity(type: String): HealthSeverity = when (type.lowercase()) {
    "warning" -> HealthSeverity.WARNING

    // Kubernetes core only emits Normal/Warning, but custom controllers can
    // emit Error — treat it as WARNING for severity purposes (the banner
    // doesn't need a separate tier for an extremely rare case).
    "error" -> HealthSeverity.WARNING

    else -> HealthSeverity.OK
}

// `ready` is the "X/Y" string `kubectl get deploy` shows. If X < Y and Y > 0
// the deployment isn't fully serving its desired replica count. This will
// flicker amber during normal rollouts (kubectl rollout restart, scale up)
// — that's accepted noise for the real signal of "deployment is stuck under
// quorum". A future iteration could exempt deployments younger than a
// minute, but creationTimestamp here reflects the Deployment object, not
// the in-flight ReplicaSet, so it isn't a reliable rollout indicator.
internal fun deploymentDegraded(d: DeploymentInfo): Boolean {
    val parts = d.ready.split('/')
    if (parts.size != 2) return false
    val ready = parts[0].toIntOrNull() ?: return false
    val desired = parts[1].toIntOrNull() ?: return false
    return desired > 0 && ready < desired
}

private fun parseInstantOrNull(s: String): Instant? = if (s.isBlank()) {
    null
} else {
    try {
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}
