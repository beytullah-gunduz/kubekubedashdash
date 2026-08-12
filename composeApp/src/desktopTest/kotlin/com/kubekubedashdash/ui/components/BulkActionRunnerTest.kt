package com.kubekubedashdash.ui.components

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

class BulkActionRunnerTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private data class Item(val uid: String, val name: String)

    private fun item(uid: String) = Item(uid, "item-$uid")

    private suspend fun awaitFinished(r: BulkActionRunner<Item>) = withTimeout(5_000) { r.state.first { it is BulkRunState.Finished } as BulkRunState.Finished }

    @Test
    fun `all pods succeed`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"), item("2"), item("3"))
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { Result.success(Unit) }
        val f = awaitFinished(runner)
        assertEquals(3, f.attempted)
        assertEquals(3, f.total)
        assertTrue(f.failures.isEmpty())
        assertFalse(f.cancelled)
    }

    @Test
    fun `mixed failures preserve order and reason`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"), item("2"), item("3"))
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { p ->
            when (p.name) {
                items[0].name -> Result.failure(IllegalStateException("boom-1"))
                items[2].name -> Result.failure(IllegalStateException("boom-3"))
                else -> Result.success(Unit)
            }
        }
        val f = awaitFinished(runner)
        assertEquals(listOf(items[0].name, items[2].name), f.failures.map { it.item.name })
        assertEquals("boom-1", f.failures[0].reason)
        assertEquals("boom-3", f.failures[1].reason)
    }

    @Test
    fun `null exception message falls back to evict label`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"))
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { Result.failure(IllegalStateException()) }
        val f = awaitFinished(runner)
        assertEquals("Evict failed", f.failures[0].reason)
    }

    @Test
    fun `null exception message falls back to delete label`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"))
        runner.start(BulkVerbs.Delete, items, itemLabel = { it.name }) { Result.failure(IllegalStateException()) }
        val f = awaitFinished(runner)
        assertEquals("Delete failed", f.failures[0].reason)
    }

    @Test
    fun `cancel stops issuing further actions and reports cancelled`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"), item("2"), item("3"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { p ->
            calls += p.name
            if (p.name == items[0].name) {
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
        assertEquals(listOf(items[0].name), calls)
    }

    @Test
    fun `start while running returns false and does not perturb the run`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val itemsA = listOf(item("1"), item("2"))
        val itemsB = listOf(item("3"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.start(BulkVerbs.Evict, itemsA, itemLabel = { it.name }) { p ->
            if (p.name == itemsA[0].name) {
                started.complete(Unit)
                release.await()
            }
            Result.success(Unit)
        }
        withTimeout(5_000) { started.await() }
        val startedSecond = runner.start(BulkVerbs.Delete, itemsB, itemLabel = { it.name }) { Result.success(Unit) }
        assertFalse(startedSecond)
        release.complete(Unit)
        val f = awaitFinished(runner)
        assertEquals(2, f.total)
        assertEquals(BulkVerbs.Evict, f.verb)
    }

    @Test
    fun `start with empty list returns false and leaves state null`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val startedRun = runner.start(BulkVerbs.Evict, emptyList(), itemLabel = { it.name }) { Result.success(Unit) }
        assertFalse(startedRun)
        assertNull(runner.state.value)
    }

    @Test
    fun `clear is a no-op while running and clears after finished`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { p ->
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
    fun `armOrReattach drops a finished leftover and mounts the fresh snapshot`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        runner.start(BulkVerbs.Evict, listOf(item("1")), itemLabel = { it.name }) { Result.success(Unit) }
        awaitFinished(runner)
        var mounted: Pair<BulkVerb, List<Item>>? = null
        runner.armOrReattach(BulkVerbs.Delete, listOf(item("2"))) { v, items -> mounted = v to items }
        assertEquals(BulkVerbs.Delete, mounted?.first)
        assertEquals(listOf(item("2")), mounted?.second)
        assertNull(runner.state.value)
    }

    @Test
    fun `armOrReattach with empty snapshot and no run mounts nothing`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        var mountCount = 0
        runner.armOrReattach(BulkVerbs.Delete, emptyList()) { _, _ -> mountCount++ }
        assertEquals(0, mountCount)
        assertNull(runner.state.value)
    }

    @Test
    fun `armOrReattach while running reattaches under the running verb and keeps the run`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"), item("2"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { p ->
            if (p.name == items[0].name) {
                started.complete(Unit)
                release.await()
            }
            Result.success(Unit)
        }
        withTimeout(5_000) { started.await() }
        var mounted: Pair<BulkVerb, List<Item>>? = null
        // The new snapshot — even a non-empty one under a different verb —
        // must be discarded in favor of the in-flight run.
        runner.armOrReattach(BulkVerbs.Delete, listOf(item("3"))) { v, mountedItems -> mounted = v to mountedItems }
        assertEquals(BulkVerbs.Evict, mounted?.first)
        assertEquals(emptyList<Item>(), mounted?.second)
        assertTrue(runner.state.value is BulkRunState.Running)
        release.complete(Unit)
        val f = awaitFinished(runner)
        assertEquals(BulkVerbs.Evict, f.verb)
        assertEquals(2, f.total)
    }

    @Test
    fun `throwing action degrades to per-pod failure and the loop continues`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"), item("2"), item("3"))
        runner.start(BulkVerbs.Evict, items, itemLabel = { it.name }) { p ->
            if (p.name == items[0].name) throw IllegalStateException("Not connected")
            Result.success(Unit)
        }
        val f = awaitFinished(runner)
        assertEquals(3, f.attempted)
        assertEquals(1, f.failures.size)
        assertEquals("Not connected", f.failures[0].reason)
    }

    @Test
    fun `progress advances one pod at a time in order`() = runBlocking {
        val runner = BulkActionRunner<Item>(scope)
        val items = listOf(item("1"), item("2"), item("3"))
        val started = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        runner.start(BulkVerbs.Delete, items, itemLabel = { it.name }) { p ->
            val i = items.indexOf(p)
            started[i].complete(Unit)
            release[i].await()
            Result.success(Unit)
        }
        for (i in 0..2) {
            withTimeout(5_000) { started[i].await() }
            withTimeout(5_000) {
                runner.state.first { it is BulkRunState.Running && it.done == i && it.currentItemLabel == items[i].name }
            }
            release[i].complete(Unit)
        }
        val f = awaitFinished(runner)
        assertEquals(3, f.attempted)
        assertEquals(3, f.total)
        assertFalse(f.cancelled)
    }
}
