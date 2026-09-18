package com.kubekubedashdash.ui.screens.events.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.slf4j.LoggerFactory

class EventsScreenViewModel(
    reactiveClient: ReactiveKubeClient,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(EventsScreenViewModel::class.java)

    private val _selected = MutableStateFlow<EventInfo?>(null)
    val selected: StateFlow<EventInfo?> = _selected.asStateFlow()

    // null = "no explicit allowlist" (= show all). Non-null = user-chosen
    // allowlist. Survives screen navigation because this VM is session-scoped.
    private val _typeFilter = MutableStateFlow<Set<String>?>(null)
    val typeFilter: StateFlow<Set<String>?> = _typeFilter.asStateFlow()

    private val _nodeFilter = MutableStateFlow<Set<String>?>(null)
    val nodeFilter: StateFlow<Set<String>?> = _nodeFilter.asStateFlow()

    fun setTypeFilter(value: Set<String>?) {
        _typeFilter.value = value
    }

    fun setNodeFilter(value: Set<String>?) {
        _nodeFilter.value = value
    }

    private var pendingSelectUid: String? = null

    val state: StateFlow<ResourceState<List<EventInfo>>> = reactiveClient.events
        .onEach { s -> if (s is ResourceState.Success) resolvePending(s.data) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    fun setParams(selectEventUid: String? = null) {
        _selected.value = null
        pendingSelectUid = selectEventUid
        // Resolve against the snapshot already in hand: the informer replays
        // its current value when the screen subscribes, which happens BEFORE
        // this call — on a quiet cluster no further emission may ever arrive,
        // and the pending uid would wait forever on an event that never comes.
        if (selectEventUid != null) {
            (state.value as? ResourceState.Success)?.let { resolvePending(it.data) }
        }
    }

    /** A manual row click supersedes any unresolved jump-to-event target. */
    fun dismissPendingSelection() {
        pendingSelectUid = null
    }

    private fun resolvePending(current: List<EventInfo>) {
        val uid = pendingSelectUid ?: return
        // Keep the pending uid until the event actually appears: a snapshot
        // that lacks it (the stale value of a previous visit, a pre-switch
        // replay) must not consume the jump — the next one may carry it.
        val resolved = current.firstOrNull { it.uid == uid }
        if (resolved == null) {
            log.debug("Pending event selection uid={} not in snapshot of {} events; keeping", uid, current.size)
            return
        }
        log.debug("Pending event selection resolved uid={}", uid)
        revealInFilters(resolved)
        _selected.value = resolved
        pendingSelectUid = null
    }

    // A jump must land on a visible row: an allowlist left over from an
    // earlier visit (Warning-only, a single node) that would hide the target
    // is cleared. One that already shows it is kept.
    private fun revealInFilters(event: EventInfo) {
        if (filterHides(_typeFilter.value, event.type)) _typeFilter.value = null
        if (filterHides(_nodeFilter.value, event.nodeFilterKey())) _nodeFilter.value = null
    }
}

/** True when a non-null allowlist would hide [value]. */
internal fun filterHides(filter: Set<String>?, value: String): Boolean = filter != null && value !in filter

/** The key the Events screen's node filter matches on: an event with no source host files under "-". */
internal fun EventInfo.nodeFilterKey(): String = node.ifEmpty { "-" }
