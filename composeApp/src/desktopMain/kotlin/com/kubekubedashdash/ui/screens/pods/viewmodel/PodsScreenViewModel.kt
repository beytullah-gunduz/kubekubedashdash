package com.kubekubedashdash.ui.screens.pods.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HealthSeverity
import com.kubekubedashdash.ui.screens.cluster.viewmodel.podStatusSeverity
import com.kubekubedashdash.util.DEFAULT_STALE_TTL
import com.kubekubedashdash.util.ERROR_STALE_TTL
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.STALE_PRUNE_INTERVAL_MS
import com.kubekubedashdash.util.StaleEntry
import com.kubekubedashdash.util.pruneExpiredStale
import com.kubekubedashdash.util.reduceStale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class PodsScreenViewModel(
    private val reactiveClient: ReactiveKubeClient,
    private val now: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val _selectedPod = MutableStateFlow<PodInfo?>(null)
    val selectedPod: StateFlow<PodInfo?> = _selectedPod.asStateFlow()

    // Pods that have left the live watch stream, kept briefly so a deletion or
    // rollout doesn't flicker a row out instantly. Each carries the instant it
    // went stale and a per-entry TTL (error departures linger longer — see
    // ttlFor). Evicted by the prune ticker below or on the next snapshot (see
    // reduceStale). The frozen PodInfo is never refreshed: the pod is gone from
    // the stream, so there's no source left to update it from. The public flow
    // exposes just the PodInfo so callers keep using `s.data + stalePods.values`;
    // `stalePods.keys` is the stale UID set.
    private val _stalePods = MutableStateFlow<Map<String, StaleEntry<PodInfo>>>(emptyMap())
    val stalePods: StateFlow<Map<String, PodInfo>> = _stalePods
        .map { stale -> stale.mapValues { (_, entry) -> entry.info } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // null = "no explicit allowlist" (= show every status). Non-null Set is
    // the explicit allowlist. Survives screen navigation (session-scoped VM).
    private val _statusFilter = MutableStateFlow<Set<String>?>(null)
    val statusFilter: StateFlow<Set<String>?> = _statusFilter.asStateFlow()

    fun setStatusFilter(value: Set<String>?) {
        _statusFilter.value = value
    }

    private var previousPodsByUid: Map<String, PodInfo> = emptyMap()
    private var pendingSelectUid: String? = null

    val state: StateFlow<ResourceState<List<PodInfo>>> = reactiveClient.pods
        .onEach { state ->
            if (state is ResourceState.Loading) {
                _stalePods.value = emptyMap()
                previousPodsByUid = emptyMap()
            }
            if (state is ResourceState.Success) processPodUpdate(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    val resourceUsage: StateFlow<ResourceUsageSummary?> = reactiveClient.resourceUsage
        .map { state -> if (state is ResourceState.Success) state.data else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Age stale entries out independently of informer traffic, so they
        // still expire on a quiet cluster without the user leaving the screen.
        // Loops only while the stale set is non-empty: collectLatest restarts on
        // every empty<->non-empty transition, so the timer parks when idle.
        viewModelScope.launch {
            _stalePods
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { hasStale ->
                    while (hasStale) {
                        delay(STALE_PRUNE_INTERVAL_MS)
                        _stalePods.update { pruneExpiredStale(it, now()) }
                    }
                }
        }
    }

    fun setParams(selectPodUid: String? = null) {
        _selectedPod.value = null
        _stalePods.value = emptyMap()
        previousPodsByUid = emptyMap()
        pendingSelectUid = selectPodUid
    }

    fun selectPod(pod: PodInfo?) {
        _selectedPod.value = if (_selectedPod.value?.uid == pod?.uid) null else pod
    }

    fun clearSelection() {
        _selectedPod.value = null
    }

    // Error departures (OOMKilled / CrashLoopBackOff / Failed / …) are worth
    // catching, so they linger longer on screen than a clean exit.
    private fun ttlFor(pod: PodInfo): Duration = if (podStatusSeverity(pod.status) == HealthSeverity.ERROR) ERROR_STALE_TTL else DEFAULT_STALE_TTL

    private fun processPodUpdate(current: List<PodInfo>) {
        val currentByUid = current.associateBy { it.uid }
        val updatedStale = reduceStale(_stalePods.value, currentByUid, previousPodsByUid, now(), ::ttlFor)

        previousPodsByUid = currentByUid
        _stalePods.value = updatedStale

        val uid = pendingSelectUid
        if (uid != null) {
            _selectedPod.value = currentByUid[uid] ?: updatedStale[uid]?.info
            pendingSelectUid = null
        } else {
            _selectedPod.value = _selectedPod.value?.let { sel ->
                currentByUid[sel.uid] ?: updatedStale[sel.uid]?.info
            }
        }
    }
}
