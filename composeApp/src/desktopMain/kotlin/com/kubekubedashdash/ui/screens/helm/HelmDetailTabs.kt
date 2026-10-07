package com.kubekubedashdash.ui.screens.helm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.Screen
import com.kubekubedashdash.helm.HelmCacheKey
import com.kubekubedashdash.helm.HelmDecoded
import com.kubekubedashdash.helm.HelmReleaseDetail
import com.kubekubedashdash.helm.HelmReleaseRepository
import com.kubekubedashdash.helm.HelmReleaseSummary
import com.kubekubedashdash.helm.ManifestResource
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.security_filled
import com.kubekubedashdash.ui.components.CellData
import com.kubekubedashdash.ui.components.ColumnDef
import com.kubekubedashdash.ui.components.EMPTY_DASH
import com.kubekubedashdash.ui.components.ResourceLoadingIndicator
import com.kubekubedashdash.ui.components.ResourceTable
import com.kubekubedashdash.ui.components.RowIdentity
import com.kubekubedashdash.ui.components.StatusCell
import com.kubekubedashdash.ui.components.TableRow
import com.kubekubedashdash.ui.components.kindColor
import com.kubekubedashdash.ui.screens.YamlTextPane
import com.kubekubedashdash.ui.screens.YamlToolbarToggle
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmReleaseRow
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmSummaryState
import com.kubekubedashdash.ui.screens.helm.viewmodel.toSummaryState
import com.kubekubedashdash.ui.screens.relatedScreen
import com.kubekubedashdash.util.RelatedRef
import com.kubekubedashdash.util.formatAge
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import java.time.Instant
import java.util.Locale

/** What the Helm tabs may show, from the masking setting and this release's Reveal. */
internal data class HelmVisibility(val masked: Boolean, val revealed: Boolean) {
    val showValues: Boolean get() = !masked || revealed
    val showNotes: Boolean get() = !masked || revealed
    fun manifestText(detail: HelmReleaseDetail): String = if (!masked || revealed) detail.manifest else detail.maskedManifest
}

/** The Manifest and Values panes render at most this many lines until "Show all" (the YAML viewer composes every line eagerly). */
internal const val HELM_YAML_LINE_CAP = 5_000

/**
 * The first [cap] lines of [text] unless [showAll]; returns the shown text and the total line
 * count. A final newline ends the last line rather than starting an empty one, so it is not
 * counted; text that fits (or [showAll]) comes back unchanged.
 */
internal fun capLines(text: String, cap: Int, showAll: Boolean): Pair<String, Int> {
    if (text.isEmpty()) return text to 0
    val lines = text.lines()
    val total = if (lines.size > 1 && lines.last().isEmpty()) lines.size - 1 else lines.size
    return if (showAll || total <= cap) text to total else lines.take(cap).joinToString("\n") to total
}

/** What a tab says when the release's storage object vanished between the list and the fetch. */
private const val RELEASE_GONE = "This release is no longer stored in the cluster."

/** Why the payload can't be shown, or null while it is decoding or decoded. */
internal fun HelmDecoded<*>?.failureMessage(): String? = when (this) {
    null, is HelmDecoded.Ok -> null
    is HelmDecoded.Failed -> message
    HelmDecoded.Missing -> RELEASE_GONE
}

/** The decoding spinner, the failure message, or [content] with the decoded detail. */
@Composable
private fun HelmDetailGate(
    detailState: HelmDecoded<HelmReleaseDetail>?,
    onRetry: (() -> Unit)?,
    content: @Composable (HelmReleaseDetail) -> Unit,
) {
    when (detailState) {
        null -> ResourceLoadingIndicator()
        is HelmDecoded.Ok -> content(detailState.value)
        else -> HelmDetailFailure(detailState.failureMessage().orEmpty(), onRetry, Modifier.padding(14.dp))
    }
}

/**
 * Why the release can't be shown, with a Retry when [onRetry] is set — only for a cluster
 * failure: a bad payload reads the same however often it is fetched.
 */
@Composable
internal fun HelmDetailFailure(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = KdError)
        if (onRetry != null) {
            TextButton(
                onClick = onRetry,
                colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                shape = kdRoundShape,
            ) {
                Text("Retry", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** [what] is "Values are" or "NOTES are". */
@Composable
internal fun HelmRevealGate(what: String, onReveal: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = 8.dp.kdCorner, color = KdSurfaceVariant) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(Res.drawable.security_filled), null, Modifier.size(16.dp), tint = KdTextSecondary)
            Text(
                "$what hidden while Secret masking is on (Settings → Privacy). Helm values and notes often hold passwords and tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onReveal,
                colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                shape = kdRoundShape,
            ) {
                Text("Reveal", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The YAML viewer over [text], capped at [HELM_YAML_LINE_CAP] lines until "Show all". Copy
 * copies exactly what is shown. [identity] keys the viewer's search and scroll and the cap.
 */
@Composable
internal fun HelmYamlTab(identity: Any, text: String, toggle: YamlToolbarToggle?) {
    var showAll by remember(identity) { mutableStateOf(false) }
    val (shown, total) = remember(text, showAll) { capLines(text, HELM_YAML_LINE_CAP, showAll) }
    Column(Modifier.fillMaxSize()) {
        if (total > HELM_YAML_LINE_CAP && !showAll) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Showing the first ${groupedCount(HELM_YAML_LINE_CAP)} of ${groupedCount(total)} lines.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdWarning,
                )
                TextButton(
                    onClick = { showAll = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = KdPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = kdRoundShape,
                ) {
                    Text("Show all", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        YamlTextPane(identity = identity, displayText = shown, copyText = shown, loading = false, toggle = toggle)
    }
}

private fun groupedCount(n: Int): String = "%,d".format(Locale.ROOT, n)

@Composable
internal fun HelmValuesTab(
    row: HelmReleaseRow,
    detailState: HelmDecoded<HelmReleaseDetail>?,
    visibility: HelmVisibility,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    var computed by remember(row.uid) { mutableStateOf(false) }
    HelmDetailGate(detailState, onRetry) { detail ->
        if (visibility.showValues) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ValuesModeButton("User-supplied", selected = !computed) { computed = false }
                    ValuesModeButton("Computed", selected = computed) { computed = true }
                }
                if (computed) {
                    Text(
                        "Chart defaults merged with the user-supplied values, as helm get values --all prints them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = KdTextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
                val yaml = if (computed) detail.computedValuesYaml else detail.userValuesYaml
                val text = yaml.ifEmpty { if (computed) "# No values" else "# No user-supplied values" }
                HelmYamlTab(
                    identity = "values:${row.latest.cacheKey}:$computed",
                    text = text,
                    toggle = if (visibility.masked) YamlToolbarToggle("Hide", Res.drawable.security_filled, onHide) else null,
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(14.dp)) { HelmRevealGate("Values are", onReveal) }
        }
    }
}

@Composable
private fun ValuesModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = if (selected) KdPrimary else KdTextSecondary),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        shape = kdRoundShape,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun HelmManifestTab(
    row: HelmReleaseRow,
    detailState: HelmDecoded<HelmReleaseDetail>?,
    visibility: HelmVisibility,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    HelmDetailGate(detailState, onRetry) { detail ->
        val toggle = when {
            !visibility.masked -> null
            visibility.revealed -> YamlToolbarToggle("Hide", Res.drawable.security_filled, onHide)
            else -> YamlToolbarToggle("Reveal", Res.drawable.security_filled, onReveal)
        }
        HelmYamlTab(identity = "manifest:${row.latest.cacheKey}", text = visibility.manifestText(detail), toggle = toggle)
    }
}

/**
 * Every stored revision, newest first by default. Each revision's chart, app version and
 * description come from its own decoded payload, fetched as the tab opens.
 */
@Composable
internal fun HelmHistoryTab(row: HelmReleaseRow, repository: HelmReleaseRepository) {
    val states = remember(row.uid) { mutableStateMapOf<HelmCacheKey, HelmSummaryState>() }
    LaunchedEffect(row.group.revisions) {
        row.group.revisions.forEach { ref -> launch { states[ref.cacheKey] = repository.summary(ref).toSummaryState() } }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The pane is narrow until expanded: keep the columns that identify a revision and add
        // App version and Description as it widens, like the list table does.
        val showAppVersion = maxWidth >= 520.dp
        val showDescription = maxWidth >= 640.dp
        val columns = buildList {
            add(ColumnDef("Revision", 0.5f))
            add(ColumnDef("Updated", 0.8f))
            add(ColumnDef("Status", 1.2f))
            add(ColumnDef("Chart", 1.8f))
            if (showAppVersion) add(ColumnDef("App version", 1.0f))
            if (showDescription) add(ColumnDef("Description", 2.2f))
        }
        val rows = row.group.revisions.map { ref ->
            val state = states[ref.cacheKey]
            val summary = (state as? HelmSummaryState.Ready)?.summary
            // The label is what Helm queries on; the decoded status agrees with it for Helm-written data.
            val updated = summary?.lastDeployed?.epochSecond ?: ref.updatedEpochSeconds
            TableRow(
                id = ref.revision.toString(),
                cells = buildList {
                    add(CellData(ref.revision.toString(), sortNumber = ref.revision.toDouble()))
                    add(
                        CellData(
                            text = updated?.let { seconds -> formatAge(Instant.ofEpochSecond(seconds).toString()) } ?: EMPTY_DASH,
                            sortNumber = updated?.toDouble(),
                        ),
                    )
                    add(CellData(text = ref.status, sortValue = ref.status, content = { StatusCell(ref.status) }))
                    add(historyCell(state, "Unreadable", "Unavailable") { it.chart.ifBlank { EMPTY_DASH } })
                    if (showAppVersion) add(historyCell(state, null, null) { it.appVersion.ifBlank { EMPTY_DASH } })
                    if (showDescription) add(historyCell(state, null, null) { it.description.ifBlank { EMPTY_DASH } })
                },
                identity = RowIdentity(kind = null, name = "${row.name} v${ref.revision}", namespace = row.namespace),
            )
        }
        ResourceTable(
            columns = columns,
            rows = rows,
            emptyMessage = "No stored revisions",
            defaultSortHeader = "Revision",
            defaultSortAscending = false,
            tableKey = "HelmReleaseHistory",
        )
    }
}

/**
 * A History cell from a revision's decode state: [text] once decoded, "…" before. A revision
 * that can't be read shows the matching label ([unreadableText] for a bad payload,
 * [unavailableText] for a cluster failure), or a dash when the column has none.
 */
private fun historyCell(
    state: HelmSummaryState?,
    unreadableText: String?,
    unavailableText: String?,
    text: (HelmReleaseSummary) -> String,
): CellData = when (state) {
    null, HelmSummaryState.Decoding -> CellData("…", KdTextSecondary)

    is HelmSummaryState.Ready -> CellData(text(state.summary))

    is HelmSummaryState.Unreadable -> when {
        state.transient && unavailableText != null -> CellData(unavailableText, KdWarning)
        !state.transient && unreadableText != null -> CellData(unreadableText, KdError)
        else -> CellData(EMPTY_DASH, KdTextSecondary)
    }

    HelmSummaryState.Gone -> CellData(EMPTY_DASH, KdTextSecondary)
}

/** The objects the latest revision's manifest renders; a click opens the object's screen when it has one. */
@Composable
internal fun HelmResourcesTab(
    detailState: HelmDecoded<HelmReleaseDetail>?,
    onNavigate: (Screen) -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    HelmDetailGate(detailState, onRetry) { detail ->
        val resources = detail.resources
        // Looked up by position: one manifest can name the same object twice.
        val targets = remember(resources) { resources.map { relatedScreen(it.toRelatedRef()) } }
        if (resources.isEmpty()) {
            Text(
                "This release's manifest renders no objects.",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "${if (resources.size == 1) "1 object" else "${resources.size} objects"} in the latest revision's manifest. Select one to open it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                val columns = listOf(ColumnDef("Kind", 1.2f), ColumnDef("Name", 2.0f), ColumnDef("Namespace", 1.2f), ColumnDef("API version", 1.2f))
                val rows = resources.mapIndexed { index, r ->
                    TableRow(
                        id = index.toString(),
                        cells = listOf(
                            CellData(text = r.kind, sortValue = r.kind, content = { KindCell(r.kind) }),
                            CellData(r.name, if (targets[index] != null) KdPrimary else KdTextSecondary),
                            CellData(r.namespace ?: EMPTY_DASH),
                            CellData(r.apiVersion.ifBlank { EMPTY_DASH }),
                        ),
                        identity = RowIdentity(kind = null, name = r.name, namespace = r.namespace),
                    )
                }
                ResourceTable(
                    columns = columns,
                    rows = rows,
                    onRowClick = { tableRow -> tableRow.id.toIntOrNull()?.let { targets.getOrNull(it) }?.let(onNavigate) },
                    emptyMessage = "This release's manifest renders no objects.",
                    tableKey = "HelmReleaseResources",
                )
            }
        }
    }
}

private fun ManifestResource.toRelatedRef() = RelatedRef(kind = kind, name = name, namespace = namespace, uid = null, group = group)

@Composable
private fun KindCell(kind: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(kdRoundShape).background(kindColor(kind)))
        Spacer(Modifier.width(6.dp))
        Text(
            kind,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
