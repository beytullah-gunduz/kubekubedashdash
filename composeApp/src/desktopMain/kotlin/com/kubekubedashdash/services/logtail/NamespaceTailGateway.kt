package com.kubekubedashdash.services.logtail

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
}

class DefaultNamespaceTailGateway(private val client: ReactiveKubeClient) : NamespaceTailGateway {
    override fun podSnapshots(namespace: String) = client.watchTailPods(namespace)

    // A terminal pod gets a one-shot read instead of a followed stream; keep it
    // as shallow as a live stream's backfill so a few Completed pods cannot
    // flush the tail's line buffer.
    override fun streamPodLogs(podName: String, namespace: String, container: String?) = client.streamPodLogs(
        podName,
        namespace,
        container,
        oneShotTailLines = NamespaceTailEngine.ONE_SHOT_TAIL_LINES,
    )
}
