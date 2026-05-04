package com.kubekubedashdash.ui.screens.allclusters.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.screens.allclusters.EventGroup
import com.kubekubedashdash.ui.screens.allclusters.EventTriageFilters
import com.kubekubedashdash.ui.screens.allclusters.EventTriagePreset
import com.kubekubedashdash.ui.screens.allclusters.GroupKey
import com.kubekubedashdash.ui.screens.allclusters.HeatmapData
import com.kubekubedashdash.ui.screens.allclusters.TimeWindow
import com.kubekubedashdash.ui.screens.allclusters.ViewMode
import com.kubekubedashdash.ui.screens.allclusters.buildBuiltIns
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AllClustersViewModel private constructor() : ViewModel() {

    companion object {
        val instance: AllClustersViewModel by lazy { AllClustersViewModel() }
    }

    data class ClusterSummary(
        val sessionId: SessionId,
        val contextName: String,
        val isConnected: Boolean,
        val isConnecting: Boolean,
        val nodeCount: Int,
        val namespaceCount: Int,
        val recentErrorCount: Int,
    )

    /**
     * Emits the live list of [WorkspaceTab.Cluster] tabs whenever any workspace's
     * tab list changes (new cluster added, cluster closed, window closed, etc.).
     */
    private fun clusterTabsFlow(): Flow<List<WorkspaceTab.Cluster>> = WorkspaceManager.workspaces.flatMapLatest { workspaceList ->
        if (workspaceList.isEmpty()) return@flatMapLatest flowOf(emptyList())
        combine(workspaceList.map { ws -> ws.tabs }) { allTabs ->
            allTabs.flatMap { it.filterIsInstance<WorkspaceTab.Cluster>() }
        }
    }

    /** Merged events from all open sessions, each tagged with its source cluster name. */
    val aggregatedEvents: StateFlow<List<EventInfo>> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                clusterTabs.map { tab ->
                    combine(
                        tab.session.viewModel.selectedContext,
                        tab.session.reactiveClient.events,
                    ) { ctx, state ->
                        val events = (state as? ResourceState.Success)?.data ?: emptyList()
                        events.map {
                            it.copy(
                                cluster = ctx.ifBlank { "?" },
                                sessionId = tab.session.id.value,
                            )
                        }
                    }
                },
            ) { arrays -> arrays.flatMap { it }.sortedByDescending { it.lastSeenTimestamp } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Filter state ─────────────────────────────────────────────────────────────

    private val _filters = MutableStateFlow(EventTriageFilters())
    val filters: StateFlow<EventTriageFilters> = _filters.asStateFlow()

    fun updateFilters(update: (EventTriageFilters) -> EventTriageFilters) {
        _filters.update(update)
    }

    fun resetFilters() {
        _filters.value = EventTriageFilters()
    }

    /**
     * Available cluster names derived from the current loaded events — used to
     * populate the cluster filter dropdown.
     */
    val availableClusters: StateFlow<Set<String>> = aggregatedEvents
        .map { events -> events.mapNotNull { it.cluster }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Available namespaces derived from the current loaded events.
     */
    val availableNamespaces: StateFlow<Set<String>> = aggregatedEvents
        .map { events -> events.map { it.namespace }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Available reasons derived post-time-window, pre-other-filters (per spec decision #9).
     * The search text is debounced separately; reasons use raw filter state.
     */
    val availableReasons: StateFlow<Set<String>> = combine(aggregatedEvents, _filters) { events, f ->
        val cutoff = Instant.now().minusSeconds(f.timeWindow.minutes * 60)
        events
            .filter { ev -> ev.lastSeenTimestamp.isBlank() || parseInstantOrNull(ev.lastSeenTimestamp)?.isAfter(cutoff) == true }
            .map { it.reason }
            .toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Debounced search text to avoid recomputing on every keystroke.
     */
    private val debouncedSearch = _filters
        .map { it.searchText }
        .debounce(180)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Events after applying all active filters. */
    val filteredEvents: StateFlow<List<EventInfo>> = combine(
        aggregatedEvents,
        _filters,
        debouncedSearch,
    ) { events, f, search ->
        val cutoff = Instant.now().minusSeconds(f.timeWindow.minutes * 60)
        val effectiveClusters = f.clusters.ifEmpty { null }
        val effectiveNamespaces = f.namespaces.ifEmpty { null }
        val effectiveReasons = f.reasons.ifEmpty { null }
        events.filter { ev ->
            (f.types.isEmpty() || ev.type in f.types) &&
                (effectiveClusters == null || ev.cluster in effectiveClusters) &&
                (effectiveNamespaces == null || ev.namespace in effectiveNamespaces) &&
                (effectiveReasons == null || ev.reason in effectiveReasons) &&
                (ev.lastSeenTimestamp.isBlank() || parseInstantOrNull(ev.lastSeenTimestamp)?.isAfter(cutoff) == true) &&
                (search.isBlank() || matchesSearch(ev, search))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Grouped events sorted by totalCount desc, ties broken by lastSeenInstant desc. */
    val groupedEvents: StateFlow<List<EventGroup>> = combine(filteredEvents, _filters) { events, filters ->
        buildGroups(events, filters.timeWindow)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Heatmap data: cluster × reason matrix. Applies time-window, type, and namespace
     * filters but intentionally ignores cluster/reason filters so all clusters/reasons
     * are visible in the heatmap regardless of active selection.
     */
    val heatmapData: StateFlow<HeatmapData> = combine(aggregatedEvents, _filters) { events, f ->
        val cutoff = Instant.now().minusSeconds(f.timeWindow.minutes * 60)
        val effectiveNamespaces = f.namespaces.ifEmpty { null }

        // Apply time-window, types, and namespaces — but NOT clusters or reasons.
        val filtered = events.filter { ev ->
            (f.types.isEmpty() || ev.type in f.types) &&
                (effectiveNamespaces == null || ev.namespace in effectiveNamespaces) &&
                (ev.lastSeenTimestamp.isBlank() || parseInstantOrNull(ev.lastSeenTimestamp)?.isAfter(cutoff) == true)
        }

        if (filtered.isEmpty()) return@combine HeatmapData(emptyList(), emptyList(), emptyMap())

        // Build cluster → reason → count map.
        val cellMap = mutableMapOf<Pair<String, String>, Int>()
        for (ev in filtered) {
            val cluster = ev.cluster ?: "?"
            val reason = ev.reason
            val key = cluster to reason
            cellMap[key] = (cellMap[key] ?: 0) + ev.count
        }

        // Top 12 reasons globally, sorted by total count desc, then alphabetically.
        val reasonTotals = cellMap
            .entries
            .groupBy { it.key.second }
            .mapValues { (_, entries) -> entries.sumOf { it.value } }
        val top12Reasons = reasonTotals
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(12)
            .map { it.key }

        // Sorted cluster list.
        val clusters = filtered.mapNotNull { it.cluster }.toSortedSet().toList()

        HeatmapData(
            clusters = clusters,
            reasons = top12Reasons,
            cells = cellMap,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeatmapData(emptyList(), emptyList(), emptyMap()))

    /** Clicking a heatmap cell narrows the cluster and reason filters. */
    fun onHeatmapCellClick(cluster: String, reason: String) {
        updateFilters { f -> f.copy(clusters = setOf(cluster), reasons = setOf(reason)) }
    }

    // ── Cluster summaries ─────────────────────────────────────────────────────────

    /** One summary card per open cluster session. */
    val clusterSummaries: StateFlow<List<ClusterSummary>> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                clusterTabs.map { tab ->
                    combine(
                        tab.session.viewModel.selectedContext,
                        tab.session.viewModel.isConnected,
                        tab.session.viewModel.isConnecting,
                        tab.session.reactiveClient.nodes,
                        tab.session.reactiveClient.clusterInfo,
                    ) { ctx, connected, connecting, nodesState, clusterState ->
                        val info = (clusterState as? ResourceState.Success)?.data
                        val recentErrors = aggregatedEvents.value.count {
                            it.cluster == ctx && (it.type == "Warning" || it.type == "Error")
                        }
                        ClusterSummary(
                            sessionId = tab.session.id,
                            contextName = ctx.ifBlank { "Loading…" },
                            isConnected = connected,
                            isConnecting = connecting,
                            nodeCount = info?.nodesCount ?: 0,
                            namespaceCount = info?.namespacesCount ?: 0,
                            recentErrorCount = recentErrors,
                        )
                    }
                },
            ) { it.toList() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Preset state ──────────────────────────────────────────────────────────────

    private val _userPresets = MutableStateFlow<List<EventTriagePreset>>(emptyList())

    /**
     * Combined list: built-ins (recomputed whenever [clusterSummaries] changes)
     * followed by user-saved presets.
     */
    val presets: StateFlow<List<EventTriagePreset>> = combine(_userPresets, clusterSummaries) { user, summaries ->
        buildBuiltIns(summaries) + user
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildBuiltIns(emptyList()))

    /**
     * Apply a preset by id. Built-in "this-cluster-only" is recomputed at
     * call-time from the current [clusterSummaries].
     */
    fun applyPreset(id: String) {
        val preset = presets.value.firstOrNull { it.id == id } ?: return
        if (id == "this-cluster-only") {
            val summaries = clusterSummaries.value
            val target = summaries.firstOrNull { it.recentErrorCount > 0 }?.contextName
                ?: summaries.firstOrNull()?.contextName
            if (target.isNullOrEmpty()) return
            _filters.value = EventTriageFilters(
                types = setOf("Warning", "Error"),
                clusters = setOf(target),
                namespaces = emptySet(),
                reasons = emptySet(),
                searchText = "",
                timeWindow = TimeWindow.LAST_1H,
                mode = ViewMode.GROUPED,
            )
        } else {
            _filters.value = preset.filters
        }
    }

    /**
     * Save the current filters as a new user preset with the given [name].
     * Name is trimmed and capped at 40 characters.
     */
    fun saveCurrentAsPreset(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isEmpty()) return
        val preset = EventTriagePreset(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            filters = _filters.value,
        )
        _userPresets.value = _userPresets.value + preset
        PreferenceRepository.customPresets = _userPresets.value
    }

    /**
     * Delete a user preset by id. Built-in presets ("critical-only",
     * "this-cluster-only") are silently ignored.
     */
    fun deletePreset(id: String) {
        if (id == "critical-only" || id == "this-cluster-only") return
        _userPresets.value = _userPresets.value.filterNot { it.id == id }
        PreferenceRepository.customPresets = _userPresets.value
    }

    // ── Aggregated stats card data ────────────────────────────────────────────────

    private val _cpuHistory = MutableStateFlow<List<Float>>(emptyList())
    val cpuHistory: StateFlow<List<Float>> = _cpuHistory.asStateFlow()

    private val _memHistory = MutableStateFlow<List<Float>>(emptyList())
    val memHistory: StateFlow<List<Float>> = _memHistory.asStateFlow()

    private val _podsHistory = MutableStateFlow<List<Float>>(emptyList())
    val podsHistory: StateFlow<List<Float>> = _podsHistory.asStateFlow()

    /** Summed cluster-level counts across all open sessions. */
    val aggregatedClusterInfo: StateFlow<ClusterInfo?> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(null)
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.clusterInfo }) { states ->
                val infos = states.mapNotNull { (it as? ResourceState.Success)?.data }
                if (infos.isEmpty()) {
                    null
                } else {
                    ClusterInfo(
                        name = "All Clusters",
                        server = "",
                        version = "",
                        nodesCount = infos.sumOf { it.nodesCount },
                        namespacesCount = infos.sumOf { it.namespacesCount },
                        podsCount = infos.sumOf { it.podsCount },
                        deploymentsCount = infos.sumOf { it.deploymentsCount },
                        servicesCount = infos.sumOf { it.servicesCount },
                        runningPods = infos.sumOf { it.runningPods },
                        pendingPods = infos.sumOf { it.pendingPods },
                        failedPods = infos.sumOf { it.failedPods },
                        succeededPods = infos.sumOf { it.succeededPods },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Summed CPU and memory totals across all open sessions. Updates CPU/mem history as a side-effect. */
    val aggregatedUsage: StateFlow<ResourceUsageSummary?> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(null)
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.resourceUsage }) { states ->
                val summaries = states.mapNotNull { (it as? ResourceState.Success)?.data }
                if (summaries.isEmpty()) {
                    null
                } else {
                    ResourceUsageSummary(
                        cpuUsedMillis = summaries.sumOf { it.cpuUsedMillis },
                        cpuCapacityMillis = summaries.sumOf { it.cpuCapacityMillis },
                        memoryUsedBytes = summaries.sumOf { it.memoryUsedBytes },
                        memoryCapacityBytes = summaries.sumOf { it.memoryCapacityBytes },
                        metricsAvailable = summaries.any { it.metricsAvailable },
                    )
                }
            }
        }
        .onEach { usage ->
            if (usage != null && usage.metricsAvailable) {
                val cpuF = if (usage.cpuCapacityMillis > 0) usage.cpuUsedMillis.toFloat() / usage.cpuCapacityMillis else 0f
                val memF = if (usage.memoryCapacityBytes > 0) usage.memoryUsedBytes.toFloat() / usage.memoryCapacityBytes else 0f
                _cpuHistory.value = (_cpuHistory.value + cpuF).takeLast(20)
                _memHistory.value = (_memHistory.value + memF).takeLast(20)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Sum of allocatable pod slots across all nodes in all open sessions. */
    val aggregatedPodsCapacity: StateFlow<Int> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(0)
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.nodes }) { states ->
                states.sumOf { state ->
                    (state as? ResourceState.Success)?.data
                        ?.sumOf { it.pods.toIntOrNull() ?: 0 } ?: 0
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Top 3 nodes by pressure fraction across all open sessions. */
    val topNodesAcrossAllClusters: StateFlow<List<NodeResourceUsage>> = clusterTabsFlow()
        .flatMapLatest { clusterTabs ->
            if (clusterTabs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(clusterTabs.map { tab -> tab.session.reactiveClient.nodeUsages }) { usageMaps ->
                usageMaps.flatMap { it.values }
                    .sortedByDescending { it.pressureFraction }
                    .take(3)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            combine(aggregatedClusterInfo, aggregatedPodsCapacity) { info, cap ->
                val count = info?.podsCount ?: 0
                if (cap > 0) count.toFloat() / cap else 0f
            }.collect { frac ->
                _podsHistory.value = (_podsHistory.value + frac).takeLast(20)
            }
        }
        viewModelScope.launch {
            PreferenceRepository.customPresets().collect { persisted ->
                _userPresets.value = persisted
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun parseInstantOrNull(ts: String): Instant? = try {
        Instant.parse(ts)
    } catch (_: Exception) {
        null
    }

    private fun matchesSearch(ev: EventInfo, query: String): Boolean {
        val q = query.lowercase()
        return ev.objectRef.lowercase().contains(q) ||
            ev.message.lowercase().contains(q) ||
            ev.reason.lowercase().contains(q)
    }

    private fun buildGroups(events: List<EventInfo>, window: TimeWindow): List<EventGroup> {
        val bucketCount = 12
        // bucketWidth in seconds: total window seconds divided by bucket count
        val bucketWidthSeconds = window.minutes * 60L / bucketCount
        val now = Instant.now()
        val windowStart = now.minusSeconds(window.minutes * 60L)

        val grouped = events.groupBy { GroupKey(it.type, it.reason, it.objectRef) }
        return grouped.map { (key, members) ->
            val latest = members.maxByOrNull { parseInstantOrNull(it.lastSeenTimestamp) ?: Instant.MIN }
            val lastSeenInstant = latest?.let { parseInstantOrNull(it.lastSeenTimestamp) } ?: Instant.MIN
            val perCluster = members
                .groupBy { it.cluster ?: "?" }
                .mapValues { (_, evs) -> evs.sumOf { it.count } }

            // Compute 12-bucket histogram bucketed by lastSeenTimestamp of each member.
            // We bucket by occurrence (1 per member), not by EventInfo.count, because
            // k8s already aggregated repeats into count — we have no per-occurrence timestamps.
            val histogram = MutableList(bucketCount) { 0 }
            members.forEach { ev ->
                val eventInstant = parseInstantOrNull(ev.lastSeenTimestamp) ?: return@forEach
                val offsetSeconds = eventInstant.epochSecond - windowStart.epochSecond
                val bucketIndex = (offsetSeconds / bucketWidthSeconds).toInt().coerceIn(0, bucketCount - 1)
                histogram[bucketIndex]++
            }

            EventGroup(
                key = key,
                totalCount = members.sumOf { it.count },
                lastSeen = latest?.lastSeen ?: "",
                lastSeenInstant = lastSeenInstant,
                latestMessage = latest?.message ?: "",
                perClusterCounts = perCluster,
                members = members,
                bucketHistogram = histogram,
            )
        }.sortedWith(compareByDescending<EventGroup> { it.totalCount }.thenByDescending { it.lastSeenInstant })
    }
}
