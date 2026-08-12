package com.kubekubedashdash.ui.screens.pods.viewmodel

import com.kubekubedashdash.models.PodInfo
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

enum class BulkPodVerb(val actionLabel: String, val progressLabel: String, val destructive: Boolean) {
    EVICT("Evict", "Evicting", false),
    DELETE("Delete", "Deleting", true),
}

data class BulkPodFailure(val pod: PodInfo, val reason: String)

sealed interface BulkRunState {
    val verb: BulkPodVerb
    val total: Int

    data class Running(
        override val verb: BulkPodVerb,
        override val total: Int,
        val done: Int,
        val currentPodName: String,
        /** True once cancel() was requested; the in-flight pod still completes. */
        val cancelRequested: Boolean = false,
    ) : BulkRunState

    data class Finished(
        override val verb: BulkPodVerb,
        override val total: Int,
        val attempted: Int,
        val failures: List<BulkPodFailure>,
        val cancelled: Boolean,
    ) : BulkRunState
}

class PodBulkActionRunner(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<BulkRunState?>(null)
    val state: StateFlow<BulkRunState?> = _state.asStateFlow()

    // Sanctioned exception to the state-crosses-threads-via-StateFlow rule:
    // a one-way latch read once per loop iteration; nothing observes it.
    // Keep the @Volatile; do not "upgrade" it to a StateFlow.
    @Volatile private var cancelRequested = false

    /** Starts a run; returns false (and does nothing) if one is in flight or [pods] is empty. */
    fun start(verb: BulkPodVerb, pods: List<PodInfo>, action: suspend (PodInfo) -> Result<Unit>): Boolean {
        if (_state.value is BulkRunState.Running || pods.isEmpty()) return false
        cancelRequested = false
        _state.value = BulkRunState.Running(verb, pods.size, 0, pods.first().name)
        scope.launch {
            val failures = mutableListOf<BulkPodFailure>()
            var attempted = 0
            try {
                for (pod in pods) {
                    if (cancelRequested) break
                    _state.value = BulkRunState.Running(verb, pods.size, attempted, pod.name)
                    val result = try {
                        withContext(io) { action(pod) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // A throwing client call must degrade to a per-pod failure,
                        // never kill the loop: the dialog cannot be dismissed while
                        // Running.
                        Result.failure(e)
                    }
                    attempted++
                    result.onFailure { failures += BulkPodFailure(pod, it.message ?: "${verb.actionLabel} failed") }
                }
            } finally {
                _state.value = BulkRunState.Finished(verb, pods.size, attempted, failures.toList(), cancelRequested)
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
