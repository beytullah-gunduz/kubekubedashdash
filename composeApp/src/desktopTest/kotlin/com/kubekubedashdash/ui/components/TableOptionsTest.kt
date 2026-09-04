package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the pure table-controls model in TableOptions.kt:
 * [TableDensity.fromKey]'s key round-trip, and [sortTableRows]'
 * pin-first / header-based / identity-tiebreak ordering (D5-D8). The menu,
 * persistence and column-hiding pieces this file also declares
 * ([tableColumnKey], [visibleColumnIndices], [isLastVisibleColumn]) are
 * exercised by WS2, which owns the call sites that need them.
 */
class TableOptionsTest {

    private val nameStatus = listOf(ColumnDef(header = "Name"), ColumnDef(header = "Status"))

    private fun row(id: String, name: String, status: String, pinId: String? = null) = TableRow(
        id = id,
        cells = listOf(CellData(text = name), CellData(text = status)),
        pinId = pinId,
    )

    // ── TableDensity ─────────────────────────────────────────────────────────

    @Test
    fun `fromKey resolves both known keys`() {
        assertEquals(TableDensity.Comfortable, TableDensity.fromKey("comfortable"))
        assertEquals(TableDensity.Compact, TableDensity.fromKey("compact"))
    }

    @Test
    fun `fromKey falls back to Comfortable for an unknown key or null`() {
        assertEquals(TableDensity.Comfortable, TableDensity.fromKey("spacious"))
        assertEquals(TableDensity.Comfortable, TableDensity.fromKey(null))
    }

    // ── sortTableRows: primary key ──────────────────────────────────────────

    @Test
    fun `sorts ascending by the header's sort value`() {
        val rows = listOf(
            row("1", "pod-b", "Running"),
            row("2", "pod-a", "Pending"),
            row("3", "pod-c", "Failed"),
        )
        val sorted = sortTableRows(rows, nameStatus, "Status", ascending = true, identityColumn = 0, pinnedIds = emptySet())
        assertEquals(listOf("Failed", "Pending", "Running"), sorted.map { it.cells[1].text })
    }

    @Test
    fun `descending is the exact reverse of ascending`() {
        val rows = listOf(
            row("1", "pod-b", "Running"),
            row("2", "pod-a", "Pending"),
            row("3", "pod-c", "Failed"),
        )
        val ascending = sortTableRows(rows, nameStatus, "Status", ascending = true, identityColumn = 0, pinnedIds = emptySet())
        val descending = sortTableRows(rows, nameStatus, "Status", ascending = false, identityColumn = 0, pinnedIds = emptySet())
        assertEquals(ascending.reversed().map { it.id }, descending.map { it.id })
    }

    // ── sortTableRows: identity tiebreak (D7) ───────────────────────────────

    @Test
    fun `ties on the primary column break by the identity column ascending`() {
        val rows = listOf(
            row("1", "pod-b", "Running"),
            row("2", "pod-a", "Running"),
            row("3", "pod-c", "Running"),
        )
        val sorted = sortTableRows(rows, nameStatus, "Status", ascending = true, identityColumn = 0, pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b", "pod-c"), sorted.map { it.cells[0].text })
    }

    /**
     * Regression guard for the exact wording of D7: descending must be
     * `.reversed()` of the whole ascending result, NOT a fresh
     * `compareByDescending(primary).thenBy(identity)` sort. With two primary
     * groups ("Failed" tied x2, "Running" x1) those two implementations
     * disagree on the tied group's internal order — reversed() flips it to
     * (b, a); compareByDescending+thenBy would keep it ascending (a, b).
     */
    @Test
    fun `descending reverses the whole ascending list rather than re-sorting ties`() {
        val rows = listOf(
            row("failed-b", "pod-b", "Failed"),
            row("failed-a", "pod-a", "Failed"),
            row("running-c", "pod-c", "Running"),
        )
        val ascending = sortTableRows(rows, nameStatus, "Status", ascending = true, identityColumn = 0, pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b", "pod-c"), ascending.map { it.cells[0].text })

        val descending = sortTableRows(rows, nameStatus, "Status", ascending = false, identityColumn = 0, pinnedIds = emptySet())
        assertEquals(listOf("pod-c", "pod-b", "pod-a"), descending.map { it.cells[0].text })
    }

    // ── sortTableRows: fallback to identity ─────────────────────────────────

    @Test
    fun `an unresolvable sortHeader falls back to identity order`() {
        val rows = listOf(
            row("1", "pod-c", "Running"),
            row("2", "pod-a", "Pending"),
            row("3", "pod-b", "Failed"),
        )
        val sorted = sortTableRows(rows, nameStatus, "Nonexistent Header", ascending = true, identityColumn = 0, pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b", "pod-c"), sorted.map { it.cells[0].text })
    }

    @Test
    fun `a null sortHeader still puts pinned rows first`() {
        val rows = listOf(
            row("1", "pod-a", "Running"),
            row("2", "pod-b", "Pending", pinId = "pin:pod-b"),
            row("3", "pod-c", "Failed"),
        )
        val sorted = sortTableRows(rows, nameStatus, sortHeader = null, ascending = true, identityColumn = 0, pinnedIds = setOf("pin:pod-b"))
        assertEquals("2", sorted.first().id)
        // Everything else still falls back to identity order.
        assertEquals(listOf("pod-b", "pod-a", "pod-c"), sorted.map { it.cells[0].text })
    }

    // ── sortTableRows: pin-first (D8) ───────────────────────────────────────

    @Test
    fun `pinned rows lead in both directions under an explicit sort column`() {
        val rows = listOf(
            row("1", "pod-a", "Running"),
            row("2", "pod-b", "Pending"),
            row("3", "pod-c", "Failed", pinId = "pin:pod-c"),
        )
        val pinned = setOf("pin:pod-c")
        val ascending = sortTableRows(rows, nameStatus, "Status", ascending = true, identityColumn = 0, pinnedIds = pinned)
        assertEquals("3", ascending.first().id)
        val descending = sortTableRows(rows, nameStatus, "Status", ascending = false, identityColumn = 0, pinnedIds = pinned)
        assertEquals("3", descending.first().id)
    }

    // ── sortTableRows: identityColumn as a parameter (D6) ───────────────────

    private val typeAgeObject = listOf(
        ColumnDef(header = "Type"),
        ColumnDef(header = "Age"),
        ColumnDef(header = "Object"),
    )

    private fun eventRow(id: String, type: String, age: String, obj: String) = TableRow(id = id, cells = listOf(CellData(text = type), CellData(text = age), CellData(text = obj)))

    @Test
    fun `identityColumn 2 breaks ties instead of column 0`() {
        val rows = listOf(
            eventRow("1", "Warning", "5m", "pod-b"),
            eventRow("2", "Warning", "5m", "pod-a"),
        )
        val sorted = sortTableRows(rows, typeAgeObject, "Age", ascending = true, identityColumn = 2, pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b"), sorted.map { it.cells[2].text })
    }

    @Test
    fun `empty input returns empty output`() {
        assertEquals(
            emptyList(),
            sortTableRows(emptyList(), nameStatus, "Status", ascending = true, identityColumn = 0, pinnedIds = emptySet()),
        )
    }
}
