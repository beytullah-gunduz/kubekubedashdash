package com.kubekubedashdash.ui.screens.generic

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.NONE_PLACEHOLDER
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowAction
import com.kubekubedashdash.ui.components.RowIdentity
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.util.formatElapsed
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

internal class GenericColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (GenericResourceInfo) -> CellData,
)

/**
 * The columns of a generic resource table, in order: the name, the namespace, the status, the CRD printer
 * columns, a SparkApplication's run time and (given [sparkUsages]) its live CPU and Memory, then the age. Each column shows once the table is [GenericColumn.minTableWidth] wide.
 * A printer value that is a UTC timestamp of [now]'s day shows its time only.
 */
internal fun genericColumns(
    resources: List<GenericResourceInfo>,
    namespacedKind: Boolean,
    now: Instant = Instant.now(),
    sparkUsages: Map<String, SparkAppUsage>? = null,
): List<GenericColumn> {
    // CRD printer columns almost always include their own "Age"; the table
    // appends a built-in Age column below, so drop the duplicate here. Assumes
    // the printer Age tracks metadata.creationTimestamp (the kubectl
    // convention); a CRD that redefines it loses the printer value.
    val hasStatus = resources.any { it.status != null }
    // A printer "Status" likewise duplicates the built-in status column when the
    // kind already carries one (Jobs); keep it only when it is the sole status.
    // SparkApplication rows (the ones with spark data) get a built-in Duration column, like Age, so a printer "Duration" would repeat it.
    val hasSparkRuns = resources.any { it.spark != null }
    val extraKeys = resources.flatMap { it.extraColumns.keys }.distinct()
        .filterNot {
            it.equals("Age", ignoreCase = true) ||
                (hasStatus && it.equals("Status", ignoreCase = true)) ||
                (hasSparkRuns && it.equals("Duration", ignoreCase = true)) ||
                // The built-in live CPU and Memory columns (given sparkUsages) would repeat a printer column of that name.
                (hasSparkRuns && sparkUsages != null && (it.equals("CPU", ignoreCase = true) || it.equals("Memory", ignoreCase = true)))
        }

    return buildList {
        add(GenericColumn("Name", 2.5f, 0.dp) { CellData(it.name, KdPrimary) })
        if (namespacedKind) {
            add(GenericColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace ?: "") })
        }
        if (hasStatus) {
            add(
                GenericColumn("Status", 0.8f, 0.dp) { r ->
                    val s = r.status.orEmpty()
                    CellData(text = s, sortValue = s, content = { StatusCell(s) })
                },
            )
        }
        extraKeys.forEachIndexed { i, key ->
            val minW = when {
                extraKeys.size <= 1 -> 0.dp
                i == extraKeys.lastIndex -> (500 + i * 100).dp
                else -> (450 + i * 100).dp
            }
            // A printer column named "Status" carries the same vocabulary as the
            // built-in status column (Running / Completed / Failed …); give it the
            // icon + colour treatment when the row has no real status column.
            val isStatusKey = !hasStatus && key.equals("Status", ignoreCase = true)
            // As wide as its longest header or value (a status icon counts two characters), so a dash-only column does not take a timestamp's room.
            val longest = maxOf(
                key.length,
                resources.maxOfOrNull { r -> printerText(r.extraColumns[key].orEmpty(), now).length + if (isStatusKey) 2 else 0 } ?: 0,
            )
            add(
                GenericColumn(key, (longest * PRINTER_WEIGHT_PER_CHAR).coerceIn(PRINTER_WEIGHT_MIN, PRINTER_WEIGHT_MAX), minW) { r ->
                    val v = r.extraColumns[key] ?: ""
                    if (isStatusKey && v.isNotBlank() && v != NONE_PLACEHOLDER) {
                        CellData(text = v, sortValue = v, content = { StatusCell(v) })
                    } else {
                        // The raw value stays the sort key, so a shortened timestamp still sorts in time order.
                        CellData(text = shortenTodayTimestamp(v, now), sortValue = v)
                    }
                },
            )
        }
        if (hasSparkRuns) {
            // Blank until the application finished; sorted by seconds, so the blank rows go last.
            add(
                GenericColumn("Duration", 0.7f, 0.dp) { r ->
                    val seconds = r.spark?.runSeconds
                    CellData(text = seconds?.let { formatElapsed(Duration.ofSeconds(it)) }.orEmpty(), sortNumber = seconds?.toDouble())
                },
            )
        }
        if (hasSparkRuns && sparkUsages != null) {
            add(GenericColumn("CPU", 0.7f, 0.dp) { r -> sparkCpuCell(sparkUsages[sparkUsageKey(r)]) })
            add(GenericColumn("Memory", 0.8f, 0.dp) { r -> sparkMemoryCell(sparkUsages[sparkUsageKey(r)]) })
        }
        add(GenericColumn("Age", 0.7f, 0.dp) { CellData(it.age) })
    }
}

@Composable
internal fun GenericTable(
    resources: List<GenericResourceInfo>,
    kind: String,
    namespacedKind: Boolean,
    selectedUid: String?,
    onClick: (GenericResourceInfo) -> Unit,
    onDelete: ((GenericResourceInfo) -> Unit)? = null,
    extraActions: ((GenericResourceInfo) -> List<RowAction>)? = null,
    selectedUids: Set<String> = emptySet(),
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: ((String) -> Unit)? = null,
    sparkUsages: Map<String, SparkAppUsage>? = null,
) {
    val columns = genericColumns(resources, namespacedKind, sparkUsages = sparkUsages)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = columns.filter { maxWidth >= it.minTableWidth }
        val columnDefs = visible.map { ColumnDef(it.header, it.weight) }
        val rows = resources.map { r ->
            TableRow(
                id = r.uid,
                pinId = if (namespacedKind) {
                    "${kind.lowercase()}:${r.namespace}:${r.name}"
                } else {
                    "${kind.lowercase()}:${r.name}"
                },
                identity = RowIdentity(kind, r.name, r.namespace),
                cells = visible.map { it.cell(r) },
                actions = (extraActions?.invoke(r) ?: emptyList()) +
                    (if (onDelete != null) listOf(RowAction("Delete") { onDelete(r) }) else emptyList()),
            )
        }

        ResourceTable(
            columns = columnDefs,
            rows = rows,
            selectedRowId = selectedUid,
            onRowClick = { row -> resources.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No resources found",
            tableKey = kind,
            pinnable = onTogglePin != null,
            pinnedIds = pinnedIds,
            onTogglePin = onTogglePin,
            selectable = onSelectionChange != null,
            selectedIds = selectedUids,
            onSelectionChange = onSelectionChange,
        )
    }
}

// A printer column's weight per character of its longest text, and its bounds: Age (0.7) holds about five characters.
private const val PRINTER_WEIGHT_PER_CHAR = 0.12f
private const val PRINTER_WEIGHT_MIN = 0.6f
private const val PRINTER_WEIGHT_MAX = 2.4f

// The text a printer value shows: a dash for none, a timestamp of today without its date.
private fun printerText(value: String, now: Instant): String = if (value == NONE_PLACEHOLDER) "-" else shortenTodayTimestamp(value, now)

// A whole RFC 3339 UTC timestamp, as the API server writes metav1.Time and metav1.MicroTime.
private val UTC_TIMESTAMP = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)""")

/** [value] without its date when it is a UTC timestamp on [now]'s UTC day ("09:02:46Z"); any other value as it is. */
internal fun shortenTodayTimestamp(value: String, now: Instant): String {
    val match = UTC_TIMESTAMP.matchEntire(value) ?: return value
    val today = now.atOffset(ZoneOffset.UTC).toLocalDate().toString()
    return if (match.groupValues[1] == today) match.groupValues[2] else value
}
