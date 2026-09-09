package com.kubekubedashdash.util

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.StateFlow

/** A list flow that can be restarted on its own: the current informer is closed and a fresh one built and run. */
interface Restartable {
    fun restart()
}

/**
 * What [ReactiveInformerFactory] hands back: the list's [StateFlow], every
 * member delegated, plus [restart]. Declared as a plain `StateFlow` at its
 * 38 call sites; [restartListFlow] recovers the capability where the UI
 * needs it.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class) // StateFlow is @SubclassOptInRequired; coroutines' own ReadonlyStateFlow delegates the same way
internal class RestartableStateFlow<T>(
    upstream: StateFlow<T>,
    private val onRestart: () -> Unit,
) : StateFlow<T> by upstream,
    Restartable {
    override fun restart() = onRestart()
}

/**
 * Restarts [flow] if the factory built it, and says whether it did. A list
 * parked on `Error` — a start failure, a sync failure, a mapping failure —
 * has no other way back before the next connection-version bump restarts
 * every list (review follow-up F3); a screen's Retry calls this. A flow the
 * factory did not build (a view model's derived state, the CRD list) is left
 * alone; a view the factory derived from a list restarts that list.
 */
fun restartListFlow(flow: StateFlow<*>): Boolean {
    val restartable = flow as? Restartable ?: return false
    restartable.restart()
    return true
}
