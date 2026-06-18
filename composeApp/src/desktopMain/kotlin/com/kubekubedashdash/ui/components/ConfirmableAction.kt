package com.kubekubedashdash.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the in-flight / error state for one confirmable cluster action and runs it
 * with the correct threading: the suspending, [Result]-returning client call runs on
 * [Dispatchers.IO] while the Compose snapshot-state writes (and [onSuccess]) resume on
 * the EDT-confined caller scope.
 *
 * Replaces the per-dialog `var inFlight` / `var error` + `launch { withContext(IO){…};
 * fold(…) }` boilerplate that was hand-written at every action dialog (audit A5), and
 * keeps the off-EDT-dispatch fix (audit F1) in a single place so it cannot regress
 * site-by-site. Re-entrant [run] calls are ignored while one is already in flight.
 */
@Stable
class ConfirmableAction internal constructor(
    private val scope: CoroutineScope,
) {
    var inFlight by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)

    fun <T> run(
        failureMessage: String = "Action failed",
        block: suspend () -> Result<T>,
        onSuccess: (T) -> Unit,
    ) {
        if (inFlight) return
        inFlight = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            inFlight = false
            result.fold(
                onSuccess = { onSuccess(it) },
                onFailure = { error = it.message ?: failureMessage },
            )
        }
    }

    fun clearError() {
        error = null
    }
}

/** Remember a [ConfirmableAction] bound to a (Main/EDT-confined) composition scope. */
@Composable
fun rememberConfirmableAction(): ConfirmableAction {
    val scope = rememberCoroutineScope()
    return remember { ConfirmableAction(scope) }
}
