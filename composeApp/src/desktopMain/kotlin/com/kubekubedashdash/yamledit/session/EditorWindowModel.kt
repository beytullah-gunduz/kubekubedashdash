package com.kubekubedashdash.yamledit.session

import com.kubekubedashdash.model.SessionId
import kotlinx.coroutines.flow.StateFlow

/**
 * What the window layer (WS3/WS5) and the guards need from either kind of editor session
 * ([YamlEditSession] and the Apply window's session).
 *
 * Coroutine ownership, for both sessions: the constructor takes the `scope` the session runs on
 * (Main in the app, a test dispatcher in tests) plus an `io` and a `compute` dispatcher. A session
 * keeps its own `SupervisorJob` as a child of the scope's job and launches everything on
 * `scope + that job + an exception handler` that logs only the exception's class name, so
 * [close] cancels the session's work and never the injected scope. Network calls hop to `io`, CPU
 * work (parsing and diffing a 1 MB buffer) to `compute`, and every `StateFlow` is written back on
 * the scope.
 */
sealed interface EditorWindowModel {
    val id: Long
    val clusterSessionId: SessionId
    val context: String

    /** The window title, e.g. `Edit ConfigMap default/app-config — cluster-a`. */
    val title: String

    /** Display only (debounced). Guard decisions use [isDirtyNow]. */
    val dirty: StateFlow<Boolean>

    /** True while the window shows its "close with unsaved changes?" prompt. */
    val closePrompt: StateFlow<Boolean>

    /** Counts [requestFocus] calls; the window brings itself to the front when it changes. */
    val focusRequests: StateFlow<Int>
    val buffer: EditorBuffer

    /**
     * Synchronous, on the EDT: compares the live buffer text, never the debounced [dirty] flow. A
     * user who types and presses Cmd/Ctrl+W within the debounce would otherwise close a dirty
     * editor without a prompt.
     */
    fun isDirtyNow(): Boolean

    fun requestFocus()

    /** Dirty ([isDirtyNow]) -> [closePrompt] is raised; clean -> [close]. Ignored while an apply is in flight. */
    fun requestClose()

    /** [close] without asking. */
    fun confirmClose()

    fun cancelClose()

    /** Idempotent. Cancels the session's jobs (never the injected scope) and tells the registry. */
    fun close()

    /** Label for prompts: `ConfigMap default/app-config` or `Apply YAML (cluster-a)`. */
    val dirtyLabel: String
}
