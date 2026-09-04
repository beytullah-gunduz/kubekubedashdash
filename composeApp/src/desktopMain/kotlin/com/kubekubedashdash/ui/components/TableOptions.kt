package com.kubekubedashdash.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global row-density preference for every [ResourceTable]. `Comfortable` is
 * the historical fixed padding; `Compact` tightens it. Nothing else about a
 * table changes with density — same type scale, same horizontal padding.
 */
enum class TableDensity(val key: String, val rowPadding: Dp) {
    Comfortable("comfortable", 7.dp),
    Compact("compact", 3.dp),
    ;

    companion object {
        /** Unknown or absent [key] (including null) falls back to [Comfortable]. */
        fun fromKey(key: String?): TableDensity = entries.firstOrNull { it.key == key } ?: Comfortable
    }
}

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

        val sorted = rows.sortedWith(compareBy({ primaryKey(it) }, { identityKey(it) }))
        if (ascending) sorted else sorted.reversed()
    }
    return ordered.sortedByDescending { (it.pinId ?: it.id) in pinnedIds }
}
