package com.kubekubedashdash.data.repository

import androidx.datastore.core.CorruptionException
import com.kubekubedashdash.data.datastore.LoadFault
import com.kubekubedashdash.data.datastore.PreferenceStorageHealth
import com.kubekubedashdash.data.datastore.SaveFault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repositories' `dataStore.data` collectors used to end silently on a
 * read failure (`catch { }` in PreferenceRepository, nothing at all in the
 * other two), and every later `edit` then threw into an unhandled scope.
 * The recovery operator retries a read that failed on the way in, gives
 * up with a logged, reported fault, and lets the flow complete normally;
 * the write-side helpers turn a failed save into a reported fault instead
 * of an uncaught exception, and keep a serial consumer alive.
 *
 * Pure: fake flows only; no DataStore, no files.
 */
class PreferenceStorageRecoveryTest {

    private val noDelay = listOf(0L, 0L, 0L)

    @Test
    fun `a read that fails on the way in is retried until it succeeds`() = runBlocking<Unit> {
        val health = PreferenceStorageHealth()
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts < 3) throw IOException("busy") else emit("seeded")
        }

        val seen = source.recoveringPreferenceReads("test", health, noDelay).toList()

        assertEquals(listOf("seeded"), seen)
        assertEquals(3, attempts)
        assertTrue(health.state.value.healthy, "a read that eventually succeeded is not a fault")
    }

    @Test
    fun `when the budget is spent the flow completes normally and the fault is reported`() = runBlocking<Unit> {
        val health = PreferenceStorageHealth()
        var attempts = 0
        val source = flow<String> {
            attempts++
            throw IOException("unreadable")
        }

        val seen = source.recoveringPreferenceReads("test", health, listOf(0L, 0L)).toList()

        assertEquals(emptyList(), seen, "the collector must not throw")
        assertEquals(3, attempts, "one read plus one retry per budget entry")
        assertEquals(LoadFault(exceptionClass = "IOException", backupFileName = null), health.state.value.load)
        assertNull(health.state.value.save)
    }

    @Test
    fun `a corruption exception is reported at once and never retried`() = runBlocking<Unit> {
        val health = PreferenceStorageHealth()
        var attempts = 0
        val source = flow<String> {
            attempts++
            throw CorruptionException("unparseable")
        }

        val seen = source.recoveringPreferenceReads("test", health, noDelay).toList()

        assertEquals(emptyList(), seen)
        assertEquals(1, attempts, "the store's handler already replaced what it could; retrying cannot help")
        assertEquals(LoadFault(exceptionClass = "CorruptionException", backupFileName = null), health.state.value.load)
    }

    @Test
    fun `the first load fault of a run wins and the latest save fault wins`() {
        val health = PreferenceStorageHealth()

        health.reportLoadFault(LoadFault("CorruptionException", "settings.corrupt-1"))
        health.reportLoadFault(LoadFault("IOException"))
        assertEquals(LoadFault("CorruptionException", "settings.corrupt-1"), health.state.value.load)

        health.reportSaveFault(SaveFault("IOException"))
        health.reportSaveFault(SaveFault("AccessDeniedException"))
        assertEquals(SaveFault("AccessDeniedException"), health.state.value.save)
        assertTrue(!health.state.value.healthy)
    }

    @Test
    fun `cancelling the collector is not a fault`() = runBlocking<Unit> {
        val health = PreferenceStorageHealth()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            flow {
                emit(1)
                started.complete(Unit)
                awaitCancellation()
            }.recoveringPreferenceReads("test", health, noDelay).collect()
        }
        started.await()

        job.cancelAndJoin()

        assertTrue(health.state.value.healthy)
    }

    @Test
    fun `a failed write in the scope is reported instead of reaching the uncaught handler`() = runBlocking<Unit> {
        val health = PreferenceStorageHealth()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + preferenceWriteFailureHandler("test", health))
        val uncaught = AtomicInteger()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught.incrementAndGet() }
        try {
            scope.launch { throw IOException("read-only volume") }.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals(SaveFault("IOException"), health.state.value.save)
        assertEquals(0, uncaught.get(), "the scope's handler must consume the failure")
        assertTrue(scope.isActive, "a supervisor scope survives one failed write")
    }

    @Test
    fun `a serial consumer survives a failed write`() = runBlocking<Unit> {
        val health = PreferenceStorageHealth()
        val ran = mutableListOf<Int>()
        val writes = listOf<suspend () -> Unit>(
            { ran += 1 },
            {
                ran += 2
                throw IOException("disk full")
            },
            { ran += 3 },
        )

        for (write in writes) runPreferenceWrite("test", health) { write() }

        assertEquals(listOf(1, 2, 3), ran, "the write after the failure still runs")
        assertEquals(SaveFault("IOException"), health.state.value.save)
    }
}
