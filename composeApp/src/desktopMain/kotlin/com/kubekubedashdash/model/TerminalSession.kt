package com.kubekubedashdash.model

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

    /**
     * Called by the connector after a successful `openExec`. Replaces any
     * previous watch (defensive; should not happen during normal lifecycle).
     */
    fun attach(watch: ExecWatch) {
        val previous = watchRef.getAndSet(watch)
        previous?.close()
    }

    fun close() {
        watchRef.getAndSet(null)?.close()
    }
}
