package com.kubekubedashdash.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One grouped-action verb as shown in the bulk bar and dialog. */
data class BulkVerb(val actionLabel: String, val progressLabel: String, val destructive: Boolean)

/** The canonical verbs; screens compare by identity in `when` branches. */
object BulkVerbs {
    val Delete = BulkVerb("Delete", "Deleting", destructive = true)
    val Evict = BulkVerb("Evict", "Evicting", destructive = false)
    val Restart = BulkVerb("Restart", "Restarting", destructive = false)
    val Cordon = BulkVerb("Cordon", "Cordoning", destructive = false)
    val Uncordon = BulkVerb("Uncordon", "Uncordoning", destructive = false)
    val Drain = BulkVerb("Drain", "Draining", destructive = true)
}

data class BulkFailure<out T>(val item: T, val reason: String)

sealed interface BulkRunState<out T> {
    val verb: BulkVerb
    val total: Int

    data class Running(
        override val verb: BulkVerb,
        override val total: Int,
        val done: Int,
        val currentItemLabel: String,
        /** True once cancel() was requested; the in-flight item still completes. */
        val cancelRequested: Boolean = false,
    ) : BulkRunState<Nothing>

    data class Finished<out T>(
        override val verb: BulkVerb,
        override val total: Int,
        val attempted: Int,
        val failures: List<BulkFailure<T>>,
        val cancelled: Boolean,
    ) : BulkRunState<T>
}

class BulkActionRunner<T>(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<BulkRunState<T>?>(null)
    val state: StateFlow<BulkRunState<T>?> = _state.asStateFlow()

    // Sanctioned exception to the state-crosses-threads-via-StateFlow rule:
    // a one-way latch read once per loop iteration; nothing observes it.
    // Keep the @Volatile; do not "upgrade" it to a StateFlow.
    @Volatile private var cancelRequested = false

    /** Starts a run; returns false (and does nothing) if one is in flight or [items] is empty. */
    fun start(
        verb: BulkVerb,
        items: List<T>,
        itemLabel: (T) -> String,
        action: suspend (T) -> Result<Unit>,
    ): Boolean {
        if (_state.value is BulkRunState.Running || items.isEmpty()) return false
        cancelRequested = false
        _state.value = BulkRunState.Running(verb, items.size, 0, itemLabel(items.first()))
        scope.launch {
            val failures = mutableListOf<BulkFailure<T>>()
            var attempted = 0
            try {
                for (item in items) {
                    if (cancelRequested) break
                    _state.value = BulkRunState.Running(verb, items.size, attempted, itemLabel(item))
                    val result = try {
                        withContext(io) { action(item) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // A throwing client call must degrade to a per-item failure,
                        // never kill the loop: the dialog cannot be dismissed while
                        // Running.
                        Result.failure(e)
                    }
                    attempted++
                    result.onFailure { failures += BulkFailure(item, it.message ?: "${verb.actionLabel} failed") }
                }
            } finally {
                _state.value = BulkRunState.Finished(verb, items.size, attempted, failures.toList(), cancelRequested)
            }
        }
        return true
    }

    /** Stops issuing further actions; the in-flight one completes. */
    fun cancel() {
        cancelRequested = true
        // Reflect the request in observable state so the UI can disable the
        // Stop control. The loop emits no further Running states after the
        // latch is set, so this cannot be overwritten back to false.
        _state.update { s -> if (s is BulkRunState.Running) s.copy(cancelRequested = true) else s }
    }

    /** Dismisses a Finished result. No-op while Running. */
    fun clear() {
        if (_state.value is BulkRunState.Finished) _state.value = null
    }
}
