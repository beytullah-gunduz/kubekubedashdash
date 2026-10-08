package com.kubekubedashdash.helm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelmReleaseRepositoryTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun releaseJson(chart: String = "web", version: String = "1.0.0"): String = """{"info":{"status":"deployed"},"chart":{"metadata":{"name":"$chart","version":"$version"}},"manifest":"---\n# Source: x\napiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm\n"}"""

    private val payload = HelmReleaseCodec.encode(releaseJson())

    private fun ref(
        name: String = "web",
        revision: Int = 1,
        resourceVersion: String = "1",
        epoch: Long = 1,
        uid: String = "uid-$name-$revision",
    ) = HelmRevisionRef(
        driver = HelmDriver.SECRET,
        namespace = "default",
        objectName = "sh.helm.release.v1.$name.v$revision",
        uid = uid,
        resourceVersion = resourceVersion,
        releaseName = name,
        revision = revision,
        status = "deployed",
        createdAtEpochSeconds = null,
        modifiedAtEpochSeconds = null,
        creationTimestamp = null,
        epoch = epoch,
    )

    private fun repository(
        summaryCapacity: Int = 1_024,
        maxConcurrentFetches: Int = 4,
        fetch: (HelmRevisionRef) -> String?,
    ) = HelmReleaseRepository(
        scope = scope,
        fetch = fetch,
        dispatcher = Dispatchers.Default,
        summaryCapacity = summaryCapacity,
        maxConcurrentFetches = maxConcurrentFetches,
    )

    @Test
    fun `summary decodes the payload`() = runBlocking {
        val repo = repository { payload }

        val result = assertIs<HelmDecoded.Ok<HelmReleaseSummary>>(repo.summary(ref()))

        assertEquals("web-1.0.0", result.value.chart)
        assertEquals("deployed", result.value.status)
    }

    @Test
    fun `a detail load does not wait behind summaries that hold every summary permit`() = runBlocking {
        // The open panel's detail has permits of its own: a list's queue of summaries (here one
        // stuck fetch holding the only summary permit) must not delay it.
        val gate = CountDownLatch(1)
        val repo = repository(maxConcurrentFetches = 1) { r ->
            if (r.releaseName == "stuck") gate.await()
            payload
        }
        val stuck = scope.async { repo.summary(ref(name = "stuck")) }
        try {
            val detail = withTimeout(10_000) { repo.detail(ref(name = "open")) }

            assertIs<HelmDecoded.Ok<HelmReleaseDetail>>(detail)
            assertTrue(stuck.isActive, "the summary is still waiting on its fetch")
        } finally {
            gate.countDown()
        }
        assertIs<HelmDecoded.Ok<HelmReleaseSummary>>(stuck.await())
        Unit
    }

    @Test
    fun `a second call is answered from the cache`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            payload
        }

        repo.summary(ref())
        repo.summary(ref())

        assertEquals(1, fetches.get())
    }

    @Test
    fun `two concurrent calls for one key fetch once`() = runBlocking {
        // A fresh repository each round and an instant fetch: the window between a caller's
        // cache miss and an earlier load's completion handler is what this shakes out.
        repeat(200) { round ->
            val fetches = AtomicInteger()
            val repo = repository {
                fetches.incrementAndGet()
                payload
            }

            val results = coroutineScope {
                listOf(
                    async(Dispatchers.Default) { repo.summary(ref()) },
                    async(Dispatchers.Default) { repo.summary(ref()) },
                ).awaitAll()
            }

            assertEquals(1, fetches.get(), "round $round")
            results.forEach { assertIs<HelmDecoded.Ok<HelmReleaseSummary>>(it) }
        }
    }

    @Test
    fun `sixteen concurrent callers for one key fetch once, round after round`() = runBlocking {
        // Two callers rarely hit the window between one caller's cache miss and another load's
        // completion; sixteen do (without the cache re-check inside the load, about 1.5% of rounds fetch twice).
        repeat(500) { round ->
            val fetches = AtomicInteger()
            val repo = repository {
                fetches.incrementAndGet()
                payload
            }

            coroutineScope { (1..16).map { async(Dispatchers.Default) { repo.summary(ref()) } }.awaitAll() }

            assertEquals(1, fetches.get(), "round $round")
        }
    }

    @Test
    fun `many concurrent callers for one detail fetch once`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            Thread.sleep(50)
            payload
        }

        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { repo.detail(ref()) } }.awaitAll() }

        assertEquals(1, fetches.get())
        results.forEach { assertIs<HelmDecoded.Ok<HelmReleaseDetail>>(it) }
    }

    @Test
    fun `an Error from the fetch is a transient failure and nothing escapes`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            throw OutOfMemoryError("test")
        }

        val first = assertIs<HelmDecoded.Failed>(repo.summary(ref()))
        val second = assertIs<HelmDecoded.Failed>(repo.summary(ref()))

        assertTrue(first.transient)
        assertEquals("Couldn't read this release from the cluster.", first.message)
        assertTrue(second.transient)
        assertEquals(2, fetches.get(), "a transient failure is not cached")
    }

    @Test
    fun `a new resourceVersion fetches again`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            payload
        }

        repo.summary(ref(resourceVersion = "1"))
        repo.summary(ref(resourceVersion = "2"))

        assertEquals(2, fetches.get())
    }

    @Test
    fun `a new connection epoch fetches again`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            payload
        }

        repo.summary(ref(epoch = 1))
        repo.summary(ref(epoch = 2))

        assertEquals(2, fetches.get())
    }

    @Test
    fun `a blank uid is keyed by the object's address`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            payload
        }

        repo.summary(ref(name = "a", uid = ""))
        repo.summary(ref(name = "b", uid = ""))
        repo.summary(ref(name = "a", uid = ""))

        assertEquals(2, fetches.get())
    }

    @Test
    fun `the least recently used summary is evicted at capacity`() = runBlocking {
        val fetches = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val repo = repository(summaryCapacity = 2) { ref ->
            fetches.computeIfAbsent(ref.releaseName) { AtomicInteger() }.incrementAndGet()
            payload
        }

        repo.summary(ref(name = "a"))
        repo.summary(ref(name = "b"))
        repo.summary(ref(name = "a")) // a is now the most recently used
        repo.summary(ref(name = "c")) // evicts b
        repo.summary(ref(name = "a"))
        repo.summary(ref(name = "b"))

        assertEquals(1, fetches["a"]!!.get())
        assertEquals(2, fetches["b"]!!.get(), "b was evicted and fetched again")
        assertEquals(1, fetches["c"]!!.get())
    }

    @Test
    fun `a malformed payload is a permanent failure and is cached`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            HelmReleaseCodec.encode("this is not json")
        }

        val first = assertIs<HelmDecoded.Failed>(repo.summary(ref()))
        val second = assertIs<HelmDecoded.Failed>(repo.summary(ref()))

        assertEquals(false, first.transient)
        assertEquals("The release payload is not a Helm release.", first.message)
        assertEquals(first, second)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `a payload that is not base64 is a permanent failure with a fixed message`() = runBlocking {
        val repo = repository { "not base64 at all !!" }

        val failed = assertIs<HelmDecoded.Failed>(repo.summary(ref()))

        assertEquals(false, failed.transient)
        assertEquals("The release payload is not valid base64.", failed.message)
    }

    @Test
    fun `a decode exception from the fetch keeps its fixed message and is cached`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            throw HelmDecodeException("This revision has no release data.")
        }

        val first = assertIs<HelmDecoded.Failed>(repo.summary(ref()))
        repo.summary(ref())

        assertEquals("This revision has no release data.", first.message)
        assertEquals(false, first.transient)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `a plain exception from the fetch is a transient failure and is not cached`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            throw IllegalStateException("connection reset, token=marker-secret")
        }

        val first = assertIs<HelmDecoded.Failed>(repo.summary(ref()))
        repo.summary(ref())

        assertTrue(first.transient)
        assertEquals("Couldn't read this release from the cluster.", first.message)
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a forbidden fetch says so without echoing the server's text`() = runBlocking {
        val repo = repository { throw RuntimeException("secrets is forbidden: User \"dev@example.com\" cannot get resource") }

        val failed = assertIs<HelmDecoded.Failed>(repo.summary(ref()))

        assertTrue(failed.transient)
        assertEquals("Reading this release was refused (HTTP 403).", failed.message)
    }

    @Test
    fun `a fetch that finds nothing is missing`() = runBlocking {
        val repo = repository { null }

        assertEquals(HelmDecoded.Missing, repo.summary(ref()))
        assertEquals(HelmDecoded.Missing, repo.detail(ref()))
    }

    @Test
    fun `detail decodes the payload and fills the summary cache`() = runBlocking {
        val fetches = AtomicInteger()
        val repo = repository {
            fetches.incrementAndGet()
            payload
        }
        val ref = ref()
        assertNull(repo.cachedSummary(ref))

        val detail = assertIs<HelmDecoded.Ok<HelmReleaseDetail>>(repo.detail(ref))
        val cached = assertIs<HelmDecoded.Ok<HelmReleaseSummary>>(repo.cachedSummary(ref))
        repo.summary(ref)

        assertTrue("kind: ConfigMap" in detail.value.manifest)
        assertEquals("web-1.0.0", cached.value.chart)
        assertEquals(1, fetches.get(), "the summary came from the detail's decode")
    }

    @Test
    fun `cachedSummary is null before and Ok after a summary call`() = runBlocking {
        val repo = repository { payload }
        val ref = ref()

        assertNull(repo.cachedSummary(ref))
        repo.summary(ref)

        val cached = assertIs<HelmDecoded.Ok<HelmReleaseSummary>>(repo.cachedSummary(ref))
        assertEquals("web-1.0.0", cached.value.chart)
    }

    @Test
    fun `no more than the permitted number of fetches run at once`() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val repo = repository(maxConcurrentFetches = 2) {
            val now = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            Thread.sleep(40)
            running.decrementAndGet()
            payload
        }

        coroutineScope { (1..8).map { n -> async(Dispatchers.Default) { repo.summary(ref(name = "r$n")) } }.awaitAll() }

        assertTrue(peak.get() in 1..2, "peak concurrency was ${peak.get()}")
    }

    @Test
    fun `a closed session scope is a transient failure for a caller that is still active`() = runBlocking {
        val closed = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val repo = HelmReleaseRepository(closed, { payload }, Dispatchers.Default)
        closed.cancel()

        val result = repo.summary(ref())

        val failed = assertIs<HelmDecoded.Failed>(result)
        assertTrue(failed.transient)
    }
}
