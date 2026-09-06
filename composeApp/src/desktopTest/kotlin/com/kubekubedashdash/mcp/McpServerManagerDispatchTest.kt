package com.kubekubedashdash.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Ktor CIO runs the MCP tool and resource handlers on Dispatchers.IO, and
 * every fabric8 call they make is blocking I/O: against a dead cluster each
 * one parked an IO slot for fabric8's whole retry budget, and enough of them
 * starved the app's own informers, which share the pool. The blocking work
 * now goes through [McpServerManager.blockingCall], which must (1) run on a
 * worker, (2) admit at most [McpServerManager.MCP_WORKERS] calls at a time,
 * and (3) be interruptible, so a cancelled request frees its slot instead of
 * finishing a fetch nobody will read. And `get_pod_logs`' `tailLines` is
 * bounded: an agent asking for 2147483647 lines used to pull a pod's entire
 * log into one String.
 */
class McpServerManagerDispatchTest {

    @Test
    fun `tailLines is defaulted when missing or nonsensical and capped when huge`() {
        val default = McpServerManager.DEFAULT_TAIL_LINES
        val max = McpServerManager.MAX_TAIL_LINES
        assertEquals(default, McpServerManager.clampTailLines(null))
        assertEquals(default, McpServerManager.clampTailLines(""))
        assertEquals(default, McpServerManager.clampTailLines("abc"))
        assertEquals(default, McpServerManager.clampTailLines("12.5"))
        assertEquals(default, McpServerManager.clampTailLines("0"))
        assertEquals(default, McpServerManager.clampTailLines("-5"))
        assertEquals(1, McpServerManager.clampTailLines("1"))
        assertEquals(50, McpServerManager.clampTailLines("50"))
        assertEquals(max, McpServerManager.clampTailLines(max.toString()))
        assertEquals(max, McpServerManager.clampTailLines("2147483647"))
        assertEquals(default, McpServerManager.clampTailLines("99999999999"))
    }

    @Test
    fun `blocking work runs on an IO worker, not the caller's thread`() {
        val loop = Executors.newSingleThreadExecutor { r -> Thread(r, "mcp-loop") }.asCoroutineDispatcher()
        try {
            runBlocking(loop) {
                val caller = Thread.currentThread()
                // startsWith: with JVM assertions on (Gradle's default for tests) kotlinx.coroutines'
                // debug mode appends " @coroutine#N" to the running thread's name.
                assertTrue(caller.name.startsWith("mcp-loop"), "expected the loop thread, got ${caller.name}")
                val worker = McpServerManager.blockingCall { Thread.currentThread() }
                assertNotSame(caller, worker, "the blocking work ran on the calling dispatcher's thread")
                assertTrue(worker.name.startsWith("DefaultDispatcher-worker"), "expected an IO worker, got ${worker.name}")
            }
        } finally {
            loop.close()
        }
    }

    @Test
    fun `at most MCP_WORKERS blocking calls run at once`() = runBlocking<Unit> {
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()
        val release = CountDownLatch(1)
        val jobs = List(McpServerManager.MCP_WORKERS * 3) {
            launch {
                McpServerManager.blockingCall {
                    val now = running.incrementAndGet()
                    maxSeen.accumulateAndGet(now, ::maxOf)
                    release.await(10, TimeUnit.SECONDS)
                    running.decrementAndGet()
                }
            }
        }
        // Give the pool time to admit everything it is allowed to.
        delay(500)
        assertEquals(McpServerManager.MCP_WORKERS, running.get(), "the bounded view must admit exactly MCP_WORKERS calls")
        release.countDown()
        withTimeout(10_000) { jobs.joinAll() }
        assertEquals(McpServerManager.MCP_WORKERS, maxSeen.get())
    }

    @Test
    fun `cancelling the request interrupts the blocking work`() = runBlocking<Unit> {
        val outcome = CompletableDeferred<String>()
        val job = launch {
            McpServerManager.blockingCall {
                try {
                    Thread.sleep(10_000)
                    outcome.complete("finished")
                } catch (e: InterruptedException) {
                    outcome.complete("interrupted")
                    throw e
                }
            }
        }
        delay(200)
        job.cancel()
        withTimeout(2_000) { job.join() }
        assertEquals("interrupted", withTimeout(2_000) { outcome.await() })
    }
}
