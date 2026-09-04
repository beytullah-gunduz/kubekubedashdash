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
 * Indices into [columns] that render, in order. [identityColumn] is always
 * present regardless of the hidden map; a null [tableKey] returns every
 * index (opted out of column hiding entirely).
 */
fun visibleColumnIndices(
    columns: List<ColumnDef>,
    tableKey: String?,
    identityColumn: Int,
    hidden: Map<String, Boolean>,
): List<Int> {
    if (tableKey == null) return columns.indices.toList()
    return columns.indices.filter { index ->
        index == identityColumn || hidden[tableColumnKey(tableKey, columns, index)] != true
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
    identityColumn: Int,
    hidden: Map<String, Boolean>,
    index: Int,
): Boolean {
    if (index == identityColumn) return false
    val visibleHideable = columns.indices.filter { i ->
        i != identityColumn && hidden[tableColumnKey(tableKey, columns, i)] != true
    }
    return visibleHideable.size == 1 && visibleHideable[0] == index
}

/**
 * Pinned rows first, then the primary key ([sortHeader], resolved against
 * [columns]; unresolved — including a null [sortHeader] — falls back to
 * [identityColumn]), then the identity column as a tiebreaker. Descending is
 * the reverse of the ascending order, so tied rows land in reverse-identity
 * order when descending — that is intentional, not a bug to fix.
 */
fun sortTableRows(
    rows: List<TableRow>,
    columns: List<ColumnDef>,
    sortHeader: String?,
    ascending: Boolean,
    identityColumn: Int,
    pinnedIds: Set<String>,
): List<TableRow> {
    fun identityKey(row: TableRow): String {
        val cell = row.cells.getOrNull(identityColumn)
        return cell?.sortValue ?: cell?.text ?: ""
    }

    val primaryIndex = columns.indexOfFirst { it.header == sortHeader }.let { if (it >= 0) it else identityColumn }

    fun primaryKey(row: TableRow): String {
        val cell = row.cells.getOrNull(primaryIndex)
        return cell?.sortValue ?: cell?.text ?: ""
    }

    val sorted = rows.sortedWith(compareBy({ primaryKey(it) }, { identityKey(it) }))
    val ordered = if (ascending) sorted else sorted.reversed()
    return ordered.sortedByDescending { (it.pinId ?: it.id) in pinnedIds }
}
