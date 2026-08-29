package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import kotlinx.coroutines.delay

/**
 * Generous upper bound on a first list-and-watch. A real cluster with many
 * objects can take a while; this only has to beat "forever".
 */
private const val INFORMER_SYNC_TIMEOUT_MS = 120_000L

/**
 * Waits for [informer]'s initial sync, but gives up instead of spinning forever.
 *
 * fabric8's reflector stops the informer when list-and-watch fails hard
 * (`listSyncAndWatch failed … will stop`). `hasSynced()` then never flips, and a
 * bare `while (!hasSynced()) delay(50)` leaves the caller — and the UI — parked
 * in Loading with nothing reported. Throwing lets the caller's existing
 * `catch (e: Exception)` surface a `ResourceState.Error`.
 */
internal suspend fun awaitInformerSync(informer: SharedIndexInformer<*>, what: String) {
    val deadlineNanos = System.nanoTime() + INFORMER_SYNC_TIMEOUT_MS * 1_000_000L
    while (!informer.hasSynced()) {
        check(informer.isRunning()) { "$what stopped before completing its initial sync" }
        check(System.nanoTime() < deadlineNanos) {
            "$what did not sync within ${INFORMER_SYNC_TIMEOUT_MS}ms"
        }
        delay(50)
    }
}
