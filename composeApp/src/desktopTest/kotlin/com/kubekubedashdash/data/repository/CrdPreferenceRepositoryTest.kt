package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrdPreferenceRepositoryTest {

    private val repo = CrdPreferenceRepository

    @Test
    fun `pinning a fresh key adds it`() {
        val (pinned, hidden) = repo.computeTogglePinned(emptyMap(), emptyMap(), "ctxA", "g/Spark")
        assertEquals(setOf("g/Spark"), pinned["ctxA"])
        assertTrue(hidden.isEmpty())
    }

    @Test
    fun `pinning twice unpins`() {
        val (firstPinned, _) = repo.computeTogglePinned(emptyMap(), emptyMap(), "ctxA", "g/Spark")
        val (secondPinned, _) = repo.computeTogglePinned(firstPinned, emptyMap(), "ctxA", "g/Spark")
        assertFalse("ctxA" in secondPinned)
    }

    @Test
    fun `pinning a hidden key unhides it`() {
        val pinned = emptyMap<String, Set<String>>()
        val hidden = mapOf("ctxA" to setOf("g/Spark"))
        val (newPinned, newHidden) = repo.computeTogglePinned(pinned, hidden, "ctxA", "g/Spark")
        assertEquals(setOf("g/Spark"), newPinned["ctxA"])
        assertFalse("ctxA" in newHidden, "hidden ctxA entry should disappear when its only key was unhidden")
    }

    @Test
    fun `hiding a fresh key adds it`() {
        val (pinned, hidden) = repo.computeToggleHidden(emptyMap(), emptyMap(), "ctxA", "g/Spark")
        assertEquals(setOf("g/Spark"), hidden["ctxA"])
        assertTrue(pinned.isEmpty())
    }

    @Test
    fun `hiding twice unhides`() {
        val (_, firstHidden) = repo.computeToggleHidden(emptyMap(), emptyMap(), "ctxA", "g/Spark")
        val (_, secondHidden) = repo.computeToggleHidden(emptyMap(), firstHidden, "ctxA", "g/Spark")
        assertFalse("ctxA" in secondHidden)
    }

    @Test
    fun `hiding a pinned key unpins it`() {
        val pinned = mapOf("ctxA" to setOf("g/Spark"))
        val hidden = emptyMap<String, Set<String>>()
        val (newPinned, newHidden) = repo.computeToggleHidden(pinned, hidden, "ctxA", "g/Spark")
        assertEquals(setOf("g/Spark"), newHidden["ctxA"])
        assertFalse("ctxA" in newPinned)
    }

    @Test
    fun `pin and hide are isolated across contexts`() {
        var pinned = emptyMap<String, Set<String>>()
        var hidden = emptyMap<String, Set<String>>()

        repo.computeTogglePinned(pinned, hidden, "prod", "g/Spark").also { (p, h) ->
            pinned = p
            hidden = h
        }
        repo.computeToggleHidden(pinned, hidden, "stage", "g/Spark").also { (p, h) ->
            pinned = p
            hidden = h
        }

        assertEquals(setOf("g/Spark"), pinned["prod"])
        assertEquals(setOf("g/Spark"), hidden["stage"])
        assertFalse("stage" in pinned)
        assertFalse("prod" in hidden)
    }

    @Test
    fun `multiple keys in one context coexist`() {
        var pinned = emptyMap<String, Set<String>>()
        repo.computeTogglePinned(pinned, emptyMap(), "ctxA", "g/Spark").also { pinned = it.first }
        repo.computeTogglePinned(pinned, emptyMap(), "ctxA", "g/Workflow").also { pinned = it.first }
        assertEquals(setOf("g/Spark", "g/Workflow"), pinned["ctxA"])
    }

    @Test
    fun `unpinning the last key removes the context entry`() {
        val pinned = mapOf("ctxA" to setOf("g/Spark"))
        val (afterUnpin, _) = repo.computeTogglePinned(pinned, emptyMap(), "ctxA", "g/Spark")
        assertFalse("ctxA" in afterUnpin, "context entry should be dropped, not left as empty set")
    }

    @Test
    fun `decode round-trips through encode`() {
        val original = mapOf(
            "prod" to setOf("g/Spark", "g/Workflow"),
            "stage" to setOf("g/Certificate"),
        )
        // Round-trip via the same JSON shape that the prefs file uses.
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, List<String>>>(),
            original.mapValues { it.value.toList() },
        )
        val decoded = repo.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `decode tolerates blank input`() {
        assertTrue(repo.decode(null).isEmpty())
        assertTrue(repo.decode("").isEmpty())
        assertTrue(repo.decode("   ").isEmpty())
    }

    @Test
    fun `decode returns empty on malformed json`() {
        assertTrue(repo.decode("{not-json").isEmpty())
    }
}
