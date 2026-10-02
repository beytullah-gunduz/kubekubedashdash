package com.kubekubedashdash.services.logtail

import com.kubekubedashdash.services.logcapture.CapturePodSpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.ConcurrentHashMap

/**
 * Test double for [NamespaceTailGateway]. No cluster involved — pod discovery
 * is driven by [pushSnapshot]/[failDiscovery], and each container's log
 * stream is an independently programmable channel driven by
 * [pushLine]/[completeStream]/[failStream].
 *
 * Namespace handling is wildcard-by-default: a `null` namespace on a push or
 * stream helper means "any namespace", so a single-namespace test can ignore
 * namespaces entirely, while a multi-namespace test passes them explicitly. A
 * test should use either wildcard pushes or namespaced pushes for a given
 * namespace, never both: each [podSnapshots] merges two `replay = 1` flows, and
 * after a discovery retry both replays re-emit in unspecified order.
 */
class FakeNamespaceTailGateway : NamespaceTailGateway {

    private sealed interface DiscoveryEvent {
        data class Snapshot(val pods: List<CapturePodSpec>) : DiscoveryEvent

        data class Failure(val message: String) : DiscoveryEvent
    }

    // replay = 1 so a late collector — e.g. the engine resubscribing after a
    // discovery retry — still sees the latest snapshot instead of hanging
    // until the next push.
    //
    // anyNamespaceEvents feeds every podSnapshots(ns); namespaceEvents[ns] feeds
    // only that namespace's. podSnapshots(ns) merges the two.
    private val anyNamespaceEvents = MutableSharedFlow<DiscoveryEvent>(replay = 1)
    private val namespaceEvents = ConcurrentHashMap<String, MutableSharedFlow<DiscoveryEvent>>()

    private fun eventsFor(namespace: String): MutableSharedFlow<DiscoveryEvent> = namespaceEvents.getOrPut(namespace) { MutableSharedFlow(replay = 1) }

    // Re-armed on every streamPodLogs() call (i.e. every attach), so a
    // pod/container that re-attaches after detaching gets a fresh channel
    // instead of replaying an already-closed one. push/complete/fail always
    // act on whichever channel is current for that key. Keyed
    // (namespace, pod, container).
    private val podStreams = ConcurrentHashMap<Triple<String, String, String?>, Channel<String>>()

    private fun publish(namespace: String?, event: DiscoveryEvent) {
        val target = if (namespace == null) anyNamespaceEvents else eventsFor(namespace)
        check(target.tryEmit(event)) { "discovery event buffer full" }
    }

    /** Emits [pods] to [namespace]'s discovery, or to every namespace's when it is null. */
    fun pushSnapshot(pods: List<CapturePodSpec>, namespace: String? = null) {
        publish(namespace, DiscoveryEvent.Snapshot(pods))
    }

    fun failDiscovery(message: String, namespace: String? = null) {
        publish(namespace, DiscoveryEvent.Failure(message))
    }

    private fun streamsFor(podName: String, container: String?, namespace: String?): List<Channel<String>> = podStreams.entries
        .filter { (key, _) -> key.second == podName && key.third == container && (namespace == null || key.first == namespace) }
        .map { it.value }

    fun pushLine(podName: String, container: String?, text: String, namespace: String? = null) {
        streamsFor(podName, container, namespace).forEach { it.trySend(text) }
    }

    fun completeStream(podName: String, container: String?, namespace: String? = null) {
        streamsFor(podName, container, namespace).forEach { it.close() }
    }

    fun failStream(podName: String, container: String?, message: String, namespace: String? = null) {
        streamsFor(podName, container, namespace).forEach { it.close(RuntimeException(message)) }
    }

    /**
     * True once the engine has actually called [streamPodLogs] for this
     * pod/container. Tests must poll this before [pushLine]/[completeStream]/
     * [failStream] — those act on `podStreams`, which has no entry (a silent
     * no-op) until the engine's attach coroutine has run, and attach is
     * asynchronous relative to the `attachedPods` state update.
     */
    fun hasStream(podName: String, container: String?, namespace: String? = null): Boolean = streamsFor(podName, container, namespace).isNotEmpty()

    override fun podSnapshots(namespace: String): Flow<List<CapturePodSpec>> = flow {
        merge(anyNamespaceEvents, eventsFor(namespace)).collect { event ->
            when (event) {
                is DiscoveryEvent.Snapshot -> emit(event.pods)
                is DiscoveryEvent.Failure -> throw RuntimeException(event.message)
            }
        }
    }

    override fun streamPodLogs(podName: String, namespace: String, container: String?): Flow<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        podStreams[Triple(namespace, podName, container)] = channel
        return channel.consumeAsFlow()
    }
}
