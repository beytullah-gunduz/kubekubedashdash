package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure map-selector helpers in MapSelectorChip.kt:
 * [mapSelectorOptions]'s counting/ordering/noise-exclusion (D2),
 * [visibleMapSelectorOptions]'s search/order/cap (D4), and
 * [removeSelectorEntry]'s single-entry removal (D8) as distinct from
 * [toggleSelectorEntry]'s toggle-or-replace behaviour.
 */
class MapSelectorTest {

    // ── mapSelectorOptions ───────────────────────────────────────────────────

    @Test
    fun `counts a key=value pair across several resources`() {
        val entries = listOf(
            mapOf("app" to "web"),
            mapOf("app" to "web"),
            mapOf("app" to "api"),
        )
        val options = mapSelectorOptions(entries)
        assertEquals(
            listOf(
                MapSelectorOption("app", "api", 1),
                MapSelectorOption("app", "web", 2),
            ),
            options,
        )
    }

    @Test
    fun `one key with two values yields two options`() {
        val entries = listOf(
            mapOf("tier" to "backend"),
            mapOf("tier" to "frontend"),
        )
        val options = mapSelectorOptions(entries)
        assertEquals(2, options.size)
        assertEquals(setOf("backend", "frontend"), options.map { it.value }.toSet())
    }

    @Test
    fun `orders key-then-value regardless of input order`() {
        val entries = listOf(
            mapOf("tier" to "backend"),
            mapOf("app" to "web"),
            mapOf("app" to "api"),
        )
        val options = mapSelectorOptions(entries)
        assertEquals(
            listOf(
                MapSelectorOption("app", "api", 1),
                MapSelectorOption("app", "web", 1),
                MapSelectorOption("tier", "backend", 1),
            ),
            options,
        )
    }

    @Test
    fun `excludes NoisyAnnotationKeys such as last-applied-configuration`() {
        val entries = listOf(
            mapOf(
                "kubectl.kubernetes.io/last-applied-configuration" to "{...}",
                "owner" to "team-a",
            ),
        )
        val options = mapSelectorOptions(entries)
        assertEquals(listOf(MapSelectorOption("owner", "team-a", 1)), options)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList(), mapSelectorOptions(emptyList()))
    }

    // ── visibleMapSelectorOptions ────────────────────────────────────────────

    private val sampleOptions = listOf(
        MapSelectorOption("app", "web", count = 1),
        MapSelectorOption("app", "api", count = 5),
        MapSelectorOption("tier", "backend", count = 3),
    )

    @Test
    fun `search matches on the key, case-insensitively`() {
        val visible = visibleMapSelectorOptions(sampleOptions, "TIER")
        assertEquals(listOf(MapSelectorOption("tier", "backend", 3)), visible)
    }

    @Test
    fun `search matches on the value, case-insensitively`() {
        val visible = visibleMapSelectorOptions(sampleOptions, "WEB")
        assertEquals(listOf(MapSelectorOption("app", "web", 1)), visible)
    }

    @Test
    fun `blank search returns everything ordered by count descending`() {
        val visible = visibleMapSelectorOptions(sampleOptions, "")
        assertEquals(
            listOf(
                MapSelectorOption("app", "api", 5),
                MapSelectorOption("tier", "backend", 3),
                MapSelectorOption("app", "web", 1),
            ),
            visible,
        )
    }

    @Test
    fun `ties on count break by key then value`() {
        val tied = listOf(
            MapSelectorOption("tier", "backend", count = 2),
            MapSelectorOption("app", "web", count = 2),
            MapSelectorOption("app", "api", count = 2),
        )
        val visible = visibleMapSelectorOptions(tied, "")
        assertEquals(
            listOf(
                MapSelectorOption("app", "api", 2),
                MapSelectorOption("app", "web", 2),
                MapSelectorOption("tier", "backend", 2),
            ),
            visible,
        )
    }

    @Test
    fun `cap boundary is inclusive at exactly cap`() {
        val options = (1..3).map { MapSelectorOption("key$it", "value$it", count = it) }
        val visible = visibleMapSelectorOptions(options, "", cap = 3)
        assertEquals(3, visible.size)
    }

    @Test
    fun `cap boundary drops the excess at cap plus one`() {
        val options = (1..4).map { MapSelectorOption("key$it", "value$it", count = it) }
        val visible = visibleMapSelectorOptions(options, "", cap = 3)
        assertEquals(3, visible.size)
        // Highest counts survive the cap: 4, 3, 2 kept, 1 dropped.
        assertEquals(listOf(4, 3, 2), visible.map { it.count })
    }

    // ── removeSelectorEntry ──────────────────────────────────────────────────

    @Test
    fun `removes the named key and leaves the rest in original order`() {
        assertEquals("a=1, c=3", removeSelectorEntry("a=1, b=2, c=3", "b"))
    }

    @Test
    fun `no-ops for a key that is not present`() {
        assertEquals("a=1, b=2", removeSelectorEntry("a=1, b=2", "z"))
    }

    @Test
    fun `returns an empty string when it removed the only entry`() {
        assertEquals("", removeSelectorEntry("a=1", "a"))
    }

    @Test
    fun `keeps a value containing an equals sign intact`() {
        assertEquals("a=x=y", removeSelectorEntry("a=x=y, b=2", "b"))
    }

    @Test
    fun `toggleSelectorEntry replaces a differing value where removeSelectorEntry always removes`() {
        // Pinning the contrast the two helpers exist for: toggling a *different*
        // value for an existing key replaces it in place...
        assertEquals("a=2", toggleSelectorEntry("a=1", "a", "2"))
        // ...while removeSelectorEntry drops the key outright, regardless of value.
        assertEquals("", removeSelectorEntry("a=1", "a"))
    }
}
