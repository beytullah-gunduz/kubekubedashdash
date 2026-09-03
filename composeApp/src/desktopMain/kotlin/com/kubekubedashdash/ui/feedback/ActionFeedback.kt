package com.kubekubedashdash.ui.feedback

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ToastKind { Success, Failure, Warning, Info }

/**
 * The inverse of an action that just succeeded. [run] executes off the EDT;
 * the feedback layer then replaces the original toast with [successTitle], or
 * with [failureTitle] plus the failure's message as detail.
 */
class UndoAction(
    val successTitle: String,
    val failureTitle: String,
    val run: suspend () -> Result<Unit>,
)

data class Toast(
    val id: Long,
    val kind: ToastKind,
    val title: String,
    val detail: String? = null,
    val undo: UndoAction? = null,
    /** True from the Undo click until the inverse completes; the toast does not expire meanwhile. */
    val undoInFlight: Boolean = false,
)

/** What action call sites see: fire-and-forget reporting. Each call returns the toast id. */
interface ActionFeedback {
    fun success(title: String, detail: String? = null, undo: UndoAction? = null): Long

    fun failure(title: String, detail: String? = null): Long

    fun warning(title: String, detail: String? = null): Long

    fun info(title: String, detail: String? = null): Long
}

/** Default for compositions without an [ActionFeedbackHost] (screenshots, previews): drops everything. */
object NoActionFeedback : ActionFeedback {
    override fun success(title: String, detail: String?, undo: UndoAction?): Long = -1

    override fun failure(title: String, detail: String?): Long = -1

    override fun warning(title: String, detail: String?): Long = -1

    override fun info(title: String, detail: String?): Long = -1
}

/** Installed per window by [ActionFeedbackHost]; safe to read anywhere. */
val LocalActionFeedback = staticCompositionLocalOf<ActionFeedback> { NoActionFeedback }

/**
 * Per-window toast queue. Every method must be called on [scope]'s thread (the
 * EDT in the app, the test scheduler in tests): the timer map is unguarded by
 * design and the list is only ever mutated from that one thread. The undo
 * inverse alone hops to [io].
 */
class ActionFeedbackState(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ActionFeedback {
    private val _toasts = MutableStateFlow<List<Toast>>(emptyList())
    val toasts: StateFlow<List<Toast>> = _toasts.asStateFlow()

    private var nextId = 1L
    private val timers = HashMap<Long, Job>()

    override fun success(title: String, detail: String?, undo: UndoAction?): Long = show(ToastKind.Success, title, detail, undo, if (undo != null) UNDOABLE_MS else SUCCESS_MS)

    override fun failure(title: String, detail: String?): Long = show(ToastKind.Failure, title, detail, null, FAILURE_MS)

    override fun warning(title: String, detail: String?): Long = show(ToastKind.Warning, title, detail, null, FAILURE_MS)

    override fun info(title: String, detail: String?): Long = show(ToastKind.Info, title, detail, null, SUCCESS_MS)

    private fun show(kind: ToastKind, title: String, detail: String?, undo: UndoAction?, durationMs: Long): Long {
        val id = nextId++
        val toast = Toast(id, kind, title, detail, undo)
        // Newest last (closest to the corner). Over the cap the oldest toast
        // that is not mid-undo goes; an undoing toast is never dropped.
        val next = _toasts.value + toast
        _toasts.value = if (next.size <= MAX_VISIBLE) {
            next
        } else {
            // Search the PRE-EXISTING toasts only: the toast we were just asked
            // to show must never be its own victim, which is what would happen
            // when every older toast is mid-undo.
            val victim = _toasts.value.firstOrNull { !it.undoInFlight }
            if (victim == null) {
                next
            } else {
                timers.remove(victim.id)?.cancel()
                next - victim
            }
        }
        timers[id] = scope.launch {
            delay(durationMs)
            timers.remove(id)
            _toasts.update { current -> current.filterNot { it.id == id && !it.undoInFlight } }
        }
        return id
    }

    fun dismiss(id: Long) {
        timers.remove(id)?.cancel()
        _toasts.update { current -> current.filterNot { it.id == id } }
    }

    /** Runs the toast's inverse. Ignored when the toast is gone, has no inverse, or is already undoing. */
    fun undo(id: Long) {
        val toast = _toasts.value.firstOrNull { it.id == id } ?: return
        val action = toast.undo ?: return
        if (toast.undoInFlight) return
        timers.remove(id)?.cancel()
        _toasts.update { current -> current.map { if (it.id == id) it.copy(undoInFlight = true) else it } }
        scope.launch {
            val result = try {
                withContext(io) { action.run() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            _toasts.update { current -> current.filterNot { it.id == id } }
            result.fold(
                onSuccess = { success(action.successTitle) },
                onFailure = { failure(action.failureTitle, detail = it.message) },
            )
        }
    }

    /**
     * Drops every toast and expiry timer. An undo already in flight is not
     * cancelled and still reports its outcome as a new toast. No production
     * caller today; kept for tests.
     */
    fun clear() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        _toasts.value = emptyList()
    }

    companion object {
        const val MAX_VISIBLE = 4
        const val SUCCESS_MS = 5_000L
        const val UNDOABLE_MS = 10_000L
        const val FAILURE_MS = 10_000L
    }
}

/** "1 replica" / "3 replicas". */
fun replicaCount(n: Int): String = if (n == 1) "1 replica" else "$n replicas"

/** The quoted resource for a toast title: `"ns/name"` when namespaced, `"name"` otherwise. */
fun resourceRef(name: String, namespace: String?): String = if (namespace.isNullOrBlank()) "\"$name\"" else "\"$namespace/$name\""
