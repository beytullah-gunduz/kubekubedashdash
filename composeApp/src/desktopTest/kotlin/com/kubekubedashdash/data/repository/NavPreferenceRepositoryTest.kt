package com.kubekubedashdash.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises the pure top-level helpers only — the repository object backing
 * them is never named here. Touching a Kotlin `object` runs its `init`,
 * which for this one would open the real DataStore; see the module's global
 * test constraints.
 */
class NavPreferenceRepositoryTest {

    // ── computeToggleFavourite ───────────────────────────────────────────

    @Test
    fun `toggling a fresh key adds it, toggling again removes it`() {
        val added = computeToggleFavourite(emptyMap(), "ctxA", "Pods")
        assertEquals(listOf("Pods"), added["ctxA"])

        val removed = computeToggleFavourite(added, "ctxA", "Pods")
        assertFalse("ctxA" in removed, "context entry should be dropped, not left as an empty list")
    }

    @Test
    fun `favourites are isolated across contexts`() {
        val afterProd = computeToggleFavourite(emptyMap(), "prod", "Pods")
        val afterStage = computeToggleFavourite(afterProd, "stage", "Nodes")
        assertEquals(listOf("Pods"), afterStage["prod"])
        assertEquals(listOf("Nodes"), afterStage["stage"])
    }

    @Test
    fun `unfavouriting the last key removes the context entry`() {
        val withOne = mapOf("ctxA" to listOf("Pods"))
        val after = computeToggleFavourite(withOne, "ctxA", "Pods")
        assertFalse("ctxA" in after)
    }

    @Test
    fun `favouriting A, B, C yields them in that order`() {
        var map = computeToggleFavourite(emptyMap(), "ctxA", "A")
        map = computeToggleFavourite(map, "ctxA", "B")
        map = computeToggleFavourite(map, "ctxA", "C")
        assertEquals(listOf("A", "B", "C"), map["ctxA"])
    }

    @Test
    fun `unfavouriting a middle key preserves the order of the rest`() {
        var map = computeToggleFavourite(emptyMap(), "ctxA", "A")
        map = computeToggleFavourite(map, "ctxA", "B")
        map = computeToggleFavourite(map, "ctxA", "C")
        map = computeToggleFavourite(map, "ctxA", "B")
        assertEquals(listOf("A", "C"), map["ctxA"])
    }

    // ── computeRecordRecent ──────────────────────────────────────────────

    @Test
    fun `recording a fresh key adds it as the only entry`() {
        val map = computeRecordRecent(emptyMap(), "ctxA", "Pods")
        assertEquals(listOf("Pods"), map["ctxA"])
    }

    @Test
    fun `recording an existing key moves it to the front and dedupes`() {
        val map = mapOf("ctxA" to listOf("Nodes", "Pods", "Events"))
        val after = computeRecordRecent(map, "ctxA", "Pods")
        assertEquals(listOf("Pods", "Nodes", "Events"), after["ctxA"])
    }

    @Test
    fun `recording caps at the configured limit, dropping the oldest`() {
        val map = mapOf("ctxA" to listOf("E", "D", "C", "B", "A"))
        val after = computeRecordRecent(map, "ctxA", "F", cap = 5)
        assertEquals(listOf("F", "E", "D", "C", "B"), after["ctxA"])
    }

    @Test
    fun `recording is a no-op when the key is already first`() {
        val map = mapOf("ctxA" to listOf("Pods", "Nodes"))
        val after = computeRecordRecent(map, "ctxA", "Pods")
        assertSame(map, after, "should return the same map instance unchanged")
    }

    @Test
    fun `recording with a zero cap drops the context entirely`() {
        val map = mapOf("ctxA" to listOf("Pods", "Nodes"))
        val after = computeRecordRecent(map, "ctxA", "Events", cap = 0)
        assertFalse("ctxA" in after, "a zero cap empties the list, which must drop the context, not leave it as []")
    }

    // ── decodeContextLists / encodeContextLists ──────────────────────────

    @Test
    fun `decode tolerates blank input`() {
        assertTrue(decodeContextLists(null).isEmpty())
        assertTrue(decodeContextLists("").isEmpty())
        assertTrue(decodeContextLists("   ").isEmpty())
    }

    @Test
    fun `decode returns empty on malformed json`() {
        assertTrue(decodeContextLists("{not-json").isEmpty())
    }

    @Test
    fun `encode then decode round-trips`() {
        val original = mapOf(
            "prod" to listOf("Pods", "example.com/SparkApplication"),
            "stage" to listOf("Nodes"),
        )
        val decoded = decodeContextLists(encodeContextLists(original))
        assertEquals(original, decoded)
    }

    // ── favourites and Recent slots ──────────────────────────────────────

    @Test
    fun `recording a favourite is a no-op so it cannot burn a slot`() {
        val before = mapOf("ctx" to listOf("Nodes"))
        val after = computeRecordRecent(before, "ctx", "Pods", favourites = listOf("Pods"))
        assertSame(before, after)
    }

    @Test
    fun `removing a recent drops it and keeps the rest in order`() {
        val before = mapOf("ctx" to listOf("A", "B", "C"))
        assertEquals(mapOf("ctx" to listOf("A", "C")), computeRemoveRecent(before, "ctx", "B"))
    }

    @Test
    fun `removing the last recent drops the context entry`() {
        assertEquals(emptyMap<String, List<String>>(), computeRemoveRecent(mapOf("ctx" to listOf("A")), "ctx", "A"))
    }

    @Test
    fun `removing an absent recent is a no-op`() {
        val before = mapOf("ctx" to listOf("A"))
        assertSame(before, computeRemoveRecent(before, "ctx", "Z"))
    }
}
