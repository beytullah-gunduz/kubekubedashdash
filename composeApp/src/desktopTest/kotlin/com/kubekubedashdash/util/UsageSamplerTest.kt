package com.kubekubedashdash.util

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.ResourceUsageSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session usage sampler against fakes for everything it is wired to: the
 * connection trigger, the namespace selection, the connected context, the
 * clock and the read itself. Nothing here touches a cluster or the network.
 */
class UsageSamplerTest {

    private companion object {
        const val FIRST_EPOCH = 1_700_000_000L
        const val HOUR_MS = 3_600_000L
        const val WAIT_MS = 10_000L
    }

    private lateinit var scope: CoroutineScope
    private val trigger = MutableStateFlow(1L)
    private val selection = MutableStateFlow<NamespaceScope>(NamespaceScope.All)

    @Volatile private var context: String? = "example-context"

    @Volatile private var connected = true

    // Each call to the clock moves it on by a slot, so every sample gets a slot of its own.
    private val clock = AtomicLong(FIRST_EPOCH)
    private val fetches = AtomicInteger()

    // Lets a read that blocks on purpose finish, so teardown can always join the scope.
    private val release = CountDownLatch(1)

    @Volatile private var read: (NamespaceScope) -> ResourceUsageSummary = { usage(cpuUsed = fetches.get().toLong()) }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        release.countDown()
        runBlocking {
            scope.coroutineContext[Job]?.let { job ->
                job.cancel()
                job.join()
            }
        }
    }

    private fun usage(cpuUsed: Long = 100, cpuCapacity: Long = 4_000, memUsed: Long = 1_000, memCapacity: Long = 4_000, available: Boolean = true) = ResourceUsageSummary(cpuUsed, cpuCapacity, memUsed, memCapacity, metricsAvailable = available)

    private fun sampler(intervalMs: Long = HOUR_MS, stepSec: Long = 30L): UsageSampler = UsageSampler(
        scope = scope,
        trigger = trigger,
        park = { if (!connected) awaitCancellation() },
        namespaceScope = selection,
        connectedContext = { context },
        fetch = { s ->
            fetches.incrementAndGet()
            read(s)
        },
        intervalMs = intervalMs,
        nowEpochSec = { clock.getAndAdd(stepSec) },
    )

    /** Polls rather than collects: the sampler must fill its buffer with nobody subscribed. */
    private suspend fun UsageSampler.await(what: String, condition: (List<UsageSample>) -> Boolean): List<UsageSample> {
        try {
            withTimeout(WAIT_MS) { while (!condition(samples.value)) delay(5) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; samples: ${samples.value}", e)
        }
        return samples.value
    }

    @Test
    fun `samples accumulate while nothing collects them`() = runBlocking {
        val sampler = sampler(intervalMs = 20)

        val seen = sampler.await("three samples") { it.size >= 3 }

        assertEquals(seen.map { it.epochSec }, seen.map { it.epochSec }.sorted(), "oldest first")
        assertEquals(seen.size, seen.map { it.epochSec }.toSet().size, "one per slot")
    }

    @Test
    fun `a sample carries the fractions, the raw figures and a slot-aligned time`() = runBlocking {
        val offSlot = FIRST_EPOCH + 17
        clock.set(offSlot)
        read = { usage(cpuUsed = 1_000, cpuCapacity = 4_000, memUsed = 3_000, memCapacity = 12_000) }
        val sampler = sampler()

        val sample = sampler.await("a sample") { it.isNotEmpty() }.single()

        assertEquals(offSlot - offSlot % USAGE_SAMPLE_SLOT_SEC, sample.epochSec)
        assertTrue(sample.epochSec <= offSlot && offSlot - sample.epochSec < USAGE_SAMPLE_SLOT_SEC)
        assertEquals(0.25f, sample.cpuFraction)
        assertEquals(0.25f, sample.memFraction)
        assertEquals(listOf(1_000L, 4_000L, 3_000L, 12_000L), listOf(sample.cpuUsedMillis, sample.cpuCapacityMillis, sample.memUsedBytes, sample.memCapacityBytes))
    }

    @Test
    fun `the buffer keeps the last 120 samples`() = runBlocking {
        val sampler = sampler(intervalMs = 1)

        val seen = sampler.await("a full hour and then some") { it.size == USAGE_SAMPLE_CAPACITY && it.first().epochSec > FIRST_EPOCH + 5 * USAGE_SAMPLE_SLOT_SEC }

        assertEquals(USAGE_SAMPLE_CAPACITY, seen.size)
        assertEquals(seen.map { it.epochSec }, seen.map { it.epochSec }.sorted())
        assertEquals(USAGE_SAMPLE_CAPACITY, seen.map { it.epochSec }.toSet().size)
        assertTrue(sampler.samples.value.size <= USAGE_SAMPLE_CAPACITY)
    }

    @Test
    fun `a sample in the slot of the last one replaces it`() = runBlocking {
        val sampler = sampler(stepSec = 0)
        sampler.await("the first sample") { it.size == 1 }
        assertEquals(1L, sampler.samples.value.single().cpuUsedMillis)

        trigger.value = 2L

        val seen = sampler.await("the reconnect's sample") { it.singleOrNull()?.cpuUsedMillis == 2L }
        assertEquals(1, seen.size)
    }

    @Test
    fun `a namespace change clears the samples and samples again at once`() = runBlocking {
        val sampler = sampler()
        val observed = CopyOnWriteArrayList<List<UsageSample>>()
        val collector = scope.launch { sampler.samples.collect { observed += it } }
        sampler.await("the first sample") { it.size == 1 }

        selection.value = NamespaceScope.single("team-a")

        val seen = sampler.await("a series of the new selection only") { it.singleOrNull()?.cpuUsedMillis == 2L }
        collector.cancel()
        assertEquals(2, fetches.get(), "one read per selection; the interval is an hour")
        assertTrue(observed.all { it.size <= 1 }, "the old reading never sits beside the new one: $observed")
        assertEquals(1, seen.size)
    }

    @Test
    fun `a reconnect with the same selection and context keeps the samples`() = runBlocking {
        val sampler = sampler()
        sampler.await("the first sample") { it.size == 1 }

        trigger.value = 2L

        val seen = sampler.await("a second sample beside the first") { it.size == 2 }
        assertEquals(listOf(1L, 2L), seen.map { it.cpuUsedMillis })
    }

    @Test
    fun `a bump while disconnected parks the loop and keeps the samples until the reconnect`() = runBlocking {
        val sampler = sampler()
        sampler.await("the first sample") { it.size == 1 }

        connected = false
        trigger.value = 2L
        delay(300)

        assertEquals(1, fetches.get(), "no read while there is no live client")
        assertEquals(listOf(1L), sampler.samples.value.map { it.cpuUsedMillis })

        connected = true
        trigger.value = 3L

        val seen = sampler.await("the reconnect's sample") { it.size == 2 }
        assertEquals(listOf(1L, 2L), seen.map { it.cpuUsedMillis })
    }

    @Test
    fun `a context change clears the samples`() = runBlocking {
        val sampler = sampler()
        sampler.await("the first sample") { it.size == 1 }

        context = "example-cluster"
        trigger.value = 2L

        val seen = sampler.await("a series of the new context only") { it.singleOrNull()?.cpuUsedMillis == 2L }
        assertEquals(1, seen.size)
    }

    @Test
    fun `skipped, unavailable and failed reads add nothing and the loop keeps going`() = runBlocking {
        read = {
            when (fetches.get()) {
                1 -> throw SkippedPoll(RuntimeException("synthetic"))
                2 -> usage(available = false)
                3 -> error("synthetic")
                else -> usage(cpuUsed = fetches.get().toLong())
            }
        }
        val sampler = sampler(intervalMs = 20)

        val seen = sampler.await("a sample after the three that add nothing") { it.isNotEmpty() }

        assertTrue(fetches.get() >= 4)
        assertTrue(seen.all { it.cpuUsedMillis >= 4L }, "only the reads that answered are kept: $seen")
    }

    @Test
    fun `no capacity is a zero fraction, not a missing sample`() = runBlocking {
        read = { usage(cpuUsed = 300, cpuCapacity = 0, memUsed = 500, memCapacity = 0) }
        val sampler = sampler()

        val sample = sampler.await("a sample") { it.isNotEmpty() }.single()

        assertEquals(0f, sample.cpuFraction)
        assertEquals(0f, sample.memFraction)
        assertEquals(300L, sample.cpuUsedMillis)
        assertEquals(0L, sample.cpuCapacityMillis)
    }

    @Test
    fun `a read that finishes after a namespace change is not kept`() = runBlocking {
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        read = { s ->
            if (s == NamespaceScope.All) {
                started.countDown()
                // Like a blocking client call that ignores the interrupt and still answers.
                var released = false
                while (!released) {
                    released = try {
                        release.await(10, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        false
                    }
                }
                returned.countDown()
                usage(cpuUsed = 111)
            } else {
                usage(cpuUsed = 222)
            }
        }
        val sampler = sampler()
        val observed = CopyOnWriteArrayList<List<UsageSample>>()
        val collector = scope.launch { sampler.samples.collect { observed += it } }
        assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS), "the first read started")

        selection.value = NamespaceScope.single("team-a")
        // The loop of the new selection starts only once the old one has unwound, so the
        // old read is let go here: it answers after the selection changed.
        delay(100)
        release.countDown()
        assertTrue(returned.await(WAIT_MS, TimeUnit.MILLISECONDS), "the old read finished")
        sampler.await("the new selection's sample") { it.singleOrNull()?.cpuUsedMillis == 222L }
        delay(200)
        collector.cancel()

        assertEquals(listOf(222L), sampler.samples.value.map { it.cpuUsedMillis })
        assertTrue(observed.flatten().none { it.cpuUsedMillis == 111L }, "the old selection's reading was never shown: $observed")
    }

    @Test
    fun `an interval of zero never starts the sampler`() = runBlocking {
        val sampler = sampler(intervalMs = 0)

        delay(200)

        assertEquals(0, fetches.get())
        assertTrue(sampler.samples.value.isEmpty())
    }
}
