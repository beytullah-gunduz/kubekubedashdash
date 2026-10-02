package com.kubekubedashdash.services.logtail

import com.kubekubedashdash.services.logcapture.CapturePodSpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for [NamespaceTailGateway]. No cluster involved — pod discovery
 * is driven by [pushSnapshot]/[failDiscovery], and each container's log
 * stream is an independently programmable channel driven by
 * [pushLine]/[completeStream]/[failStream]. A container's previous-run read
 * ([previousPodLogs]) is a second, independent family of channels driven by
 * [pushPreviousLine]/[completePreviousStream]/[failPreviousStream].
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

    // Same keying and re-arming, for previousPodLogs() reads.
    private val previousStreams = ConcurrentHashMap<Triple<String, String, String?>, Channel<String>>()

    // Call counts (how many times the engine asked, not whether a channel is
    // currently open — hasStream stays true after a stream closes). Incremented
    // together with the channel registration under [requestLock], so a test that
    // sees a count can never still be looking at the previous channel.
    private val streamRequests = ConcurrentHashMap<Triple<String, String, String?>, AtomicInteger>()
    private val previousRequests = ConcurrentHashMap<Triple<String, String, String?>, AtomicInteger>()
    private val requestLock = Any()

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

    private fun previousStreamsFor(podName: String, container: String?, namespace: String?): List<Channel<String>> = previousStreams.entries
        .filter { (key, _) -> key.second == podName && key.third == container && (namespace == null || key.first == namespace) }
        .map { it.value }

    /** True once the engine has called [previousPodLogs] for this pod/container. */
    fun hasPreviousStream(podName: String, container: String?, namespace: String? = null): Boolean = previousStreamsFor(podName, container, namespace).isNotEmpty()

    fun pushPreviousLine(podName: String, container: String?, text: String, namespace: String? = null) {
        previousStreamsFor(podName, container, namespace).forEach { it.trySend(text) }
    }

    fun completePreviousStream(podName: String, container: String?, namespace: String? = null) {
        previousStreamsFor(podName, container, namespace).forEach { it.close() }
    }

    fun failPreviousStream(podName: String, container: String?, message: String, namespace: String? = null) {
        previousStreamsFor(podName, container, namespace).forEach { it.close(RuntimeException(message)) }
    }

    /**
     * How many times the engine called [streamPodLogs] for this pod/container
     * (summed across namespaces when [namespace] is null).
     */
    fun streamRequestCount(podName: String, container: String?, namespace: String? = null): Int = synchronized(requestLock) {
        streamRequests.entries
            .filter { (key, _) -> key.second == podName && key.third == container && (namespace == null || key.first == namespace) }
            .sumOf { it.value.get() }
    }

    /**
     * How many times the engine called [previousPodLogs] for this
     * pod/container, counted at call time (not on first collect).
     */
    fun previousRequestCount(podName: String, container: String?, namespace: String? = null): Int = synchronized(requestLock) {
        previousRequests.entries
            .filter { (key, _) -> key.second == podName && key.third == container && (namespace == null || key.first == namespace) }
            .sumOf { it.value.get() }
    }

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
        val key = Triple(namespace, podName, container)
        synchronized(requestLock) {
            streamRequests.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            podStreams[key] = channel
        }
        return channel.consumeAsFlow()
    }

    override fun previousPodLogs(podName: String, namespace: String, container: String): Flow<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        val key = Triple(namespace, podName, container)
        synchronized(requestLock) {
            previousRequests.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            previousStreams[key] = channel
        }
        return channel.consumeAsFlow()
    }
}
