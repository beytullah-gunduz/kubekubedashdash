package com.kubekubedashdash.services

import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
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
}

data class ActiveLogStream(
    val id: LogStreamId,
    override val displayLabel: String,
    val lines: StateFlow<List<String>>,
    override val openedAt: Long,
) : DrawerLogTab {
    override val key: String get() = id.key
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

    fun focus(key: String) {
        if (key in _tabs.value) _focusedKey.value = key
    }

    fun focus(id: LogStreamId) = focus(id.key)

    fun close(key: String) {
        jobs.remove(key)?.cancel()
        _tabs.update { it - key }
        if (_focusedKey.value == key) _focusedKey.value = null
    }

    fun close(id: LogStreamId) = close(id.key)

    fun closeAllForSession(sessionId: SessionId) {
        _tabs.value
            .values
            .filterIsInstance<ActiveLogStream>()
            .filter { it.id.sessionId == sessionId.value }
            .forEach { close(it.key) }
    }

    internal fun clearAll() {
        jobs.keys().toList().forEach { key ->
            jobs.remove(key)?.cancel()
        }
        _tabs.value = emptyMap()
        _focusedKey.value = null
    }
}
