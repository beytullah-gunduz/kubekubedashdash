package com.kubekubedashdash.ui.screens.helm

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kubekubedashdash.Screen
import com.kubekubedashdash.helm.HelmDriver
import com.kubekubedashdash.helm.HelmReleaseRepository
import com.kubekubedashdash.helm.HelmReleaseSummary
import com.kubekubedashdash.ui.components.EMPTY_DASH
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmReleaseRow
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmSummaryState

/**
 * The detail pane of one Helm release. For now only the Overview: the facts the list row
 * already holds, with no YAML tab (a release is not a Kubernetes object, so there is no
 * resource to fetch YAML for). [repository] and [onNavigate] are for the tabs that read the
 * release's payload and link to the objects it created.
 */
@Composable
internal fun HelmReleaseDetailPanel(
    row: HelmReleaseRow,
    repository: HelmReleaseRepository,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = buildList {
        add(DetailField("Namespace", row.namespace))
        add(DetailField("Status", row.status, statusColor(row.status)))
        add(DetailField("Revision", row.latest.revision.toString()))
        add(DetailField("Chart", summaryText(row.summary, "Unreadable", "Unavailable") { it.chart }))
        add(DetailField("App version", summaryText(row.summary, EMPTY_DASH, EMPTY_DASH) { it.appVersion.ifBlank { EMPTY_DASH } }))
        val driver = if (row.latest.driver == HelmDriver.SECRET) "Secret" else "ConfigMap"
        add(DetailField("Storage", "$driver ${row.latest.objectName}"))
    }
    ResourceDetailPanel(
        kind = "Helm Release",
        name = row.name,
        namespace = row.namespace,
        status = row.status,
        fields = fields,
        labels = emptyMap(),
        annotations = emptyMap(),
        onClose = onClose,
        modifier = modifier,
        showYamlTab = false,
    )
}

/** The decoded value, or a placeholder while the payload is being decoded or when it can't be (corrupt, or the cluster failed). */
private fun summaryText(
    state: HelmSummaryState,
    unreadableText: String,
    unavailableText: String,
    text: (HelmReleaseSummary) -> String,
): String = when (state) {
    is HelmSummaryState.Ready -> text(state.summary)
    HelmSummaryState.Decoding -> "…"
    is HelmSummaryState.Unreadable -> if (state.transient) unavailableText else unreadableText
    HelmSummaryState.Gone -> EMPTY_DASH
}
