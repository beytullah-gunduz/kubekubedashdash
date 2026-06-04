package com.kubekubedashdash.model

import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import io.fabric8.kubernetes.client.dsl.ExecWatch
import java.util.concurrent.atomic.AtomicReference

@JvmInline
value class TerminalSessionId(val value: String) {
    companion object {
        fun of(sessionId: String, namespace: String, podName: String, container: String): TerminalSessionId = TerminalSessionId("$sessionId|$namespace|$podName|$container")
    }
}

/**
 * One open terminal targeting a single container in a single pod. Lifecycle is
 * owned by whichever surface holds the corresponding `WorkspaceTab.Terminal` —
 * closing the tab calls [close]. Idempotent.
 *
 * The `ExecWatch` is opened lazily by the connector (see [JediTermPane]) and
 * attached here so that [close] can dispose it. This indirection keeps fabric8
 * out of every call site that just wants to know "is this terminal alive".
 */
class TerminalSession(
    val id: TerminalSessionId,
    val clusterSession: ClusterSession,
    val podName: String,
    val namespace: String,
    val container: String,
) {
    val displayLabel: String = "$namespace/$podName · $container"
    private val watchRef = AtomicReference<ExecWatch?>(null)

    // Live terminal UI + TTY connector, held on the session (not the JediTermPane
    // composable) so the shell session and scrollback survive tab switches: the
    // pane detaches/reattaches the widget's AWT component, but these live until
    // the tab is closed via [close]. Set once by JediTermPane on first attach.
    @Volatile
    var widget: JediTermWidget? = null

    @Volatile
    var connector: TtyConnector? = null

    /**
     * Called by the connector after a successful `openExec`. Replaces any
     * previous watch (defensive; should not happen during normal lifecycle).
     */
    fun attach(watch: ExecWatch) {
        val previous = watchRef.getAndSet(watch)
        previous?.close()
    }

    fun close() {
        // Clear the fields synchronously so the registry/pane immediately see
        // the session as gone, then do the blocking teardown on a daemon thread.
        // Running it inline froze the UI: close() is called from closeTab on the
        // AWT EDT, and both the ExecWatch WebSocket close and JediTermWidget's
        // executor shutdown can block — mirrors ClusterSession.close().
        // Connector first so a blocked read() returns before the widget's
        // executor shutdown waits on it; the watch close is then idempotent.
        val c = connector
        val w = widget
        val watch = watchRef.getAndSet(null)
        connector = null
        widget = null
        val threadName = "terminal-close-${id.value}"
        Thread {
            runCatching { c?.close() }
            runCatching { w?.close() }
            runCatching { watch?.close() }
        }.apply {
            name = threadName
            isDaemon = true
            start()
        }
    }
}
