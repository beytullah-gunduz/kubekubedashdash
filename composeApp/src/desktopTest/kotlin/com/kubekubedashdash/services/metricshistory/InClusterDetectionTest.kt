package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-cluster search state: one search per key whatever the callers do, a search that always
 * ends in a state, the clock on every cached answer, and invalidation. Most tests script the search
 * itself (detect) so the timing is theirs; three run the real search against a mock API server.
 */
class InClusterDetectionTest {

    private var previousCloudClis: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = AtomicLong(1_000_000)
    private val stubDelegate = lazy { ClusterStub() }
    private val stub by stubDelegate
    private val client = dummyClient()
    private val key = "example-context"

    @BeforeTest
    fun setUp() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun tearDown() {
        try {
            shutdownCleanly(scope, label = "InClusterDetectionTest")
            if (stubDelegate.isInitialized()) stub.close("InClusterDetectionTest")
        } finally {
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    private val found = DetectionState.Found(InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = ""))

    private fun detection(detect: suspend (KubernetesClient) -> DetectionState) = InClusterDetection(scope, clock::get, detect)

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    }

    private suspend fun InClusterDetection.ensureWith(provider: suspend () -> KubernetesClient? = { client }) = ensure(key, provider)

    // ── the clock ───────────────────────────────────────────────────────────────

    @Test
    fun `nothing found and cannot search count as no state after ten minutes, a failure after one`() = runBlocking<Unit> {
        for ((result, ttlMs) in listOf(
            DetectionState.NothingFound(clock.get()) to InClusterDetection.NOT_FOUND_TTL_MS,
            DetectionState.CannotSearch(clock.get()) to InClusterDetection.NOT_FOUND_TTL_MS,
            DetectionState.SearchFailed(clock.get()) to InClusterDetection.FAILED_TTL_MS,
        )) {
            clock.set(1_000_000)
            val detection = detection { result }
            detection.ensureWith()
            assertEquals(result, detection.current(key), "$result")

            clock.set(1_000_000 + ttlMs)
            assertEquals(result, detection.current(key), "$result at its age limit")

            clock.set(1_000_000 + ttlMs + 1)
            assertNull(detection.current(key), "$result past its age limit")
        }
    }

    @Test
    fun `a found Prometheus and a running search do not age out`() = runBlocking<Unit> {
        val detection = detection { found }
        detection.ensureWith()

        clock.addAndGet(7L * 24 * 60 * 60 * 1000)

        assertEquals(found, detection.current(key))

        val gate = CompletableDeferred<Unit>()
        val searching = detection {
            gate.await()
            found
        }
        val caller = launch(Dispatchers.Default) { searching.ensureWith() }
        awaitUntil { searching.current(key) == DetectionState.Searching }
        clock.addAndGet(7L * 24 * 60 * 60 * 1000)
        assertEquals(DetectionState.Searching, searching.current(key))
        gate.complete(Unit)
        caller.join()
    }

    @Test
    fun `an expired answer is searched for again by one ensure`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val detection = detection { DetectionState.NothingFound(clock.get()).also { calls.incrementAndGet() } }
        detection.ensureWith()
        detection.ensureWith()
        assertEquals(1, calls.get(), "a fresh answer is not searched for again")

        clock.addAndGet(InClusterDetection.NOT_FOUND_TTL_MS + 1)
        assertNull(detection.current(key))
        detection.ensureWith()

        assertEquals(2, calls.get())
        assertEquals(DetectionState.NothingFound(clock.get()), detection.current(key))
    }

    @Test
    fun `a failure is not searched for again within a minute and is retried after it`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            throw IllegalStateException("boom")
        }

        detection.ensureWith()

        assertEquals(DetectionState.SearchFailed(clock.get()), detection.current(key))
        clock.addAndGet(InClusterDetection.FAILED_TTL_MS)
        detection.ensureWith()
        assertEquals(1, calls.get(), "retried within the minute")

        clock.addAndGet(1)
        assertNull(detection.current(key))
        detection.ensureWith()
        assertEquals(2, calls.get())
        assertEquals(DetectionState.SearchFailed(clock.get()), detection.current(key))
    }

    // ── what a search can end in ────────────────────────────────────────────────

    @Test
    fun `the answer of the search is stored for the key and for no other`() = runBlocking<Unit> {
        val detection = detection { found }

        detection.ensureWith()

        assertEquals(found, detection.current(key))
        assertNull(detection.current("other-context"))
        assertEquals(mapOf(key to found), detection.state.value)
    }

    @Test
    fun `an exception from the search records a failure, not nothing found, and is not thrown to the caller`() = runBlocking<Unit> {
        val detection = detection { throw java.io.IOException("connection reset") }

        detection.ensureWith()

        assertEquals(DetectionState.SearchFailed(clock.get()), detection.current(key))
    }

    @Test
    fun `no client is a failed search`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            found
        }

        detection.ensureWith { null }

        assertEquals(DetectionState.SearchFailed(clock.get()), detection.current(key))
        assertEquals(0, calls.get())
    }

    @Test
    fun `a client provider that throws is a failed search`() = runBlocking<Unit> {
        val detection = detection { found }

        detection.ensureWith { throw IllegalStateException("closed") }

        assertEquals(DetectionState.SearchFailed(clock.get()), detection.current(key))
    }

    @Test
    fun `the search is running while it runs`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val detection = detection {
            gate.await()
            found
        }

        val caller = launch(Dispatchers.Default) { detection.ensureWith() }
        awaitUntil { detection.current(key) != null }

        assertEquals(DetectionState.Searching, detection.current(key))
        assertEquals(DetectionState.Searching, detection.state.value[key])
        gate.complete(Unit)
        caller.join()
        assertEquals(found, detection.current(key))
    }

    @Test
    fun `an answer already held is not searched for again`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            found
        }
        detection.ensureWith()

        detection.ensureWith()
        detection.ensureWith()

        assertEquals(1, calls.get())
    }

    // ── single flight, and callers that leave ───────────────────────────────────

    @Test
    fun `concurrent callers share one search`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            gate.await()
            found
        }

        val callers = List(3) { launch(Dispatchers.Default) { detection.ensureWith() } }
        awaitUntil { calls.get() >= 1 }
        delay(300)

        assertEquals(1, calls.get(), "a second search was started")
        gate.complete(Unit)
        callers.joinAll()
        assertEquals(1, calls.get())
        assertEquals(found, detection.current(key))
    }

    @Test
    fun `two keys are searched independently`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            found
        }

        detection.ensure("cluster-a") { client }
        detection.ensure("cluster-b") { client }

        assertEquals(2, calls.get())
        assertEquals(found, detection.current("cluster-a"))
        assertEquals(found, detection.current("cluster-b"))
    }

    @Test
    fun `a caller that is cancelled mid-search does not cancel it, and the key is not left searching`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            gate.await()
            found
        }
        val caller = launch(Dispatchers.Default) { detection.ensureWith() }
        awaitUntil { calls.get() == 1 }

        caller.cancel()
        caller.join()

        assertEquals(DetectionState.Searching, detection.current(key), "the search must outlive its caller")
        gate.complete(Unit)
        awaitUntil { detection.current(key) == found }

        // A second caller finds the answer there: no further search, and it does not wait.
        withTimeout(2_000) { detection.ensureWith() }
        assertEquals(1, calls.get())
    }

    @Test
    fun `a second caller that arrives after the first left joins the same search`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            gate.await()
            found
        }
        val first = launch(Dispatchers.Default) { detection.ensureWith() }
        awaitUntil { calls.get() == 1 }
        first.cancel()
        first.join()

        val second = launch(Dispatchers.Default) { detection.ensureWith() }
        delay(200)
        gate.complete(Unit)
        withTimeout(5_000) { second.join() }

        assertEquals(1, calls.get())
        assertEquals(found, detection.current(key))
    }

    @Test
    fun `closing the scope the search runs in leaves a failure, not a search that never ends`() = runBlocking<Unit> {
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val detection = InClusterDetection(ownScope, clock::get) {
            started.incrementAndGet()
            gate.await()
            found
        }
        val caller = launch(Dispatchers.Default) { detection.ensureWith() }
        awaitUntil { started.get() == 1 }

        ownScope.cancel()

        awaitUntil { detection.current(key) is DetectionState.SearchFailed }
        withTimeout(5_000) { caller.join() }
    }

    // ── invalidate ──────────────────────────────────────────────────────────────

    @Test
    fun `invalidate forgets the answer and the next ensure searches again`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val detection = detection {
            calls.incrementAndGet()
            found
        }
        detection.ensureWith()

        detection.invalidate(key)

        assertNull(detection.current(key))
        assertEquals(emptyMap(), detection.state.value)
        detection.ensureWith()
        assertEquals(2, calls.get())
    }

    @Test
    fun `invalidate leaves the other keys alone`() = runBlocking<Unit> {
        val detection = detection { found }
        detection.ensure("cluster-a") { client }
        detection.ensure("cluster-b") { client }

        detection.invalidate("cluster-a")

        assertNull(detection.current("cluster-a"))
        assertEquals(found, detection.current("cluster-b"))
    }

    @Test
    fun `a search that was invalidated never writes its result, even one that cannot be cancelled`() = runBlocking<Unit> {
        // A thread blocked on a latch takes no notice of the cancellation that invalidate sends: the write is what must be refused.
        val release = CountDownLatch(1)
        val returned = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val detection = detection {
            if (calls.incrementAndGet() == 1) {
                release.await(10, TimeUnit.SECONDS)
                returned.complete(Unit)
                found
            } else {
                DetectionState.NothingFound(clock.get())
            }
        }
        val firstCaller = launch(Dispatchers.Default) { detection.ensureWith() }
        awaitUntil { calls.get() == 1 }

        detection.invalidate(key)
        assertNull(detection.current(key))

        // A newer search finishes first; the abandoned one then comes back with its stale answer.
        detection.ensureWith()
        assertEquals(DetectionState.NothingFound(clock.get()), detection.current(key))
        release.countDown()
        withTimeout(5_000) { returned.await() }
        firstCaller.join()
        delay(200)

        assertEquals(DetectionState.NothingFound(clock.get()), detection.current(key), "the abandoned search overwrote the newer answer")
    }

    @Test
    fun `invalidate while searching leaves no state, and the waiting caller is released`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val detection = detection {
            gate.await()
            found
        }
        val caller = launch(Dispatchers.Default) { detection.ensureWith() }
        awaitUntil { detection.current(key) == DetectionState.Searching }

        detection.invalidate(key)

        withTimeout(5_000) { caller.join() }
        assertNull(detection.current(key))
        assertEquals(emptyMap(), detection.state.value)
    }

    // ── the real search ─────────────────────────────────────────────────────────

    @Test
    fun `real lists - concurrent callers cause one round of four reads`() = runBlocking<Unit> {
        stub.finders = { _, answer -> answer.setBodyDelay(Duration.ofMillis(800)) }
        val detection = InClusterDetection(scope, clock::get)
        val live = stub.client()

        val callers = List(2) { launch(Dispatchers.Default) { detection.ensure(key) { live } } }
        awaitUntil { stub.finderRequests.isNotEmpty() }
        callers.joinAll()

        assertEquals(4, stub.finderRequests.size, "two searches were started")
        assertIs<DetectionState.NothingFound>(detection.current(key))
    }

    @Test
    fun `real lists - a caller cancelled in the middle of the lists leaves the search to finish`() = runBlocking<Unit> {
        stub.addService("monitoring", "prometheus-operated", mapOf("operated-prometheus" to "true"), listOf("web" to 9090))
        stub.finders = { _, answer -> answer.setBodyDelay(Duration.ofMillis(1_500)) }
        val detection = InClusterDetection(scope, clock::get)
        val live = stub.client()
        val caller = launch(Dispatchers.Default) { detection.ensure(key) { live } }
        awaitUntil { stub.finderRequests.isNotEmpty() }

        caller.cancel()
        caller.join()

        assertEquals(DetectionState.Searching, detection.current(key))
        awaitUntil { detection.current(key) is DetectionState.Found }
        val finderReads = stub.finderRequests.size
        withTimeout(2_000) { detection.ensure(key) { live } }
        assertEquals(finderReads, stub.finderRequests.size, "the second caller searched again")
        assertEquals(1, stub.proxyRequests.size)
    }

    @Test
    fun `real lists - an expired nothing found lists again on one ensure`() = runBlocking<Unit> {
        val detection = InClusterDetection(scope, clock::get)
        val live = stub.client()
        detection.ensure(key) { live }
        assertEquals(4, stub.finderRequests.size)
        assertIs<DetectionState.NothingFound>(detection.current(key))

        detection.ensure(key) { live }
        assertEquals(4, stub.finderRequests.size, "a fresh answer was searched for again")

        clock.addAndGet(InClusterDetection.NOT_FOUND_TTL_MS + 1)
        assertNull(detection.current(key))
        detection.ensure(key) { live }

        assertEquals(8, stub.finderRequests.size)
    }

    @Test
    fun `real lists - a closed client records a failure that expires after a minute, never nothing found`() = runBlocking<Unit> {
        val detection = InClusterDetection(scope, clock::get)
        val closed = stub.client().also { it.close() }

        detection.ensure(key) { closed }

        assertEquals(DetectionState.SearchFailed(clock.get()), detection.current(key))
        clock.addAndGet(InClusterDetection.FAILED_TTL_MS + 1)
        assertNull(detection.current(key))
        assertTrue(stub.finderRequests.isEmpty())
    }
}
