package com.kubekubedashdash.util

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure stale-entry helpers behind the Pods/Nodes "keep a
 * just-deleted row briefly, then drop it" behavior. The clock is injected, so
 * the 20s TTL window is testable without a real flow or `Instant.now()`.
 */
class StaleEntriesTest {

    private val t0: Instant = Instant.parse("2026-06-03T00:00:00Z")
    private val ttl: Duration = Duration.ofSeconds(20)

    @Test
    fun `newly vanished uid is added and stamped now`() {
        val previous = mapOf("a" to "podA", "b" to "podB")
        val current = mapOf("a" to "podA") // b vanished
        val result = reduceStale(emptyMap(), current, previous, t0, ttl)
        assertEquals(setOf("b"), result.keys)
        assertEquals("podB", result.getValue("b").info)
        assertEquals(t0, result.getValue("b").staleSince)
    }

    @Test
    fun `reappeared uid is dropped from stale`() {
        val stale = mapOf("b" to StaleEntry("podB", t0))
        val current = mapOf("a" to "podA", "b" to "podB") // b is back
        val result = reduceStale(stale, current, current, t0.plusSeconds(5), ttl)
        assertFalse("b" in result)
    }

    @Test
    fun `existing stale entry keeps its original staleSince`() {
        val stale = mapOf("b" to StaleEntry("podB", t0))
        val current = mapOf("a" to "podA") // b still gone
        val result = reduceStale(stale, current, current, t0.plusSeconds(5), ttl)
        // clock not reset on every snapshot — TTL keeps counting from t0
        assertEquals(t0, result.getValue("b").staleSince)
    }

    @Test
    fun `entry past ttl is evicted by reduceStale`() {
        val stale = mapOf("b" to StaleEntry("podB", t0))
        val current = mapOf("a" to "podA")
        val result = reduceStale(stale, current, current, t0.plusSeconds(21), ttl)
        assertFalse("b" in result)
    }

    @Test
    fun `entry within ttl is retained by reduceStale`() {
        val stale = mapOf("b" to StaleEntry("podB", t0))
        val current = mapOf("a" to "podA")
        val result = reduceStale(stale, current, current, t0.plusSeconds(19), ttl)
        assertTrue("b" in result)
    }

    @Test
    fun `pruneExpiredStale drops only expired entries`() {
        val stale = mapOf(
            "fresh" to StaleEntry("p1", t0.plusSeconds(15)),
            "old" to StaleEntry("p2", t0),
        )
        val result = pruneExpiredStale(stale, t0.plusSeconds(21), ttl)
        assertEquals(setOf("fresh"), result.keys)
    }

    @Test
    fun `eviction boundary at exactly ttl evicts`() {
        val stale = mapOf("b" to StaleEntry("podB", t0))
        // age == ttl → cutoff == staleSince → not after → evicted
        val result = pruneExpiredStale(stale, t0.plus(ttl), ttl)
        assertFalse("b" in result)
    }
}
