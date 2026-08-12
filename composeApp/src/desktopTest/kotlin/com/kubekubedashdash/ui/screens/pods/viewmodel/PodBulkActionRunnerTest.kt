package com.kubekubedashdash.ui.screens.pods.viewmodel

import com.kubekubedashdash.models.PodInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodBulkActionRunnerTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun pod(uid: String, name: String = "pod-$uid", ns: String = "ns-a") = PodInfo(
        uid = uid, name = name, namespace = ns, status = "Running", ready = "1/1",
        restarts = 0, age = "1m", node = "node-a", ip = "10.0.0.1",
        labels = emptyMap(), annotations = emptyMap(), containers = emptyList(),
    )

    private suspend fun awaitFinished(r: PodBulkActionRunner) = withTimeout(5_000) { r.state.first { it is BulkRunState.Finished } as BulkRunState.Finished }

    @Test
    fun `all pods succeed`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"), pod("2"), pod("3"))
        runner.start(BulkPodVerb.EVICT, pods) { Result.success(Unit) }
        val f = awaitFinished(runner)
        assertEquals(3, f.attempted)
        assertEquals(3, f.total)
        assertTrue(f.failures.isEmpty())
        assertFalse(f.cancelled)
    }

    @Test
    fun `mixed failures preserve order and reason`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"), pod("2"), pod("3"))
        runner.start(BulkPodVerb.EVICT, pods) { p ->
            when (p.name) {
                pods[0].name -> Result.failure(IllegalStateException("boom-1"))
                pods[2].name -> Result.failure(IllegalStateException("boom-3"))
                else -> Result.success(Unit)
            }
        }
        val f = awaitFinished(runner)
        assertEquals(listOf(pods[0].name, pods[2].name), f.failures.map { it.pod.name })
        assertEquals("boom-1", f.failures[0].reason)
        assertEquals("boom-3", f.failures[1].reason)
    }

    @Test
    fun `null exception message falls back to evict label`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"))
        runner.start(BulkPodVerb.EVICT, pods) { Result.failure(IllegalStateException()) }
        val f = awaitFinished(runner)
        assertEquals("Evict failed", f.failures[0].reason)
    }

    @Test
    fun `null exception message falls back to delete label`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"))
        runner.start(BulkPodVerb.DELETE, pods) { Result.failure(IllegalStateException()) }
        val f = awaitFinished(runner)
        assertEquals("Delete failed", f.failures[0].reason)
    }

    @Test
    fun `cancel stops issuing further actions and reports cancelled`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"), pod("2"), pod("3"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        runner.start(BulkPodVerb.EVICT, pods) { p ->
            calls += p.name
            if (p.name == pods[0].name) {
                started.complete(Unit)
                release.await()
            }
            Result.success(Unit)
        }
        withTimeout(5_000) { started.await() }
        runner.cancel()
        // The request is observable immediately so the UI can disable Stop.
        withTimeout(5_000) { runner.state.first { it is BulkRunState.Running && it.cancelRequested } }
        release.complete(Unit)
        val f = awaitFinished(runner)
        assertEquals(1, f.attempted)
        assertTrue(f.cancelled)
        assertEquals(listOf(pods[0].name), calls)
    }

    @Test
    fun `start while running returns false and does not perturb the run`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val podsA = listOf(pod("1"), pod("2"))
        val podsB = listOf(pod("3"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.start(BulkPodVerb.EVICT, podsA) { p ->
            if (p.name == podsA[0].name) {
                started.complete(Unit)
                release.await()
            }
            Result.success(Unit)
        }
        withTimeout(5_000) { started.await() }
        val startedSecond = runner.start(BulkPodVerb.DELETE, podsB) { Result.success(Unit) }
        assertFalse(startedSecond)
        release.complete(Unit)
        val f = awaitFinished(runner)
        assertEquals(2, f.total)
        assertEquals(BulkPodVerb.EVICT, f.verb)
    }

    @Test
    fun `start with empty list returns false and leaves state null`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val startedRun = runner.start(BulkPodVerb.EVICT, emptyList()) { Result.success(Unit) }
        assertFalse(startedRun)
        assertNull(runner.state.value)
    }

    @Test
    fun `clear is a no-op while running and clears after finished`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.start(BulkPodVerb.EVICT, pods) { p ->
            started.complete(Unit)
            release.await()
            Result.success(Unit)
        }
        withTimeout(5_000) { started.await() }
        runner.clear()
        assertTrue(runner.state.value is BulkRunState.Running)
        release.complete(Unit)
        awaitFinished(runner)
        runner.clear()
        assertNull(runner.state.value)
    }

    @Test
    fun `throwing action degrades to per-pod failure and the loop continues`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"), pod("2"), pod("3"))
        runner.start(BulkPodVerb.EVICT, pods) { p ->
            if (p.name == pods[0].name) throw IllegalStateException("Not connected")
            Result.success(Unit)
        }
        val f = awaitFinished(runner)
        assertEquals(3, f.attempted)
        assertEquals(1, f.failures.size)
        assertEquals("Not connected", f.failures[0].reason)
    }

    @Test
    fun `progress advances one pod at a time in order`() = runBlocking {
        val runner = PodBulkActionRunner(scope)
        val pods = listOf(pod("1"), pod("2"), pod("3"))
        val started = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        runner.start(BulkPodVerb.DELETE, pods) { p ->
            val i = pods.indexOf(p)
            started[i].complete(Unit)
            release[i].await()
            Result.success(Unit)
        }
        for (i in 0..2) {
            withTimeout(5_000) { started[i].await() }
            withTimeout(5_000) {
                runner.state.first { it is BulkRunState.Running && it.done == i && it.currentPodName == pods[i].name }
            }
            release[i].complete(Unit)
        }
        val f = awaitFinished(runner)
        assertEquals(3, f.attempted)
        assertEquals(3, f.total)
        assertFalse(f.cancelled)
    }
}
