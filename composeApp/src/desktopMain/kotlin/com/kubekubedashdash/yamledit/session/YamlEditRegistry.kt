package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.ui.feedback.ActionFeedback
import com.kubekubedashdash.yamledit.EditTarget
import com.kubekubedashdash.yamledit.YamlWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** An editor with unsaved work, as a guard prompt lists it. */
data class DirtyEdit(val windowId: Long, val clusterSessionId: SessionId, val label: String, val context: String)

/**
 * Every open editor window of the process (D1, D11): the YAML edit sessions and the Apply YAML
 * sessions, whichever cluster tab they belong to. A session lives here, not in a composition, so
 * navigating the main window (Esc, selecting another resource, back/forward) never touches an
 * editor and a buffer is dropped only by the paths that close it on purpose: its own window, its
 * cluster tab, the main window or Quit, each behind [UnsavedEditGuard] / [QuitGuard].
 *
 * [openEdit], [openApply], [focus], [closeAllFor] and [closeAll] must be called on the EDT: they
 * reach the buffers through the sessions.
 *
 * Each session runs on the scope [scopeFactory] returns for it; when the session closes, that
 * scope is cancelled ([cancelScopeOnClose]; tests pass false together with a scope they own).
 * [io], [compute] and [pollIntervalMs] are handed to every edit session and [io] and [compute] to
 * every Apply session; tests substitute test dispatchers.
 */
class YamlEditRegistry(
    private val cancelScopeOnClose: Boolean = true,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val pollIntervalMs: Long = 5_000,
) {
    private val _windows = MutableStateFlow<List<EditorWindowModel>>(emptyList())

    /** The open editors in the order they were opened. */
    val windows: StateFlow<List<EditorWindowModel>> = _windows.asStateFlow()

    private val nextId = AtomicLong(1)

    /** The scope each open session runs on, to cancel when it closes. */
    private val scopes = ConcurrentHashMap<Long, CoroutineScope>()

    /**
     * Focuses the editor of the same (cluster tab, context, object) or creates and starts one. The
     * buffer comes from [bufferFactory] only for a new editor.
     */
    fun openEdit(
        clusterSessionId: SessionId,
        context: String,
        target: EditTarget,
        writer: YamlWriter,
        feedback: ActionFeedback,
        masking: () -> Boolean,
        bufferFactory: () -> EditorBuffer,
        scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main) },
    ): YamlEditSession {
        existingEdit(clusterSessionId, context, target.key)?.let {
            it.requestFocus()
            return it
        }
        val id = nextId.getAndIncrement()
        val scope = scopeFactory()
        val session = YamlEditSession(
            id = id,
            clusterSessionId = clusterSessionId,
            context = context,
            target = target,
            writer = writer,
            feedback = feedback,
            masking = masking,
            buffer = bufferFactory(),
            scope = scope,
            io = io,
            compute = compute,
            pollIntervalMs = pollIntervalMs,
            onClosed = ::removed,
        )
        register(session, scope)
        session.start()
        return session
    }

    /** Focuses the Apply YAML editor of this (cluster tab, context) or creates one; there is one per pair. */
    fun openApply(
        clusterSessionId: SessionId,
        context: String,
        defaultNamespace: String,
        writer: YamlWriter,
        crds: () -> List<CrdInfo>,
        feedback: ActionFeedback,
        masking: () -> Boolean,
        bufferFactory: () -> EditorBuffer,
        scopeFactory: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main) },
    ): ApplyYamlSession {
        _windows.value.filterIsInstance<ApplyYamlSession>().firstOrNull { it.clusterSessionId == clusterSessionId && it.context == context }?.let {
            it.requestFocus()
            return it
        }
        val id = nextId.getAndIncrement()
        val scope = scopeFactory()
        val session = ApplyYamlSession(
            id = id,
            clusterSessionId = clusterSessionId,
            context = context,
            defaultNamespace = defaultNamespace,
            writer = writer,
            crds = crds,
            feedback = feedback,
            masking = masking,
            buffer = bufferFactory(),
            scope = scope,
            io = io,
            compute = compute,
            onClosed = ::removed,
        )
        register(session, scope)
        return session
    }

    /** The edit session of the object whose [EditTarget.key] is [targetKey] in this cluster tab, if one is open. */
    fun editFor(clusterSessionId: SessionId, targetKey: String): YamlEditSession? = _windows.value.filterIsInstance<YamlEditSession>().firstOrNull { it.clusterSessionId == clusterSessionId && it.target.key == targetKey }

    /**
     * The editors among [sessionIds] (null = all) with unsaved work. Decided by each editor's
     * [EditorWindowModel.isDirtyNow], never by the debounced `dirty` flow.
     */
    fun dirty(sessionIds: Collection<SessionId>? = null): List<DirtyEdit> = _windows.value
        .filter { (sessionIds == null || it.clusterSessionId in sessionIds) && it.isDirtyNow() }
        .map { DirtyEdit(it.id, it.clusterSessionId, it.dirtyLabel, it.context) }

    /** Brings the editor window [windowId] to the front. Unknown ids are ignored. */
    fun focus(windowId: Long) {
        _windows.value.firstOrNull { it.id == windowId }?.requestFocus()
    }

    /** Closes every editor of [sessionId] without asking (the guard asks first). */
    fun closeAllFor(sessionId: SessionId) {
        _windows.value.filter { it.clusterSessionId == sessionId }.forEach { it.close() }
    }

    /** Closes every editor without asking (the quit guard asks first). */
    fun closeAll() {
        _windows.value.forEach { it.close() }
    }

    private fun existingEdit(clusterSessionId: SessionId, context: String, targetKey: String): YamlEditSession? = _windows.value.filterIsInstance<YamlEditSession>()
        .firstOrNull { it.clusterSessionId == clusterSessionId && it.context == context && it.target.key == targetKey }

    private fun register(session: EditorWindowModel, scope: CoroutineScope) {
        scopes[session.id] = scope
        _windows.update { it + session }
    }

    /** A session's `onClosed`: forgets it and, when the registry owns the scope, cancels the scope it ran on. */
    private fun removed(id: Long) {
        _windows.update { windows -> windows.filter { it.id != id } }
        val scope = scopes.remove(id) ?: return
        if (cancelScopeOnClose) scope.cancel()
    }

    companion object {
        /** The registry the app uses. */
        val Default = YamlEditRegistry()
    }
}
