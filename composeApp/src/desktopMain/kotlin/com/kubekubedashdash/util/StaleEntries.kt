package com.kubekubedashdash.util

import java.time.Duration
import java.time.Instant

/**
 * Default time-to-live for a "stale" resource entry — a resource that has
 * vanished from the informer's watch stream but is briefly kept on screen so a
 * deletion or rollout doesn't make a row flicker out instantly. After this
 * window the entry is evicted (see [pruneExpiredStale]).
 */
internal val DEFAULT_STALE_TTL: Duration = Duration.ofSeconds(20)

/**
 * How often the owning ViewModel re-evaluates its stale set to drop expired
 * entries. Deliberately independent of informer events so eviction still
 * happens on a quiet cluster (no watch traffic) without the user leaving the
 * screen.
 */
internal const val STALE_PRUNE_INTERVAL_MS: Long = 1_000L

/**
 * A resource that left the live list, paired with the instant it went stale.
 * [info] is a frozen snapshot: the resource is gone from the watch stream, so
 * nothing refreshes it after this point — which is why callers relabel it
 * (e.g. "Terminating") rather than trusting its last-seen status.
 */
internal data class StaleEntry<T>(val info: T, val staleSince: Instant)

/**
 * Recompute the stale set after a fresh list snapshot. Pure: the clock is
 * passed in via [now] so the eviction window is unit-testable.
 *
 *  - entries whose UID reappeared in [currentByUid] are dropped (resource is back);
 *  - entries older than [ttl] are dropped (expired);
 *  - UIDs present in [previousByUid] but absent from [currentByUid] are added,
 *    stamped [now] (newly vanished).
 *
 * An already-tracked stale UID keeps its original [StaleEntry.staleSince], so
 * the TTL clock isn't reset on every snapshot.
 */
internal fun <T> reduceStale(
    currentStale: Map<String, StaleEntry<T>>,
    currentByUid: Map<String, T>,
    previousByUid: Map<String, T>,
    now: Instant,
    ttl: Duration,
): Map<String, StaleEntry<T>> {
    val cutoff = now.minus(ttl)
    val result = LinkedHashMap<String, StaleEntry<T>>()
    for ((uid, entry) in currentStale) {
        if (uid in currentByUid) continue // reappeared
        if (!entry.staleSince.isAfter(cutoff)) continue // expired
        result[uid] = entry
    }
    for ((uid, info) in previousByUid) {
        if (uid !in currentByUid && uid !in result) {
            result[uid] = StaleEntry(info, now)
        }
    }
    return result
}

/**
 * Drop stale entries older than [ttl]. Used by the periodic prune ticker, which
 * has no new snapshot to diff against — it only ages entries out.
 */
internal fun <T> pruneExpiredStale(
    currentStale: Map<String, StaleEntry<T>>,
    now: Instant,
    ttl: Duration,
): Map<String, StaleEntry<T>> {
    val cutoff = now.minus(ttl)
    return currentStale.filterValues { it.staleSince.isAfter(cutoff) }
}
