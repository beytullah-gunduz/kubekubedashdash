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
 * the TTL windows are testable without a real flow or `Instant.now()`.
 */
class StaleEntriesTest {

    private val t0: Instant = Instant.parse("2026-06-03T00:00:00Z")
    private val ttl: Duration = Duration.ofSeconds(20)

    @Test
    fun `newly vanished uid is added and stamped now with its ttl`() {
        val previous = mapOf("a" to "podA", "b" to "podB")
        val current = mapOf("a" to "podA") // b vanished
        val result = reduceStale(emptyMap(), current, previous, t0) { ttl }
        assertEquals(setOf("b"), result.keys)
        assertEquals("podB", result.getValue("b").info)
        assertEquals(t0, result.getValue("b").staleSince)
        assertEquals(ttl, result.getValue("b").ttl)
    }

    @Test
    fun `reappeared uid is dropped from stale`() {
        val stale = mapOf("b" to StaleEntry("podB", t0, ttl))
        val current = mapOf("a" to "podA", "b" to "podB") // b is back
        val result = reduceStale(stale, current, current, t0.plusSeconds(5)) { ttl }
        assertFalse("b" in result)
    }

    @Test
    fun `existing stale entry keeps its original staleSince and ttl`() {
        val stale = mapOf("b" to StaleEntry("podB", t0, ttl))
        val current = mapOf("a" to "podA") // b still gone
        // ttlFor here would return a different value; the carried entry must ignore it.
        val result = reduceStale(stale, current, current, t0.plusSeconds(5)) { Duration.ofSeconds(99) }
        assertEquals(t0, result.getValue("b").staleSince)
        assertEquals(ttl, result.getValue("b").ttl)
    }

    @Test
    fun `entry past ttl is evicted by reduceStale`() {
        val stale = mapOf("b" to StaleEntry("podB", t0, ttl))
        val current = mapOf("a" to "podA")
        val result = reduceStale(stale, current, current, t0.plusSeconds(21)) { ttl }
        assertFalse("b" in result)
    }

    @Test
    fun `entry within ttl is retained by reduceStale`() {
        val stale = mapOf("b" to StaleEntry("podB", t0, ttl))
        val current = mapOf("a" to "podA")
        val result = reduceStale(stale, current, current, t0.plusSeconds(19)) { ttl }
        assertTrue("b" in result)
    }

    @Test
    fun `pruneExpiredStale drops only expired entries`() {
        val stale = mapOf(
            "fresh" to StaleEntry("p1", t0.plusSeconds(15), ttl),
            "old" to StaleEntry("p2", t0, ttl),
        )
        val result = pruneExpiredStale(stale, t0.plusSeconds(21))
        assertEquals(setOf("fresh"), result.keys)
    }

    @Test
    fun `eviction boundary at exactly ttl evicts`() {
        val stale = mapOf("b" to StaleEntry("podB", t0, ttl))
        // age == ttl → staleSince+ttl == now → not after → evicted
        val result = pruneExpiredStale(stale, t0.plus(ttl))
        assertFalse("b" in result)
    }

    @Test
    fun `error entries get a longer ttl and outlive default ones`() {
        val previous = mapOf("ok" to "okPod", "err" to "errPod")
        val current = emptyMap<String, String>() // both vanished
        val ttlFor: (String) -> Duration = { info ->
            if (info.startsWith("err")) Duration.ofSeconds(60) else Duration.ofSeconds(20)
        }
        val stale = reduceStale(emptyMap(), current, previous, t0, ttlFor)
        assertEquals(Duration.ofSeconds(20), stale.getValue("ok").ttl)
        assertEquals(Duration.ofSeconds(60), stale.getValue("err").ttl)

        // At t0+30s the 20s entry is gone but the 60s error entry remains.
        val pruned = pruneExpiredStale(stale, t0.plusSeconds(30))
        assertEquals(setOf("err"), pruned.keys)
    }
}
