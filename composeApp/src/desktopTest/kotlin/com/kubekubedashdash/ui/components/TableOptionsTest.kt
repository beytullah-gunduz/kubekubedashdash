package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for the pure table-controls model in TableOptions.kt:
 * [TableDensity.fromKey]'s key round-trip, [sortTableRows]'
 * pin-first / header-based / identity-tiebreak ordering (D5-D8), and the
 * column-picker helpers [tableColumnKey], [visibleColumnIndices] and
 * [isLastVisibleColumn] exercised by the ResourceTable options menu (WS2).
 */
class TableOptionsTest {

    private val nameStatus = listOf(ColumnDef(header = "Name"), ColumnDef(header = "Status"))
    private val nameStatusAge = listOf(ColumnDef(header = "Name"), ColumnDef(header = "Status"), ColumnDef(header = "Age"))

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
        val sorted = sortTableRows(rows, nameStatus, "Status", ascending = true, identityHeader = null, pinnedIds = emptySet())
        assertEquals(listOf("Failed", "Pending", "Running"), sorted.map { it.cells[1].text })
    }

    @Test
    fun `descending is the exact reverse of ascending`() {
        val rows = listOf(
            row("1", "pod-b", "Running"),
            row("2", "pod-a", "Pending"),
            row("3", "pod-c", "Failed"),
        )
        val ascending = sortTableRows(rows, nameStatus, "Status", ascending = true, identityHeader = null, pinnedIds = emptySet())
        val descending = sortTableRows(rows, nameStatus, "Status", ascending = false, identityHeader = null, pinnedIds = emptySet())
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
        val sorted = sortTableRows(rows, nameStatus, "Status", ascending = true, identityHeader = null, pinnedIds = emptySet())
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
        val ascending = sortTableRows(rows, nameStatus, "Status", ascending = true, identityHeader = null, pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b", "pod-c"), ascending.map { it.cells[0].text })

        val descending = sortTableRows(rows, nameStatus, "Status", ascending = false, identityHeader = null, pinnedIds = emptySet())
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
        val sorted = sortTableRows(rows, nameStatus, "Nonexistent Header", ascending = true, identityHeader = null, pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b", "pod-c"), sorted.map { it.cells[0].text })
    }

    @Test
    fun `a null sortHeader still puts pinned rows first`() {
        val rows = listOf(
            row("1", "pod-a", "Running"),
            row("2", "pod-b", "Pending", pinId = "pin:pod-b"),
            row("3", "pod-c", "Failed"),
        )
        val sorted = sortTableRows(rows, nameStatus, sortHeader = null, ascending = true, identityHeader = null, pinnedIds = setOf("pin:pod-b"))
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
        val ascending = sortTableRows(rows, nameStatus, "Status", ascending = true, identityHeader = null, pinnedIds = pinned)
        assertEquals("3", ascending.first().id)
        val descending = sortTableRows(rows, nameStatus, "Status", ascending = false, identityHeader = null, pinnedIds = pinned)
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
        val sorted = sortTableRows(rows, typeAgeObject, "Age", ascending = true, identityHeader = "Object", pinnedIds = emptySet())
        assertEquals(listOf("pod-a", "pod-b"), sorted.map { it.cells[2].text })
    }

    @Test
    fun `empty input returns empty output`() {
        assertEquals(
            emptyList(),
            sortTableRows(emptyList(), nameStatus, "Status", ascending = true, identityHeader = null, pinnedIds = emptySet()),
        )
    }

    // ── visibleColumnIndices ─────────────────────────────────────────────────

    @Test
    fun `visibleColumnIndices returns every index when tableKey is null`() {
        val hidden = mapOf(tableColumnKey("Pods", nameStatusAge, 1) to true)
        assertEquals(listOf(0, 1, 2), visibleColumnIndices(nameStatusAge, tableKey = null, identityHeader = null, hidden = hidden))
    }

    @Test
    fun `a hidden middle column is dropped and survivors keep their original indices`() {
        val hidden = mapOf(tableColumnKey("Pods", nameStatusAge, 1) to true)
        val visible = visibleColumnIndices(nameStatusAge, tableKey = "Pods", identityHeader = null, hidden = hidden)
        assertEquals(listOf(0, 2), visible)
    }

    @Test
    fun `the identity column stays visible even when the map marks it hidden`() {
        val hidden = mapOf(tableColumnKey("Pods", nameStatusAge, 0) to true)
        val visible = visibleColumnIndices(nameStatusAge, tableKey = "Pods", identityHeader = null, hidden = hidden)
        assertEquals(listOf(0, 1, 2), visible)
    }

    @Test
    fun `identityColumn 2 stays visible even when the map marks it hidden`() {
        val hidden = mapOf(tableColumnKey("Events", typeAgeObject, 2) to true)
        val visible = visibleColumnIndices(typeAgeObject, tableKey = "Events", identityHeader = "Object", hidden = hidden)
        assertEquals(listOf(0, 1, 2), visible)
    }

    @Test
    fun `an unknown header in the hidden map is ignored`() {
        val hidden = mapOf("Pods::Nonexistent" to true)
        val visible = visibleColumnIndices(nameStatusAge, tableKey = "Pods", identityHeader = null, hidden = hidden)
        assertEquals(listOf(0, 1, 2), visible)
    }

    // ── isLastVisibleColumn ──────────────────────────────────────────────────

    @Test
    fun `isLastVisibleColumn is true only for the final visible hideable column`() {
        // Status is already hidden, leaving Age as the sole hideable survivor.
        val hidden = mapOf(tableColumnKey("Pods", nameStatusAge, 1) to true)
        assertEquals(true, isLastVisibleColumn(nameStatusAge, "Pods", identityHeader = null, hidden = hidden, index = 2))
    }

    @Test
    fun `isLastVisibleColumn is false with two hideable columns still visible`() {
        assertEquals(false, isLastVisibleColumn(nameStatusAge, "Pods", identityHeader = null, hidden = emptyMap(), index = 1))
        assertEquals(false, isLastVisibleColumn(nameStatusAge, "Pods", identityHeader = null, hidden = emptyMap(), index = 2))
    }

    @Test
    fun `isLastVisibleColumn is never true for the identity column`() {
        // Both hideable columns hidden; the identity column is never itself
        // "the last hideable column" because it was never hideable at all.
        val hidden = mapOf(
            tableColumnKey("Pods", nameStatusAge, 1) to true,
            tableColumnKey("Pods", nameStatusAge, 2) to true,
        )
        assertEquals(false, isLastVisibleColumn(nameStatusAge, "Pods", identityHeader = null, hidden = hidden, index = 0))
    }

    // ── tableColumnKey ───────────────────────────────────────────────────────

    @Test
    fun `tableColumnKey disambiguates two columns sharing a header`() {
        val duplicated = listOf(ColumnDef(header = "Value"), ColumnDef(header = "Value"))
        val key0 = tableColumnKey("ConfigMap", duplicated, 0)
        val key1 = tableColumnKey("ConfigMap", duplicated, 1)
        assertNotEquals(key0, key1)
        assertEquals("ConfigMap::Value#0", key0)
        assertEquals("ConfigMap::Value#1", key1)
    }

    @Test
    fun `tableColumnKey omits the index suffix when headers are unique`() {
        assertEquals("Pods::Status", tableColumnKey("Pods", nameStatusAge, 1))
    }

    /**
     * The event tables lead with a 50 dp severity icon and name the row two
     * columns in — but `columns` is already filtered by window width, and
     * their "Object" column drops out below 800 dp. Addressing the identity by
     * header rather than index is what stops a narrow window from protecting
     * (and tiebreaking on) whatever column slid into that position.
     */
    @Test
    fun `the identity column is found by header wherever it sits`() {
        val eventish = listOf(ColumnDef(header = "Type"), ColumnDef(header = "Reason"), ColumnDef(header = "Object"))
        assertEquals(2, identityColumnIndex(eventish, "Object"))
        assertEquals(0, identityColumnIndex(eventish, null))
    }

    @Test
    fun `an identity header the window has filtered out falls back to the first column`() {
        // What the narrow-window event table actually hands over: no "Object".
        val narrow = listOf(ColumnDef(header = "Type"), ColumnDef(header = "Reason"), ColumnDef(header = "Message"))
        assertEquals(0, identityColumnIndex(narrow, "Object"))

        // And the fallback is what gets protected from hiding — never "Message"
        // just because it happens to sit at index 2.
        val visible = visibleColumnIndices(narrow, "Events", "Object", mapOf("Events::Type" to true, "Events::Message" to true))
        assertEquals(listOf(0, 1), visible)
    }
}
