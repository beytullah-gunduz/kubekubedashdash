package com.kubekubedashdash.ui.screens.pods.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.ui.components.BulkActionRunner
import com.kubekubedashdash.ui.components.SelectionFunnel
import com.kubekubedashdash.ui.screens.cluster.viewmodel.HealthSeverity
import com.kubekubedashdash.ui.screens.cluster.viewmodel.podStatusSeverity
import com.kubekubedashdash.util.DEFAULT_STALE_TTL
import com.kubekubedashdash.util.ERROR_STALE_TTL
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.StaleTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class PodsScreenViewModel(
    private val reactiveClient: ReactiveKubeClient,
    private val now: () -> Instant = { Instant.now() },
) : ViewModel() {

    private val _selectedPod = MutableStateFlow<PodInfo?>(null)
    val selectedPod: StateFlow<PodInfo?> = _selectedPod.asStateFlow()

    val selection = SelectionFunnel()
    val bulkRunner = BulkActionRunner<PodInfo>(viewModelScope)

    // Error departures (OOMKilled / CrashLoopBackOff / Failed / …) are worth
    // catching, so they linger longer on screen than a clean exit.
    private fun ttlFor(pod: PodInfo): Duration = if (podStatusSeverity(pod.status) == HealthSeverity.ERROR) ERROR_STALE_TTL else DEFAULT_STALE_TTL

    // Pods that have left the live watch stream, kept briefly so a deletion or
    // rollout doesn't flicker a row out instantly. Each carries the instant it
    // went stale and a per-entry TTL (error departures linger longer — see
    // ttlFor). Evicted by the prune ticker in StaleTracker or on the next
    // snapshot (see reduceStale). The frozen PodInfo is never refreshed: the pod
    // is gone from the stream, so there's no source left to update it from. The
    // public flow exposes just the PodInfo so callers keep using
    // `s.data + stalePods.values`; `stalePods.keys` is the stale UID set.
    private val staleTracker = StaleTracker(viewModelScope, { it.uid }, ::ttlFor, now)
    val stalePods: StateFlow<Map<String, PodInfo>> get() = staleTracker.stale

    // null = "no explicit allowlist" (= show every status). Non-null Set is
    // the explicit allowlist. Survives screen navigation (session-scoped VM).
    private val _statusFilter = MutableStateFlow<Set<String>?>(null)
    val statusFilter: StateFlow<Set<String>?> = _statusFilter.asStateFlow()

    fun setStatusFilter(value: Set<String>?) {
        _statusFilter.value = value
    }

    private var pendingSelectUid: String? = null

    val state: StateFlow<ResourceState<List<PodInfo>>> = reactiveClient.pods
        .onEach { state ->
            if (state is ResourceState.Loading) {
                staleTracker.reset()
            }
            if (state is ResourceState.Success) processPodUpdate(state.data)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    val resourceUsage: StateFlow<ResourceUsageSummary?> = reactiveClient.resourceUsage
        .map { state -> if (state is ResourceState.Success) state.data else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setParams(selectPodUid: String? = null) {
        _selectedPod.value = null
        staleTracker.reset()
        pendingSelectUid = selectPodUid
        selection.reset()
        // The runner outlives the screen composition (session-scoped VM). A
        // Finished result left over from a previous visit must not greet the
        // next bulk confirm dialog as a stale summary.
        bulkRunner.clear()
    }

    fun selectPod(pod: PodInfo?) {
        _selectedPod.value = if (_selectedPod.value?.uid == pod?.uid) null else pod
    }

    fun clearSelection() {
        _selectedPod.value = null
    }

    private fun processPodUpdate(current: List<PodInfo>) {
        val currentByUid = current.associateBy { it.uid }
        val updatedStale = staleTracker.onSnapshot(currentByUid)

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
