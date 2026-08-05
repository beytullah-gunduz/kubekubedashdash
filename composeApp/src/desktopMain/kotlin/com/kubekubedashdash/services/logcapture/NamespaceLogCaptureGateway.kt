package com.kubekubedashdash.services.logcapture

import com.kubekubedashdash.util.ReactiveKubeClient
import java.io.InputStream

/**
 * Seam between the capture engine and the cluster (same idiom as
 * [com.kubekubedashdash.ui.modals.viewmodel.GkeDiscoveryGateway]). Production
 * uses [DefaultNamespaceLogCaptureGateway]; tests substitute a fake feeding
 * ByteArrayInputStreams so the engine runs with no cluster and no real files.
 */
interface NamespaceLogCaptureGateway {
    /** Fresh LIST — the session informer is namespaced to the *selected* namespace. */
    suspend fun listPods(namespace: String): Result<List<CapturePodSpec>>

    /**
     * Opens a NON-FOLLOW log stream with timestamps enabled. The returned
     * stream is blocking-read; the caller owns closing it. Throws on API
     * errors (4xx/5xx) — the caller converts that to a ContainerOutcome.
     */
    fun openLogStream(namespace: String, query: LogQuery): InputStream
}

class DefaultNamespaceLogCaptureGateway(
    private val client: ReactiveKubeClient,
) : NamespaceLogCaptureGateway {
    override suspend fun listPods(namespace: String): Result<List<CapturePodSpec>> = client.listCapturePods(namespace)

    override fun openLogStream(namespace: String, query: LogQuery): InputStream = client.openCaptureLogStream(namespace, query)
}
