package com.kubekubedashdash.services.logtail

import com.kubekubedashdash.services.logcapture.CapturePodSpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Test double for [NamespaceTailGateway]. No cluster involved — pod discovery
 * is driven by [pushSnapshot]/[failDiscovery], and each container's log
 * stream is an independently programmable channel driven by
 * [pushLine]/[completeStream]/[failStream].
 */
class FakeNamespaceTailGateway : NamespaceTailGateway {

    private sealed interface DiscoveryEvent {
        data class Snapshot(val pods: List<CapturePodSpec>) : DiscoveryEvent

        data class Failure(val message: String) : DiscoveryEvent
    }

    // replay = 1 so a late collector — e.g. the engine resubscribing after a
    // discovery retry — still sees the latest snapshot instead of hanging
    // until the next push.
    private val discoveryEvents = MutableSharedFlow<DiscoveryEvent>(replay = 1)

    // Re-armed on every streamPodLogs() call (i.e. every attach), so a
    // pod/container that re-attaches after detaching gets a fresh channel
    // instead of replaying an already-closed one. push/complete/fail always
    // act on whichever channel is current for that key.
    private val podStreams = ConcurrentHashMap<Pair<String, String?>, Channel<String>>()

    fun pushSnapshot(pods: List<CapturePodSpec>) {
        check(discoveryEvents.tryEmit(DiscoveryEvent.Snapshot(pods))) { "discovery event buffer full" }
    }

    fun failDiscovery(message: String) {
        check(discoveryEvents.tryEmit(DiscoveryEvent.Failure(message))) { "discovery event buffer full" }
    }

    fun pushLine(podName: String, container: String?, text: String) {
        podStreams[podName to container]?.trySend(text)
    }

    fun completeStream(podName: String, container: String?) {
        podStreams[podName to container]?.close()
    }

    fun failStream(podName: String, container: String?, message: String) {
        podStreams[podName to container]?.close(RuntimeException(message))
    }

    /**
     * True once the engine has actually called [streamPodLogs] for this
     * pod/container. Tests must poll this before [pushLine]/[completeStream]/
     * [failStream] — those act on `podStreams[key]`, which is null (a silent
     * no-op) until the engine's attach coroutine has run, and attach is
     * asynchronous relative to the `attachedPods` state update.
     */
    fun hasStream(podName: String, container: String?): Boolean = podStreams.containsKey(podName to container)

    override fun podSnapshots(namespace: String): Flow<List<CapturePodSpec>> = flow {
        discoveryEvents.collect { event ->
            when (event) {
                is DiscoveryEvent.Snapshot -> emit(event.pods)
                is DiscoveryEvent.Failure -> throw RuntimeException(event.message)
            }
        }
    }

    override fun streamPodLogs(podName: String, namespace: String, container: String?): Flow<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        podStreams[podName to container] = channel
        return channel.consumeAsFlow()
    }
}
