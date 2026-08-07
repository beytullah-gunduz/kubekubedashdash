package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureTask
import com.kubekubedashdash.services.logtail.NamespaceTailTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class LogStreamId(
    val sessionId: String,
    val podName: String,
    val namespace: String,
    val container: String?,
) {
    val key: String get() = "$sessionId|$namespace|$podName|${container ?: ""}"
}

/**
 * One tab in the bottom log drawer. Two variants live side-by-side: pod log
 * streams ([ActiveLogStream]) and the singleton application log
 * ([ActiveAppLog]). The drawer iterates this as a flat list, sorted by
 * [openedAt], so the user sees a single tab strip regardless of source.
 */
sealed interface DrawerLogTab {
    val key: String
    val displayLabel: String
    val openedAt: Long

    /** Null means "global, shown in every window" (e.g. the application log). */
    val sessionId: String? get() = null
}

data class ActiveLogStream(
    val id: LogStreamId,
    override val displayLabel: String,
    val lines: StateFlow<List<String>>,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = id.key
    override val sessionId: String get() = id.sessionId
}

/**
 * Singleton drawer tab backed by [com.kubekubedashdash.logging.AppLogStore].
 * Unlike [ActiveLogStream] there is no per-tab streaming job — the pane reads
 * the store's StateFlow directly — so [LogStreamRegistry] does not register a
 * coroutine for it.
 */
data class ActiveAppLog(
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String = APP_LOG_KEY
    override val displayLabel: String = "Application logs"

    companion object {
        const val APP_LOG_KEY = "__app__"
    }
}

/**
 * Drawer tab backed by a live [NamespaceLogCaptureTask]. Unlike [ActiveLogStream]
 * this tab is scoped to a specific session (namespace log captures only make
 * sense within the cluster they were taken from), so [sessionId] is non-null.
 */
data class ActiveCaptureTask(
    override val sessionId: String,
    val task: NamespaceLogCaptureTask,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = "capture|$sessionId|${task.namespace}"
    override val displayLabel: String get() = "Capture · ${task.namespace}"
}

/**
 * Drawer tab backed by a live [NamespaceTailTask]. Mirrors [ActiveCaptureTask]
 * in shape, but the registry enforces at most one of these per [sessionId] —
 * see [openOrFocusTailTab] — because the tail's stream cap is sized per
 * session, not per namespace.
 */
data class ActiveNamespaceTail(
    override val sessionId: String,
    val task: NamespaceTailTask,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = "tail|$sessionId|${task.namespace}"
    override val displayLabel: String get() = "Tail · ${task.namespace}"
}

object LogStreamRegistry {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _tabs = MutableStateFlow<Map<String, DrawerLogTab>>(emptyMap())
    val tabs: StateFlow<Map<String, DrawerLogTab>> = _tabs.asStateFlow()
    private val _focusedKey = MutableStateFlow<String?>(null)
    val focusedKey: StateFlow<String?> = _focusedKey.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    private const val MAX_LINES = 5_000

    fun openOrFocus(
        session: ClusterSession,
        podName: String,
        namespace: String,
        container: String?,
    ): LogStreamId {
        val id = LogStreamId(session.id.value, podName, namespace, container)
        val label = "$podName${container?.let { " · $it" } ?: ""}"
        return openOrFocusStream(id, label) {
            session.reactiveClient.streamPodLogs(podName, namespace, container)
        }
    }

    // @Synchronized: the check-then-launch-then-insert below must be atomic.
    // Two concurrent opens for the same key would otherwise both pass the guard
    // and both launch a collector on the same pod-log watch, and the second
    // `jobs[key] =` would orphan the first coroutine + its watchLog connection.
    @Synchronized
    internal fun openOrFocusStream(
        id: LogStreamId,
        displayLabel: String,
        flowFactory: () -> Flow<String>,
    ): LogStreamId {
        if (id.key in _tabs.value) {
            _focusedKey.value = id.key
            return id
        }
        val state = MutableStateFlow<List<String>>(emptyList())
        val job = scope.launch {
            flowFactory()
                .runningFold(emptyList<String>()) { acc, line -> (acc + line).takeLast(MAX_LINES) }
                .collect { state.value = it }
        }
        jobs[id.key] = job
        _tabs.update {
            it + (id.key to ActiveLogStream(id, displayLabel, state.asStateFlow(), System.currentTimeMillis()))
        }
        _focusedKey.value = id.key
        return id
    }

    /**
     * Open the singleton "Application logs" drawer tab, or focus it if already
     * open. Has no streaming job — the pane reads [com.kubekubedashdash.logging.AppLogStore]
     * directly — so closing this tab does not need to cancel anything.
     */
    @Synchronized
    fun openOrFocusAppLog() {
        val key = ActiveAppLog.APP_LOG_KEY
        if (key in _tabs.value) {
            _focusedKey.value = key
            return
        }
        _tabs.update {
            it + (key to ActiveAppLog(openedAt = System.currentTimeMillis()))
        }
        _focusedKey.value = key
    }

    /**
     * Opens (or focuses) the capture tab for [namespace] in [session]. A
     * one-line delegation kept separate from [openOrFocusCaptureTab] so unit
     * tests never construct a [ClusterSession] — its init starts real
     * connection machinery on the session scope.
     */
    fun openOrFocusCapture(
        session: ClusterSession,
        namespace: String,
        taskFactory: () -> NamespaceLogCaptureTask,
    ): String = openOrFocusCaptureTab(session.id.value, namespace, taskFactory)

    /**
     * The unit-testable seam, mirroring [openOrFocusStream]. Takes a plain
     * session id so tests never construct a [ClusterSession]. @Synchronized
     * for the same reason [openOrFocusStream] is: the check-then-create-then-
     * insert below must be atomic, or two concurrent opens for the same key
     * would both pass the guard and the second `jobs[key] =` would orphan the
     * first capture job.
     *
     * If a tab already exists for [sessionId]/[namespace] and its task is
     * still running, focuses it and returns without invoking [taskFactory].
     * If the existing task has finished, replaces it — closing the old key
     * first (cancelling its already-completed job is a no-op), then inserting
     * the new tab.
     */
    @Synchronized
    internal fun openOrFocusCaptureTab(
        sessionId: String,
        namespace: String,
        taskFactory: () -> NamespaceLogCaptureTask,
    ): String {
        val key = "capture|$sessionId|$namespace"
        val existing = _tabs.value[key] as? ActiveCaptureTask
        if (existing != null) {
            if (existing.task.isRunning) {
                _focusedKey.value = key
                return key
            }
            close(key)
        }
        val task = taskFactory()
        jobs[key] = task.job
        _tabs.update {
            it + (key to ActiveCaptureTask(sessionId, task, System.currentTimeMillis()))
        }
        _focusedKey.value = key
        return key
    }

    /**
     * Opens (or focuses) the namespace tail tab for [namespace] in [session].
     * A one-line delegation kept separate from [openOrFocusTailTab] so unit
     * tests never construct a [ClusterSession] — its init starts real
     * connection machinery on the session scope.
     */
    fun openOrFocusTail(
        session: ClusterSession,
        namespace: String,
        taskFactory: () -> NamespaceTailTask,
    ): String = openOrFocusTailTab(session.id.value, namespace, taskFactory)

    /**
     * The unit-testable seam, mirroring [openOrFocusCaptureTab]. Takes a
     * plain session id so tests never construct a [ClusterSession].
     * @Synchronized for the same reason [openOrFocusCaptureTab] is: the
     * check-then-create-then-insert below must be atomic.
     *
     * At most ONE tail tab is kept per [sessionId]: unlike captures, the
     * tail's stream cap ([com.kubekubedashdash.services.logtail.NamespaceTailEngine.MAX_STREAMS])
     * is sized for a single namespace tail per session, so before inserting
     * the new tab every other [ActiveNamespaceTail] belonging to this
     * session is closed first — [close] cancels its job, unwinding that
     * tail's collectors and freeing its slots before the new tail claims
     * any.
     */
    @Synchronized
    internal fun openOrFocusTailTab(
        sessionId: String,
        namespace: String,
        taskFactory: () -> NamespaceTailTask,
    ): String {
        val key = "tail|$sessionId|$namespace"
        val existing = _tabs.value[key] as? ActiveNamespaceTail
        if (existing != null) {
            if (existing.task.isRunning) {
                _focusedKey.value = key
                return key
            }
            close(key)
        }
        _tabs.value.values
            .filterIsInstance<ActiveNamespaceTail>()
            .filter { it.sessionId == sessionId && it.key != key }
            .forEach { close(it.key) }
        val task = taskFactory()
        jobs[key] = task.job
        _tabs.update {
            it + (key to ActiveNamespaceTail(sessionId, task, System.currentTimeMillis()))
        }
        _focusedKey.value = key
        return key
    }

    @Synchronized
    fun focus(key: String) {
        if (key in _tabs.value) _focusedKey.value = key
    }

    fun focus(id: LogStreamId) = focus(id.key)

    @Synchronized
    fun close(key: String) {
        jobs.remove(key)?.cancel()
        _tabs.update { it - key }
        if (_focusedKey.value == key) _focusedKey.value = null
    }

    fun close(id: LogStreamId) = close(id.key)

    @Synchronized
    fun closeAllForSession(sessionId: SessionId) {
        _tabs.value.values.filter { it.sessionId == sessionId.value }.forEach { close(it.key) }
    }

    internal fun clearAll() {
        jobs.keys().toList().forEach { key ->
            jobs.remove(key)?.cancel()
        }
        _tabs.value = emptyMap()
        _focusedKey.value = null
    }
}
