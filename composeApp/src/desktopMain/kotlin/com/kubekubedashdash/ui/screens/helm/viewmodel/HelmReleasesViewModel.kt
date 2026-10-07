package com.kubekubedashdash.ui.screens.helm.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.helm.HelmCacheKey
import com.kubekubedashdash.helm.HelmDecoded
import com.kubekubedashdash.helm.HelmReleaseGroup
import com.kubekubedashdash.helm.HelmReleaseRepository
import com.kubekubedashdash.helm.HelmReleaseSummary
import com.kubekubedashdash.helm.HelmRevisionRef
import com.kubekubedashdash.helm.HelmRevisions
import com.kubekubedashdash.helm.HelmSourceState
import com.kubekubedashdash.helm.combineHelmSources
import com.kubekubedashdash.models.Identifiable
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A list row: one release, its latest revision, and that revision's decode state. */
data class HelmReleaseRow(val group: HelmReleaseGroup, val summary: HelmSummaryState) : Identifiable {
    override val uid: String get() = group.key
    override val fallbackKey: String get() = group.key
    val name: String get() = group.name
    val namespace: String get() = group.namespace
    val latest: HelmRevisionRef get() = group.latest
    val status: String get() = group.latest.status
}

/** What the Chart and App version cells show for a row's latest revision. */
sealed interface HelmSummaryState {
    data object Decoding : HelmSummaryState

    data class Ready(val summary: HelmReleaseSummary) : HelmSummaryState

    /** [transient] = the cluster couldn't be reached (worth another try); otherwise the payload itself is bad. */
    data class Unreadable(val message: String, val transient: Boolean = false) : HelmSummaryState

    data object Gone : HelmSummaryState
}

sealed interface HelmListState {
    data object Loading : HelmListState

    data class Failed(val message: String, val forbidden: Boolean) : HelmListState

    data class Ready(val rows: List<HelmReleaseRow>, val warning: String?) : HelmListState
}

internal fun HelmDecoded<HelmReleaseSummary>.toSummaryState(): HelmSummaryState = when (this) {
    is HelmDecoded.Ok -> HelmSummaryState.Ready(value)
    is HelmDecoded.Failed -> HelmSummaryState.Unreadable(message, transient)
    HelmDecoded.Missing -> HelmSummaryState.Gone
}

/** Text search over a row: name, namespace, status, and the chart and app version once decoded. */
internal fun matchesHelmSearch(row: HelmReleaseRow, query: String): Boolean {
    if (query.isBlank()) return true
    if (row.name.contains(query, ignoreCase = true) ||
        row.namespace.contains(query, ignoreCase = true) ||
        row.status.contains(query, ignoreCase = true)
    ) {
        return true
    }
    val summary = (row.summary as? HelmSummaryState.Ready)?.summary ?: return false
    return summary.chart.contains(query, ignoreCase = true) || summary.appVersion.contains(query, ignoreCase = true)
}

/**
 * The Helm releases list: one row per release (its newest revision, whatever its status),
 * the Chart and App version cells filled in as each revision's payload is decoded, and the
 * selected row.
 *
 * The two source flows are collected only through [state]'s `WhileSubscribed` upstream, never
 * from [viewModelScope] directly: this view model lives as long as the session, and a
 * collector of its own would keep both informers running after the screen is gone.
 */
class HelmReleasesViewModel(
    secrets: StateFlow<ResourceState<List<HelmRevisionRef>>>,
    configMaps: StateFlow<ResourceState<List<HelmRevisionRef>>>,
    val repository: HelmReleaseRepository,
    /** Waits before each retry of a revision whose decode failed transiently; the list's length is the retry count. */
    private val transientRetryDelaysMs: List<Long> = listOf(5_000L, 15_000L, 45_000L),
) : ViewModel() {
    constructor(client: ReactiveKubeClient) : this(client.helmReleaseSecrets, client.helmReleaseConfigMaps, client.helmRepository)

    /** Decode state of each listed release's latest revision, keyed by that revision's cache key. */
    private val summaries = MutableStateFlow<Map<HelmCacheKey, HelmSummaryState>>(emptyMap())

    private val sources = combine(secrets, configMaps, ::combineHelmSources)
        .onEach { s -> if (s is HelmSourceState.Ready) requestSummaries(HelmRevisions.group(s.refs)) }

    val state: StateFlow<HelmListState> = combine(sources, summaries) { s, sums ->
        when (s) {
            HelmSourceState.Loading -> HelmListState.Loading

            is HelmSourceState.Failed -> HelmListState.Failed(s.message, s.forbidden)

            is HelmSourceState.Ready -> HelmListState.Ready(
                rows = HelmRevisions.group(s.refs).map { g ->
                    HelmReleaseRow(g, sums[g.latest.cacheKey] ?: HelmSummaryState.Decoding)
                },
                warning = s.warning,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HelmListState.Loading)

    /** The selected release's group key (stable across upgrades); null when nothing is selected. */
    private val selectedKey = MutableStateFlow<String?>(null)

    // While the list is Loading or Failed the last selection is kept: only a Ready list is mapped.
    val selected: StateFlow<HelmReleaseRow?> = combine(state.filterIsInstance<HelmListState.Ready>(), selectedKey) { s, key ->
        s.rows.firstOrNull { it.uid == key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The detail panel takes the whole content area. Held here, next to the selection it
     * belongs to, so a tab switch (which disposes the screen's composition) does not collapse it.
     */
    private val _detailExpanded = MutableStateFlow(false)
    val detailExpanded: StateFlow<Boolean> = _detailExpanded.asStateFlow()

    /** A click toggles: the selected release again → none; any other → that one. */
    fun select(row: HelmReleaseRow) {
        selectedKey.update { current -> if (current == row.uid) null else row.uid }
    }

    fun clearSelection() {
        selectedKey.value = null
    }

    fun setDetailExpanded(expanded: Boolean) {
        _detailExpanded.value = expanded
    }

    /**
     * Drops decode states for revisions that left the list and starts a decode for each
     * latest revision that has none yet, seeding it from the repository's cache so a
     * revision decoded before shows at once.
     */
    private fun requestSummaries(groups: List<HelmReleaseGroup>) {
        val latest = groups.map { it.latest }
        val keys = latest.map { it.cacheKey }.toSet()
        val missing = latest.filter { it.cacheKey !in summaries.value }
        val seeded = missing.associate { ref ->
            ref.cacheKey to (repository.cachedSummary(ref)?.toSummaryState() ?: HelmSummaryState.Decoding)
        }
        summaries.update { m -> m.filterKeys { it in keys } + seeded }
        missing.filter { seeded.getValue(it.cacheKey) == HelmSummaryState.Decoding }.forEach { ref ->
            viewModelScope.launch {
                var result = repository.summary(ref)
                // A network blip must not read as a corrupt payload: the repository doesn't cache a
                // transient failure, so ask again a few times (the row stays Decoding meanwhile).
                var retries = 0
                while (result is HelmDecoded.Failed && result.transient &&
                    retries < transientRetryDelaysMs.size && ref.cacheKey in summaries.value
                ) {
                    delay(transientRetryDelaysMs[retries++])
                    // The revision may have left the list while we waited.
                    if (ref.cacheKey !in summaries.value) return@launch
                    result = repository.summary(ref)
                }
                val decoded = result.toSummaryState()
                // A revision that left the list meanwhile must not come back as a ghost entry.
                summaries.update { m -> if (ref.cacheKey in m) m + (ref.cacheKey to decoded) else m }
            }
        }
    }
}
