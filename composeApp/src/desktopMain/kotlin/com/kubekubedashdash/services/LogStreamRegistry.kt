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

data class ActiveLogStream(
    val id: LogStreamId,
    val displayLabel: String,
    val lines: StateFlow<List<String>>,
    val openedAt: Long,
)

object LogStreamRegistry {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _streams = MutableStateFlow<Map<String, ActiveLogStream>>(emptyMap())
    val streams: StateFlow<Map<String, ActiveLogStream>> = _streams.asStateFlow()
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
        if (id.key in _streams.value) {
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
        _streams.update {
            it + (id.key to ActiveLogStream(id, displayLabel, state.asStateFlow(), System.currentTimeMillis()))
        }
        _focusedKey.value = id.key
        return id
    }

    fun focus(id: LogStreamId) {
        if (id.key in _streams.value) _focusedKey.value = id.key
    }

    fun close(id: LogStreamId) {
        jobs.remove(id.key)?.cancel()
        _streams.update { it - id.key }
        if (_focusedKey.value == id.key) _focusedKey.value = null
    }

    fun closeAllForSession(sessionId: SessionId) {
        _streams.value
            .values
            .filter { it.id.sessionId == sessionId.value }
            .forEach { close(it.id) }
    }

    internal fun clearAll() {
        jobs.keys().toList().forEach { key ->
            jobs.remove(key)?.cancel()
        }
        _streams.value = emptyMap()
        _focusedKey.value = null
    }
}
