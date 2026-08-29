package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The mock server's [KubernetesCrudDispatcher] has no handler for the `/log`
 * subresource (verified by decompiling fabric8 7.5.2: it exposes
 * handleCreate/handleUpdate/handleGet/handlePatch/handleDelete/handleWatch
 * only). This dispatcher intercepts `/log` requests directly and delegates
 * everything else to the CRUD dispatcher, so both pod-log streaming and
 * ordinary resource CRUD/watch work against a single server instance.
 *
 * [denyPodList] additionally lets a test simulate an RBAC failure on the pod
 * list/watch endpoints without standing up a second server.
 */
private class LogAwareDispatcher(private val crud: KubernetesCrudDispatcher) : Dispatcher() {
    @Volatile var denyPodList = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.substringBefore('?')
        if (denyPodList && path.endsWith("/pods")) {
            return MockResponse().setResponseCode(403).setBody("{}")
        }
        return if (path.endsWith("/log")) {
            MockResponse().setResponseCode(200).setBody("alpha\nbravo\ncharlie\n")
        } else {
            crud.dispatch(request)
        }
    }
}

class ReactiveKubeClientLogStreamTest {

    private lateinit var dispatcher: LogAwareDispatcher
    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dispatcher = LogAwareDispatcher(KubernetesCrudDispatcher())
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            dispatcher,
            false,
        )
        server.init()

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientLogStreamTest", manager = manager, servers = listOf(server))
    }

    private fun seedPod(name: String, namespace: String) {
        val seed = server.createClient()
        try {
            seed.pods().inNamespace(namespace).resource(
                PodBuilder().withNewMetadata().withName(name).withNamespace(namespace).endMetadata().build(),
            ).create()
        } finally {
            seed.close()
        }
    }

    @Test
    fun `pod log stream completes when the server closes the response`() = runBlocking {
        seedPod("example-pod", "example-namespace")

        val lines = withTimeout(15_000) {
            client.streamPodLogs("example-pod", "example-namespace", null).toList()
        }

        assertEquals(listOf("alpha", "bravo", "charlie"), lines)
    }

    @Test
    fun `listCapturePods rethrows cancellation instead of returning a failure`() = runBlocking {
        seedPod("example-pod", "example-namespace")

        // UNDISPATCHED so the launch body runs synchronously on this thread up
        // to the first real suspension point (runInterruptible's dispatch onto
        // Dispatchers.IO). Cancelling immediately after launch() returns lands
        // the cancellation while listCapturePods is still in flight, which is
        // exactly the window the buggy `runCatching` swallowed: it caught the
        // resulting CancellationException, returned Result.failure(ce), and let
        // execution continue into the "should never run" marker below.
        val reachedPastCall = CompletableDeferred<Boolean>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.listCapturePods("example-namespace")
            reachedPastCall.complete(true)
        }
        job.cancel()

        withTimeout(15_000) { job.join() }

        assertTrue(job.isCancelled, "job should end up cancelled")
        assertTrue(
            !reachedPastCall.isCompleted,
            "listCapturePods must rethrow CancellationException, not return a Result and let the caller continue",
        )
    }

    @Test
    fun `watchTailPods emits an initial snapshot`() = runBlocking {
        seedPod("example-pod", "example-namespace")

        val snapshot = withTimeout(15_000) {
            client.watchTailPods("example-namespace").first { it.isNotEmpty() }
        }

        assertEquals(listOf("example-pod"), snapshot.map { it.name })
    }

    @Test
    fun `watchTailPods emits again when a pod is added`() = runBlocking {
        seedPod("example-pod-1", "example-namespace")

        withTimeout(20_000) {
            val flow = client.watchTailPods("example-namespace")
            val job = scope.launch {
                flow.first { it.isNotEmpty() }
                seedPod("example-pod-2", "example-namespace")
                flow.first { snapshot -> snapshot.any { it.name == "example-pod-2" } }
            }
            job.join()
            assertTrue(!job.isCancelled)
        }
    }

    /**
     * Pins that cancelling the collector unwinds the flow — through the
     * `finally { informer.close() }` teardown — instead of wedging on the
     * `awaitCancellation()` suspension or on the has-synced wait.
     *
     * Deliberately named for what it can actually observe. That `informer.close()`
     * releases the underlying watch connection is not visible through the mock
     * server (it exposes no connection or listener count), so that half is
     * covered by code review plus the live smoke's thread-count check, not here.
     * The previous version of this test asserted `job.isCancelled` after calling
     * `job.cancel()`, which is true whether or not the flow ever unwound.
     */
    @Test
    fun `watchTailPods unwinds promptly when the collector is cancelled`() = runBlocking {
        seedPod("example-pod", "example-namespace")

        val started = CompletableDeferred<Boolean>()
        val emissionsAfterCancel = AtomicInteger(0)
        var cancelRequested = false
        val job = scope.launch {
            client.watchTailPods("example-namespace").collect {
                if (cancelRequested) emissionsAfterCancel.incrementAndGet()
                if (!started.isCompleted) started.complete(true)
            }
        }
        withTimeout(15_000) { started.await() }

        cancelRequested = true
        job.cancel()
        // join() returning inside the timeout is the real assertion: a flow that
        // never reached its finally block, or that swallowed the cancellation,
        // would hang here and fail the test rather than pass it.
        withTimeout(15_000) { job.join() }

        assertTrue(job.isCompleted, "collector job must have finished unwinding")
        // Churn the namespace after teardown; a still-subscribed collector would
        // observe it.
        seedPod("example-pod-after-cancel", "example-namespace")
        assertEquals(
            0,
            emissionsAfterCancel.get(),
            "no snapshot may be delivered after the collector was cancelled",
        )
    }

    @Test
    fun `watchTailPods fails the flow when the server denies the pod list`() = runBlocking {
        dispatcher.denyPodList = true

        val exception = assertFailsWith<Exception> {
            withTimeout(15_000) {
                client.watchTailPods("example-namespace").first()
            }
        }
        assertTrue(exception !is CancellationException, "expected a real failure, not cancellation: $exception")
    }

    @Test
    fun `watchTailPods rejects a blank namespace`() = runBlocking {
        val exception = assertFailsWith<IllegalArgumentException> {
            withTimeout(15_000) {
                client.watchTailPods("").first()
            }
        }
        assertTrue(exception.message.orEmpty().contains("non-blank"))
    }
}
