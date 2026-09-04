package com.kubekubedashdash.ui

import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.search_filled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the command palette's pure query-parsing, prefix-scoping, recents-resolution and row-layout logic. */
class PaletteLogicTest {

    private fun entry(id: String, category: String, label: String = id) = PaletteEntry(
        id = id,
        label = label,
        category = category,
        icon = Res.drawable.search_filled,
        onActivate = {},
    )

    // ── parsePaletteQuery ────────────────────────────────────────────────

    @Test
    fun `no prefix keeps the query as typed — spacing included`() {
        assertEquals(PaletteQuery(null, "kube-proxy"), parsePaletteQuery("kube-proxy"))
        // The text is what the search field renders, so it must survive
        // verbatim: trimming here would eat a space as it was typed, and a
        // trailing space is the only route to an interior one. Matching trims.
        assertEquals(PaletteQuery(null, "  kube-proxy  "), parsePaletteQuery("  kube-proxy  "))
        assertEquals(PaletteQuery(null, "network "), parsePaletteQuery("network "))
        assertEquals(PaletteQuery(null, "network policies"), parsePaletteQuery("network policies"))
    }

    @Test
    fun `blank and empty queries parse to no prefix, keeping whitespace as typed`() {
        assertEquals(PaletteQuery(null, ""), parsePaletteQuery(""))
        assertEquals(PaletteQuery(null, "   "), parsePaletteQuery("   "))
    }

    @Test
    fun `greater-than alone parses to the actions-verbs prefix with empty text`() {
        assertEquals(PaletteQuery(">", ""), parsePaletteQuery(">"))
    }

    @Test
    fun `greater-than with text parses the prefix and keeps the remainder as typed`() {
        assertEquals(PaletteQuery(">", "restart"), parsePaletteQuery(">restart"))
        assertEquals(PaletteQuery(">", " restart"), parsePaletteQuery("> restart"))
    }

    /**
     * The search field is driven by `prefix + text`, so the parse must be
     * lossless or a keystroke disappears the moment it is typed. This is the
     * exact round trip `SearchBar`'s `onTextChange` performs.
     */
    @Test
    fun `prefix and text recompose the raw query for anything the user can type`() {
        listOf("", " ", "n", "network ", "network policies", ">", "> ", ">rest art", "pod:my pod", "abc:def ghi")
            .forEach { raw ->
                val parsed = parsePaletteQuery(raw)
                assertEquals(raw, (parsed.prefix ?: "") + parsed.text, "round trip failed for \"$raw\"")
            }
    }

    @Test
    fun `every known word prefix in D4 is recognised`() {
        assertEquals(PaletteQuery("pod:", "abc"), parsePaletteQuery("pod:abc"))
        assertEquals(PaletteQuery("node:", "abc"), parsePaletteQuery("node:abc"))
        assertEquals(PaletteQuery("ns:", "abc"), parsePaletteQuery("ns:abc"))
        assertEquals(PaletteQuery("dep:", "abc"), parsePaletteQuery("dep:abc"))
        assertEquals(PaletteQuery("crd:", "abc"), parsePaletteQuery("crd:abc"))
        assertEquals(PaletteQuery("go:", "abc"), parsePaletteQuery("go:abc"))
    }

    @Test
    fun `a known word prefix is recognised case-insensitively and normalised to lowercase`() {
        assertEquals(PaletteQuery("pod:", "abc"), parsePaletteQuery("POD:abc"))
    }

    @Test
    fun `an unrecognised word colon is not a prefix and stays in the fuzzy text`() {
        assertEquals(PaletteQuery(null, "abc:def"), parsePaletteQuery("abc:def"))
    }

    @Test
    fun `a colon with no leading word is not a prefix`() {
        assertEquals(PaletteQuery(null, ":def"), parsePaletteQuery(":def"))
    }

    // ── categoriesForPrefix ──────────────────────────────────────────────

    @Test
    fun `no prefix means no category restriction`() {
        assertEquals(null, categoriesForPrefix(null))
    }

    @Test
    fun `every D4 prefix maps to its documented category scope`() {
        assertEquals(setOf("Actions", "Verbs"), categoriesForPrefix(">"))
        assertEquals(setOf("Pods"), categoriesForPrefix("pod:"))
        assertEquals(setOf("Nodes"), categoriesForPrefix("node:"))
        assertEquals(setOf("Namespaces"), categoriesForPrefix("ns:"))
        assertEquals(setOf("Deployments"), categoriesForPrefix("dep:"))
        assertEquals(setOf("Custom Resources"), categoriesForPrefix("crd:"))
        assertEquals(
            setOf(
                "Cluster",
                "Workloads",
                "Config",
                "Network",
                "Storage",
                "Access Control",
                "Autoscaling & Disruption",
                "Governance",
                "Admission Control",
            ),
            categoriesForPrefix("go:"),
        )
    }

    // ── recentEntries ────────────────────────────────────────────────────

    @Test
    fun `recentEntries resolves ids in most-recent-first order`() {
        val entries = listOf(entry("a", "Pods"), entry("b", "Nodes"), entry("c", "Namespaces"))
        val result = recentEntries(entries, listOf("c", "a"))
        assertEquals(listOf("c", "a"), result.map { it.id })
    }

    @Test
    fun `recentEntries dedupes repeated ids`() {
        val entries = listOf(entry("a", "Pods"))
        val result = recentEntries(entries, listOf("a", "a", "a"))
        assertEquals(1, result.size)
    }

    @Test
    fun `recentEntries skips unresolvable ids without failing`() {
        val entries = listOf(entry("a", "Pods"))
        val result = recentEntries(entries, listOf("a", "gone"))
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `recentEntries overrides every returned entry's category to Recent`() {
        val entries = listOf(entry("a", "Pods"), entry("b", "Nodes"))
        val result = recentEntries(entries, listOf("a", "b"))
        assertTrue(result.all { it.category == "Recent" })
    }

    // ── paletteRows ──────────────────────────────────────────────────────

    @Test
    fun `paletteRows is empty for an empty entry list`() {
        assertEquals(emptyList(), paletteRows(emptyList()))
    }

    @Test
    fun `paletteRows emits one header per category group in order, with matching entryIndex`() {
        val entries = listOf(
            entry("a", "Pods"),
            entry("b", "Pods"),
            entry("c", "Nodes"),
        )
        val rows = paletteRows(entries)
        assertEquals(
            listOf(
                PaletteRow.Header("Pods"),
                PaletteRow.Entry(entries[0], 0),
                PaletteRow.Entry(entries[1], 1),
                PaletteRow.Header("Nodes"),
                PaletteRow.Entry(entries[2], 2),
            ),
            rows,
        )
    }

    // ── rowIndexOfEntry ──────────────────────────────────────────────────

    @Test
    fun `rowIndexOfEntry finds the first and last entry of a multi-group list`() {
        val entries = listOf(
            entry("a", "Pods"),
            entry("b", "Pods"),
            entry("c", "Nodes"),
        )
        val rows = paletteRows(entries)
        assertEquals(1, rowIndexOfEntry(rows, 0))
        assertEquals(4, rowIndexOfEntry(rows, 2))
    }
}
