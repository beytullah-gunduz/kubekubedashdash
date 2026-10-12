package com.kubekubedashdash.ui.components

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Preference entry key for one column of one table. Duplicate headers are
 * disambiguated by index — [com.kubekubedashdash.ui.screens.generic.GenericTable]
 * builds its columns from CRD printer-column names, which can repeat, so
 * hiding one twin must not hide the other.
 */
fun tableColumnKey(tableKey: String, columns: List<ColumnDef>, index: Int): String {
    val header = columns[index].header
    val duplicated = columns.count { it.header == header } > 1
    return if (duplicated) "$tableKey::$header#$index" else "$tableKey::$header"
}

/**
 * The index of the identity column — the one that names the row. Resolved by
 * HEADER because [columns] is already filtered by window width at every call
 * site, so a fixed index silently points at a different column once a wider
 * one drops out. Falls back to the first column when [identityHeader] is null
 * or currently filtered out: degraded, but never the wrong column.
 */
fun identityColumnIndex(columns: List<ColumnDef>, identityHeader: String?): Int {
    if (identityHeader == null) return 0
    val index = columns.indexOfFirst { it.header == identityHeader }
    return if (index >= 0) index else 0
}

/**
 * Indices into [columns] that render, in order. The identity column is always
 * present regardless of the hidden map; a null [tableKey] returns every
 * index (opted out of column hiding entirely).
 */
fun visibleColumnIndices(
    columns: List<ColumnDef>,
    tableKey: String?,
    identityHeader: String?,
    hidden: Map<String, Boolean>,
): List<Int> {
    if (tableKey == null) return columns.indices.toList()
    val identity = identityColumnIndex(columns, identityHeader)
    return columns.indices.filter { index ->
        index == identity || hidden[tableColumnKey(tableKey, columns, index)] != true
    }
}

/**
 * True when [index] is the last hideable (non-identity) column still
 * visible — its menu checkbox is rendered disabled so a table can never be
 * reduced to just the identity column.
 */
fun isLastVisibleColumn(
    columns: List<ColumnDef>,
    tableKey: String,
    identityHeader: String?,
    hidden: Map<String, Boolean>,
    index: Int,
): Boolean {
    val identity = identityColumnIndex(columns, identityHeader)
    if (index == identity) return false
    val visibleHideable = columns.indices.filter { i ->
        i != identity && hidden[tableColumnKey(tableKey, columns, i)] != true
    }
    return visibleHideable.size == 1 && visibleHideable[0] == index
}

/**
 * Pinned rows first, then the primary key ([sortHeader], resolved against
 * [columns]; unresolved — including a null [sortHeader] — falls back to
 * [identityHeader]), then the identity column as a tiebreaker. A null
 * [sortHeader] keeps the source order. Descending is
 * the reverse of the ascending order, so tied rows land in reverse-identity
 * order when descending — that is intentional, not a bug to fix.
 *
 * A column whose cells carry [CellData.sortNumber] sorts by number; its rows
 * without a number (no value: "—") go last in both directions, so a
 * highest-first sort does not open on a screen of dashes.
 */
fun sortTableRows(
    rows: List<TableRow>,
    columns: List<ColumnDef>,
    sortHeader: String?,
    ascending: Boolean,
    identityHeader: String?,
    pinnedIds: Set<String>,
): List<TableRow> {
    val identity = identityColumnIndex(columns, identityHeader)

    fun identityKey(row: TableRow): String {
        val cell = row.cells.getOrNull(identity)
        return cell?.sortValue ?: cell?.text ?: ""
    }

    // A null header means the table has never been sorted — keep the source
    // order (for a stream like Events that is arrival order, which is the
    // whole point of it) and only float the pins. A header that is set but
    // cannot be resolved right now — hidden, or filtered out by width — falls
    // back to the identity column, which is a defined order rather than an
    // invisible one.
    val ordered = if (sortHeader == null) {
        rows
    } else {
        val primaryIndex = columns.indexOfFirst { it.header == sortHeader }.let { if (it >= 0) it else identity }

        fun primaryKey(row: TableRow): String {
            val cell = row.cells.getOrNull(primaryIndex)
            return cell?.sortValue ?: cell?.text ?: ""
        }

        fun primaryNumber(row: TableRow): Double? = row.cells.getOrNull(primaryIndex)?.sortNumber

        val (numbered, unnumbered) = rows.partition { primaryNumber(it) != null }
        if (numbered.isEmpty()) {
            val sorted = rows.sortedWith(compareBy({ primaryKey(it) }, { identityKey(it) }))
            if (ascending) sorted else sorted.reversed()
        } else {
            val sorted = numbered.sortedWith(compareBy({ primaryNumber(it) }, { identityKey(it) }))
            val directed = if (ascending) sorted else sorted.reversed()
            directed + unnumbered.sortedWith(compareBy({ primaryKey(it) }, { identityKey(it) }))
        }
    }
    return ordered.sortedByDescending { (it.pinId ?: it.id) in pinnedIds }
}

/**
 * [CellData.sortNumber] for an Age column. Ascending Age means youngest
 * first, so the key is the NEGATED creation time, in seconds (Kubernetes
 * timestamps have whole seconds; epochSecond, unlike toEpochMilli, cannot
 * overflow on an absurd year). Blank or unparseable → null (the row sorts last).
 */
fun ageSortKey(creationTimestamp: String): Double? {
    if (creationTimestamp.isBlank()) return null
    return try {
        -Instant.parse(creationTimestamp).epochSecond.toDouble()
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * A table's sort: which column — by HEADER, because the responsive column list
 * is width-filtered and an index would silently re-target — and which way.
 */
data class TableSort(val header: String, val ascending: Boolean)

/** The `table_sort` preference value for [sort]: `"asc:<header>"` or `"desc:<header>"`. */
fun encodeTableSort(sort: TableSort): String = (if (sort.ascending) "asc:" else "desc:") + sort.header

/**
 * Reverses [encodeTableSort]. Splits on the FIRST `:` so a header containing
 * one survives. Null for anything it could not have produced — the table then
 * shows its default.
 */
fun decodeTableSort(raw: String?): TableSort? {
    if (raw == null) return null
    val separator = raw.indexOf(':')
    if (separator < 0) return null
    val ascending = when (raw.substring(0, separator)) {
        "asc" -> true
        "desc" -> false
        else -> return null
    }
    val header = raw.substring(separator + 1)
    return if (header.isBlank()) null else TableSort(header, ascending)
}

/**
 * The sort to STORE after a click on [clicked], given the sort the table
 * [displayed] and its [default]; null means "no override, show the default".
 * Another column → ascending, except the default column, which goes straight
 * to the default. The same column → ascending flips to descending; descending
 * flips back to ascending when that descending IS the default, and otherwise
 * clears the override — so a third click returns to the default order. A
 * result equal to [default] is returned as null: the default is never stored,
 * so a default changed in a later release still applies.
 */
fun nextTableSort(displayed: TableSort?, default: TableSort?, clicked: String): TableSort? {
    val next = when {
        displayed == null || displayed.header != clicked ->
            if (clicked == default?.header) default else TableSort(clicked, ascending = true)

        displayed.ascending -> TableSort(clicked, ascending = false)

        default == TableSort(clicked, ascending = false) -> TableSort(clicked, ascending = true)

        else -> null
    }
    return next?.takeIf { it != default }
}

/**
 * The sort actually driving the table: the [stored] one while its column is
 * visible, else the [default] while ITS column is visible, else null (source
 * order). The stored entry is not dropped, so re-showing its column brings it back.
 */
fun displayedTableSort(stored: TableSort?, default: TableSort?, visibleHeaders: Collection<String>): TableSort? = stored?.takeIf { it.header in visibleHeaders } ?: default?.takeIf { it.header in visibleHeaders }
