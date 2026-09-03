package com.kubekubedashdash.services

import com.kubekubedashdash.model.SessionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogStreamRegistryTest {

    @AfterTest
    fun cleanup() {
        LogStreamRegistry.clearAll()
    }

    @Test
    fun `same params returns same id and reuses stream`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)

        val returned1 = LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ -> emptyFlow() }
        assertEquals(id, returned1)
        assertEquals(1, LogStreamRegistry.tabs.value.size)

        // Second call with same params — must return the same id without adding a new entry
        val returned2 = LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ -> emptyFlow() }
        assertEquals(id, returned2)
        assertEquals(1, LogStreamRegistry.tabs.value.size)
    }

    @Test
    fun `close removes stream`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ -> emptyFlow() }
        assertTrue(id.key in LogStreamRegistry.tabs.value)

        LogStreamRegistry.close(id)
        assertFalse(id.key in LogStreamRegistry.tabs.value)
        assertTrue(LogStreamRegistry.tabs.value.isEmpty())
    }

    @Test
    fun `opening a new stream focuses it`() = runBlocking {
        val id1 = LogStreamId("session1", "pod1", "default", null)
        val id2 = LogStreamId("session1", "pod2", "default", null)

        LogStreamRegistry.openOrFocusStream(id1, "pod1") { _, _ -> emptyFlow() }
        assertEquals(id1.key, LogStreamRegistry.focusedKey.value)

        // Opening a second stream — focus must follow the newcomer so the
        // user immediately sees the pod they just clicked.
        LogStreamRegistry.openOrFocusStream(id2, "pod2") { _, _ -> emptyFlow() }
        assertEquals(id2.key, LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `re-opening an existing stream re-focuses it`() = runBlocking {
        val id1 = LogStreamId("session1", "pod1", "default", null)
        val id2 = LogStreamId("session1", "pod2", "default", null)

        LogStreamRegistry.openOrFocusStream(id1, "pod1") { _, _ -> emptyFlow() }
        LogStreamRegistry.openOrFocusStream(id2, "pod2") { _, _ -> emptyFlow() }
        assertEquals(id2.key, LogStreamRegistry.focusedKey.value)

        // Re-requesting an already-open stream should switch focus back to it
        // without creating a duplicate tab.
        LogStreamRegistry.openOrFocusStream(id1, "pod1") { _, _ -> emptyFlow() }
        assertEquals(id1.key, LogStreamRegistry.focusedKey.value)
        assertEquals(2, LogStreamRegistry.tabs.value.size)
    }

    @Test
    fun `closing the focused stream clears focus`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ -> emptyFlow() }
        assertEquals(id.key, LogStreamRegistry.focusedKey.value)

        LogStreamRegistry.close(id)
        assertNull(LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `closing a non-focused stream leaves focus alone`() = runBlocking {
        val id1 = LogStreamId("session1", "pod1", "default", null)
        val id2 = LogStreamId("session1", "pod2", "default", null)
        LogStreamRegistry.openOrFocusStream(id1, "pod1") { _, _ -> emptyFlow() }
        LogStreamRegistry.openOrFocusStream(id2, "pod2") { _, _ -> emptyFlow() }
        // id2 is now focused.

        LogStreamRegistry.close(id1)
        assertEquals(id2.key, LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `closeAllForSession removes only that session streams`() = runBlocking {
        val sessionA = SessionId("sessionA")
        val sessionB = SessionId("sessionB")

        val idA1 = LogStreamId(sessionA.value, "pod1", "default", null)
        val idA2 = LogStreamId(sessionA.value, "pod2", "default", null)
        val idB1 = LogStreamId(sessionB.value, "pod1", "default", null)

        LogStreamRegistry.openOrFocusStream(idA1, "pod1") { _, _ -> emptyFlow() }
        LogStreamRegistry.openOrFocusStream(idA2, "pod2") { _, _ -> emptyFlow() }
        LogStreamRegistry.openOrFocusStream(idB1, "pod1") { _, _ -> emptyFlow() }
        assertEquals(3, LogStreamRegistry.tabs.value.size)

        LogStreamRegistry.closeAllForSession(sessionA)

        val remaining = LogStreamRegistry.tabs.value
        assertEquals(1, remaining.size)
        assertTrue(idB1.key in remaining)
        assertFalse(idA1.key in remaining)
        assertFalse(idA2.key in remaining)
    }

    @Test
    fun `lines past the cap are evicted and counted`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        val total = LogStreamRegistry.MAX_LINES + 10
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ -> (1..total).asFlow().map { "line $it" } }
        val stream = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream

        withTimeout(10_000) { stream.droppedLines.first { it == 10 } }

        assertEquals(LogStreamRegistry.MAX_LINES, stream.lines.value.size)
        assertEquals("line 11", stream.lines.value.first())
        assertEquals("line $total", stream.lines.value.last())
    }

    @Test
    fun `options default to all-off`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ -> emptyFlow() }

        val stream = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream
        assertEquals(LogStreamOptions(), stream.options)
    }

    @Test
    fun `setOptions keeps the same key, publishes the new options and swaps the emitted lines`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, options ->
            if (options.timestamps) flowOf("with-timestamps") else flowOf("bare")
        }
        val before = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream
        withTimeout(10_000) { before.lines.first { it == listOf("bare") } }

        val newOptions = LogStreamOptions(timestamps = true)
        LogStreamRegistry.setOptions(id.key, newOptions)

        assertEquals(1, LogStreamRegistry.tabs.value.size)
        val after = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream
        assertEquals(newOptions, after.options)
        val swapped = withTimeout(10_000) { after.lines.first { it == listOf("with-timestamps") } }
        assertEquals(listOf("with-timestamps"), swapped)
    }

    @Test
    fun `setOptions clears the buffer and dropped counter, and carries openedAt over`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        val total = LogStreamRegistry.MAX_LINES + 5
        val second = Channel<String>(Channel.UNLIMITED)
        var calls = 0
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ ->
            calls++
            if (calls == 1) (1..total).asFlow().map { "line $it" } else second.consumeAsFlow()
        }
        val before = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream
        withTimeout(10_000) { before.droppedLines.first { it == 5 } }
        assertEquals(LogStreamRegistry.MAX_LINES, before.lines.value.size)

        LogStreamRegistry.setOptions(id.key, LogStreamOptions(sinceSeconds = 300))
        val after = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream

        // openedAt is carried over — the tab must not jump in the strip.
        assertEquals(before.openedAt, after.openedAt)
        // Fresh flows, not the old ones reset in place — the identity check is
        // the load-bearing one: resetting in place would satisfy the value
        // assertions below while leaving the cancelled collector able to write
        // the previous options' lines back into the tab the user is reading.
        assertNotSame(before.lines, after.lines)
        assertNotSame(before.droppedLines, after.droppedLines)
        assertEquals(emptyList<String>(), after.lines.value)
        assertEquals(0, after.droppedLines.value)

        second.send("fresh line")
        val replayed = withTimeout(10_000) { after.lines.first { it == listOf("fresh line") } }
        second.close()
        assertEquals(listOf("fresh line"), replayed)
    }

    @Test
    fun `switchContainer closes the old key, opens pod dot container, and carries options and openedAt over`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", "sidecar")
        LogStreamRegistry.openOrFocusStream(id, "pod1 · sidecar") { _, _ -> emptyFlow() }
        val options = LogStreamOptions(timestamps = true)
        LogStreamRegistry.setOptions(id.key, options)
        val before = LogStreamRegistry.tabs.value.getValue(id.key) as ActiveLogStream

        LogStreamRegistry.switchContainer(id.key, "main")

        assertFalse(id.key in LogStreamRegistry.tabs.value)
        val newKey = LogStreamId("session1", "pod1", "default", "main").key
        assertEquals(1, LogStreamRegistry.tabs.value.size)
        val after = LogStreamRegistry.tabs.value.getValue(newKey) as ActiveLogStream
        assertEquals("pod1 · main", after.displayLabel)
        assertEquals(options, after.options)
        assertEquals(before.openedAt, after.openedAt)
        assertEquals(newKey, LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `switchContainer onto an already-open container focuses it instead of restarting it`() = runBlocking {
        val sidecar = LogStreamId("session1", "pod1", "default", "sidecar")
        val main = LogStreamId("session1", "pod1", "default", "main")
        var mainInvocations = 0
        LogStreamRegistry.openOrFocusStream(main, "pod1 · main") { _, _ ->
            mainInvocations++
            emptyFlow()
        }
        LogStreamRegistry.openOrFocusStream(sidecar, "pod1 · sidecar") { _, _ -> emptyFlow() }
        val before = LogStreamRegistry.tabs.value.getValue(main.key) as ActiveLogStream

        LogStreamRegistry.switchContainer(sidecar.key, "main")

        // The tab switched away from is gone, the existing one is focused, and
        // its collector was never restarted — a second launchCollector on the
        // same key would orphan the first job and leak its watchLog.
        assertFalse(sidecar.key in LogStreamRegistry.tabs.value)
        assertEquals(1, LogStreamRegistry.tabs.value.size)
        assertEquals(main.key, LogStreamRegistry.focusedKey.value)
        assertEquals(1, mainInvocations)
        assertSame(before, LogStreamRegistry.tabs.value.getValue(main.key))
    }

    @Test
    fun `close drops the factory so a second open invokes a fresh one`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        var firstInvocations = 0
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ ->
            firstInvocations++
            emptyFlow()
        }
        assertEquals(1, firstInvocations)

        LogStreamRegistry.close(id)

        var secondInvocations = 0
        LogStreamRegistry.openOrFocusStream(id, "pod1") { _, _ ->
            secondInvocations++
            emptyFlow()
        }
        assertEquals(1, secondInvocations)

        // setOptions must invoke the factory registered by the SECOND open,
        // not a stale reference retained from before close.
        LogStreamRegistry.setOptions(id.key, LogStreamOptions(sinceSeconds = 60))
        assertEquals(1, firstInvocations)
        assertEquals(2, secondInvocations)
    }
}
