package com.kubekubedashdash.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Tracks resources that left the live watch stream and keeps them on screen briefly (so a
 * delete/rollout doesn't flicker a row out), then evicts them after a per-entry TTL. The pure
 * diff/eviction logic stays in StaleEntries; this is the stateful flow wrapper both the Pods and
 * Nodes view models compose. Identity via [keyOf] (uid); [ttlFor] picks the keep-window per entry.
 */
internal class StaleTracker<T>(
    private val scope: CoroutineScope,
    private val keyOf: (T) -> String,
    private val ttlFor: (T) -> Duration,
    private val now: () -> Instant = { Instant.now() },
) {
    private val _stale = MutableStateFlow<Map<String, StaleEntry<T>>>(emptyMap())

    val stale: StateFlow<Map<String, T>> = _stale
        .map { m -> m.mapValues { (_, e) -> e.info } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var previousByKey: Map<String, T> = emptyMap()

    init {
        // Age stale entries out independently of informer traffic, so they
        // still expire on a quiet cluster without the user leaving the screen.
        // Loops only while the stale set is non-empty: collectLatest restarts on
        // every empty<->non-empty transition, so the timer parks when idle.
        scope.launch {
            _stale
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { hasStale ->
                    while (hasStale) {
                        delay(STALE_PRUNE_INTERVAL_MS)
                        _stale.update { pruneExpiredStale(it, now()) }
                    }
                }
        }
    }

    fun reset() {
        _stale.value = emptyMap()
        previousByKey = emptyMap()
    }

    /**
     * Diff a fresh snapshot in; returns the updated [StaleEntry] map so the caller can resolve a
     * just-vanished selection against it. Commits new previous + stale state.
     */
    fun onSnapshot(currentByKey: Map<String, T>): Map<String, StaleEntry<T>> {
        val updated = reduceStale(_stale.value, currentByKey, previousByKey, now(), ttlFor)
        previousByKey = currentByKey
        _stale.value = updated
        return updated
    }
}
