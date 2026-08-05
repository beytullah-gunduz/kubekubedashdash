package com.kubekubedashdash.services.logcapture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for [NamespaceLogCaptureGateway]. Feeds in-memory byte streams
 * so the engine runs with no cluster and no real files. Records every
 * [LogQuery] it receives and tracks the concurrent open-stream high-water
 * mark so tests can assert on bounded fan-out.
 */
class FakeNamespaceLogCaptureGateway(
    private var podsResult: Result<List<CapturePodSpec>> = Result.success(emptyList()),
) : NamespaceLogCaptureGateway {

    sealed interface Response {
        data class Content(val text: String) : Response

        data class Throw(val exception: Exception) : Response

        /** Blocks the first read until [gate] completes, then serves [text]. */
        data class Gated(val gate: CompletableDeferred<Unit>, val text: String) : Response
    }

    val receivedQueries: MutableList<LogQuery> = CopyOnWriteArrayList()

    private val responses = mutableMapOf<Triple<String, String, Boolean>, Response>()
    private val openStreamCount = AtomicInteger(0)
    private val highWaterMark = AtomicInteger(0)

    fun currentHighWaterMark(): Int = highWaterMark.get()

    fun setPodsResult(result: Result<List<CapturePodSpec>>) {
        podsResult = result
    }

    fun programContent(
        podName: String,
        containerName: String,
        previous: Boolean,
        text: String,
    ) {
        responses[Triple(podName, containerName, previous)] = Response.Content(text)
    }

    fun programThrow(
        podName: String,
        containerName: String,
        previous: Boolean,
        exception: Exception,
    ) {
        responses[Triple(podName, containerName, previous)] = Response.Throw(exception)
    }

    fun programGated(
        podName: String,
        containerName: String,
        previous: Boolean,
        gate: CompletableDeferred<Unit>,
        text: String,
    ) {
        responses[Triple(podName, containerName, previous)] = Response.Gated(gate, text)
    }

    override suspend fun listPods(namespace: String): Result<List<CapturePodSpec>> = podsResult

    override fun openLogStream(namespace: String, query: LogQuery): InputStream {
        receivedQueries.add(query)
        return when (val response = responses[Triple(query.podName, query.containerName, query.previous)] ?: Response.Content("")) {
            is Response.Throw -> throw response.exception
            is Response.Content -> trackedStream(response.text.toByteArray())
            is Response.Gated -> trackedStream(response.text.toByteArray(), response.gate)
        }
    }

    private fun trackedStream(bytes: ByteArray, gate: CompletableDeferred<Unit>? = null): InputStream {
        val current = openStreamCount.incrementAndGet()
        highWaterMark.updateAndGet { previous -> maxOf(previous, current) }
        val delegate = ByteArrayInputStream(bytes)
        var awaited = gate == null

        fun awaitGate() {
            if (!awaited) {
                runBlocking { gate!!.await() }
                awaited = true
            }
        }

        return object : InputStream() {
            override fun read(): Int {
                awaitGate()
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                awaitGate()
                return delegate.read(b, off, len)
            }

            override fun close() {
                openStreamCount.decrementAndGet()
                delegate.close()
            }
        }
    }
}
