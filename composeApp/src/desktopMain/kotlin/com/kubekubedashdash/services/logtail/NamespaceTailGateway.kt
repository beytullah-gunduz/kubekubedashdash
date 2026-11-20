package com.kubekubedashdash.services.logtail

import com.kubekubedashdash.services.LogStreamOptions
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import com.kubekubedashdash.util.ReactiveKubeClient
import kotlinx.coroutines.flow.Flow

/**
 * Seam between [NamespaceTailEngine] and the cluster (same idiom as
 * [com.kubekubedashdash.services.logcapture.NamespaceLogCaptureGateway]).
 * Production uses [DefaultNamespaceTailGateway]; tests substitute a fake
 * feeding programmable in-memory flows so the engine runs with no cluster.
 */
interface NamespaceTailGateway {
    /** Live pod snapshots for the namespace. Fails on hard errors (RBAC). */
    fun podSnapshots(namespace: String): Flow<List<CapturePodSpec>>

    fun streamPodLogs(podName: String, namespace: String, container: String?): Flow<String>

    /** One-shot read of [container]'s previous (last terminated) run; completes after the dump. */
    fun previousPodLogs(podName: String, namespace: String, container: String): Flow<String>
}

/**
 * Pinned to the context connected when the tail started (or at its first
 * successful attempt), so a retry never follows a cluster switch.
 */
class DefaultNamespaceTailGateway(
    private val client: ReactiveKubeClient,
    initialContext: String? = client.connectedContextOrNull(),
) : NamespaceTailGateway {
    private val pinnedContext = java.util.concurrent.atomic.AtomicReference(initialContext)

    override fun podSnapshots(namespace: String): Flow<List<CapturePodSpec>> {
        val context = pinnedContext.get()
            ?: client.connectedContextOrNull()?.let { live ->
                pinnedContext.compareAndSet(null, live)
                pinnedContext.get()
            }
        return client.watchTailPods(namespace, context)
    }

    // A terminal pod gets a one-shot read instead of a followed stream; keep it
    // as shallow as a live stream's backfill so a few Completed pods cannot
    // flush the tail's line buffer.
    override fun streamPodLogs(podName: String, namespace: String, container: String?) = client.streamPodLogs(
        podName,
        namespace,
        container,
        oneShotTailLines = NamespaceTailEngine.ONE_SHOT_TAIL_LINES,
    )

    // `options.previous` forces the one-shot branch regardless of the pod's
    // phase; a failed read surfaces as a "[fetch error: …]" line, then completes.
    override fun previousPodLogs(podName: String, namespace: String, container: String) = client.streamPodLogs(
        podName,
        namespace,
        container,
        LogStreamOptions(previous = true),
        oneShotTailLines = NamespaceTailEngine.ONE_SHOT_TAIL_LINES,
    )
}
