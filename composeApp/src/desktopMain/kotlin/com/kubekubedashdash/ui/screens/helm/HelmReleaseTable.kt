package com.kubekubedashdash.ui.screens.helm

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.helm.HelmReleaseSummary
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.EMPTY_DASH
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowIdentity
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmReleaseRow
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmSummaryState
import com.kubekubedashdash.util.formatAge
import java.time.Instant

private class HelmColumn(
    val header: String,
    val weight: Float,
    val minTableWidth: Dp,
    val cell: (HelmReleaseRow) -> CellData,
)

/**
 * A cell that comes from the decoded payload: [text] once it is decoded, a placeholder before
 * and when it can't be. A cluster failure ([unavailableText], warning colour) is not a corrupt
 * release ([unreadableText], error colour).
 */
private fun summaryCell(
    state: HelmSummaryState,
    unreadableText: String,
    unavailableText: String,
    text: (HelmReleaseSummary) -> String,
): CellData = when (state) {
    is HelmSummaryState.Ready -> CellData(text(state.summary))
    HelmSummaryState.Decoding -> CellData("…", KdTextSecondary)
    is HelmSummaryState.Unreadable -> if (state.transient) CellData(unavailableText, KdWarning) else CellData(unreadableText, KdError)
    HelmSummaryState.Gone -> CellData(EMPTY_DASH, KdTextSecondary)
}

/**
 * The Helm releases table: read-only, so no pinning, no checkbox selection and no row actions
 * beyond the copy items the table builds from the row's identity (`kind = null` keeps kubectl
 * out of them: a release is not a Kubernetes object).
 */
@Composable
internal fun HelmReleaseTable(
    rows: List<HelmReleaseRow>,
    selectedUid: String?,
    onClick: (HelmReleaseRow) -> Unit,
) {
    val columns = listOf(
        HelmColumn("Name", 2.2f, 0.dp) { CellData(it.name, KdPrimary) },
        HelmColumn("Namespace", 1.2f, 400.dp) { CellData(it.namespace) },
        HelmColumn("Chart", 1.6f, 0.dp) { summaryCell(it.summary, "Unreadable", "Unavailable") { s -> s.chart } },
        HelmColumn("App version", 1.0f, 560.dp) { summaryCell(it.summary, "", "") { s -> s.appVersion.ifBlank { EMPTY_DASH } } },
        HelmColumn("Revision", 0.6f, 0.dp) {
            CellData(it.latest.revision.toString(), sortNumber = it.latest.revision.toDouble())
        },
        HelmColumn("Status", 1.0f, 0.dp) { row ->
            CellData(text = row.status, sortValue = row.status, content = { StatusCell(row.status) })
        },
        HelmColumn("Updated", 0.8f, 0.dp) { row ->
            val updated = row.latest.updatedEpochSeconds
            CellData(
                text = updated?.let { seconds -> formatAge(Instant.ofEpochSecond(seconds).toString()) } ?: EMPTY_DASH,
                sortNumber = updated?.toDouble(),
            )
        },
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val visible = columns.filter { maxWidth >= it.minTableWidth }
        val tableRows = rows.map { r ->
            TableRow(
                id = r.uid,
                cells = visible.map { it.cell(r) },
                identity = RowIdentity(kind = null, name = r.name, namespace = r.namespace),
            )
        }

        ResourceTable(
            columns = visible.map { ColumnDef(it.header, it.weight) },
            rows = tableRows,
            selectedRowId = selectedUid,
            onRowClick = { row -> rows.find { it.uid == row.id }?.let(onClick) },
            emptyMessage = "No Helm releases found",
            tableKey = "HelmRelease",
        )
    }
}
