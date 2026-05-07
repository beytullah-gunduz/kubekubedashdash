package com.kubekubedashdash.services

import com.kubekubedashdash.model.SessionId
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogStreamRegistryTest {

    @AfterTest
    fun cleanup() {
        LogStreamRegistry.clearAll()
    }

    @Test
    fun `same params returns same id and reuses stream`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)

        val returned1 = LogStreamRegistry.openOrFocusStream(id, "pod1") { emptyFlow() }
        assertEquals(id, returned1)
        assertEquals(1, LogStreamRegistry.streams.value.size)

        // Second call with same params — must return the same id without adding a new entry
        val returned2 = LogStreamRegistry.openOrFocusStream(id, "pod1") { emptyFlow() }
        assertEquals(id, returned2)
        assertEquals(1, LogStreamRegistry.streams.value.size)
    }

    @Test
    fun `close removes stream`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        LogStreamRegistry.openOrFocusStream(id, "pod1") { emptyFlow() }
        assertTrue(id.key in LogStreamRegistry.streams.value)

        LogStreamRegistry.close(id)
        assertFalse(id.key in LogStreamRegistry.streams.value)
        assertTrue(LogStreamRegistry.streams.value.isEmpty())
    }

    @Test
    fun `opening a new stream focuses it`() = runBlocking {
        val id1 = LogStreamId("session1", "pod1", "default", null)
        val id2 = LogStreamId("session1", "pod2", "default", null)

        LogStreamRegistry.openOrFocusStream(id1, "pod1") { emptyFlow() }
        assertEquals(id1.key, LogStreamRegistry.focusedKey.value)

        // Opening a second stream — focus must follow the newcomer so the
        // user immediately sees the pod they just clicked.
        LogStreamRegistry.openOrFocusStream(id2, "pod2") { emptyFlow() }
        assertEquals(id2.key, LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `re-opening an existing stream re-focuses it`() = runBlocking {
        val id1 = LogStreamId("session1", "pod1", "default", null)
        val id2 = LogStreamId("session1", "pod2", "default", null)

        LogStreamRegistry.openOrFocusStream(id1, "pod1") { emptyFlow() }
        LogStreamRegistry.openOrFocusStream(id2, "pod2") { emptyFlow() }
        assertEquals(id2.key, LogStreamRegistry.focusedKey.value)

        // Re-requesting an already-open stream should switch focus back to it
        // without creating a duplicate tab.
        LogStreamRegistry.openOrFocusStream(id1, "pod1") { emptyFlow() }
        assertEquals(id1.key, LogStreamRegistry.focusedKey.value)
        assertEquals(2, LogStreamRegistry.streams.value.size)
    }

    @Test
    fun `closing the focused stream clears focus`() = runBlocking {
        val id = LogStreamId("session1", "pod1", "default", null)
        LogStreamRegistry.openOrFocusStream(id, "pod1") { emptyFlow() }
        assertEquals(id.key, LogStreamRegistry.focusedKey.value)

        LogStreamRegistry.close(id)
        assertNull(LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `closing a non-focused stream leaves focus alone`() = runBlocking {
        val id1 = LogStreamId("session1", "pod1", "default", null)
        val id2 = LogStreamId("session1", "pod2", "default", null)
        LogStreamRegistry.openOrFocusStream(id1, "pod1") { emptyFlow() }
        LogStreamRegistry.openOrFocusStream(id2, "pod2") { emptyFlow() }
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

        LogStreamRegistry.openOrFocusStream(idA1, "pod1") { emptyFlow() }
        LogStreamRegistry.openOrFocusStream(idA2, "pod2") { emptyFlow() }
        LogStreamRegistry.openOrFocusStream(idB1, "pod1") { emptyFlow() }
        assertEquals(3, LogStreamRegistry.streams.value.size)

        LogStreamRegistry.closeAllForSession(sessionA)

        val remaining = LogStreamRegistry.streams.value
        assertEquals(1, remaining.size)
        assertTrue(idB1.key in remaining)
        assertFalse(idA1.key in remaining)
        assertFalse(idA2.key in remaining)
    }
}
