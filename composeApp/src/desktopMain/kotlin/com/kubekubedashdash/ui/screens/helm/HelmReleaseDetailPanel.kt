package com.kubekubedashdash.ui.screens.helm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.KdSurfaceVariant
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.helm.HelmDecoded
import com.kubekubedashdash.helm.HelmDriver
import com.kubekubedashdash.helm.HelmReleaseDetail
import com.kubekubedashdash.helm.HelmReleaseRepository
import com.kubekubedashdash.helm.HelmReleaseSummary
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.kdMonoFamily
import com.kubekubedashdash.orCompact
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.account_tree_filled
import com.kubekubedashdash.resources.code_filled
import com.kubekubedashdash.resources.description_filled
import com.kubekubedashdash.resources.schedule_filled
import com.kubekubedashdash.retroCaps
import com.kubekubedashdash.retroChrome
import com.kubekubedashdash.ui.components.EMPTY_DASH
import com.kubekubedashdash.ui.components.statusColor
import com.kubekubedashdash.ui.screens.DetailField
import com.kubekubedashdash.ui.screens.ExtraTab
import com.kubekubedashdash.ui.screens.OverviewSection
import com.kubekubedashdash.ui.screens.ResourceDetailPanel
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmReleaseRow
import com.kubekubedashdash.ui.screens.helm.viewmodel.HelmSummaryState
import com.kubekubedashdash.util.formatAge
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The detail pane of one Helm release: an Overview with the release's facts and NOTES, then
 * the Values, Manifest, History and Resources tabs. There is no YAML tab (a release is not a
 * Kubernetes object, so there is no resource to fetch YAML for).
 *
 * One Reveal state serves the Values, the Manifest and the NOTES: with Secret masking on, all
 * three stay hidden (the manifest masked) until it is set. It resets for another release and
 * whenever the setting flips.
 */
@Composable
internal fun HelmReleaseDetailPanel(
    row: HelmReleaseRow,
    repository: HelmReleaseRepository,
    onNavigate: (Screen) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maskPref by PreferenceRepository.maskSecretValues.collectAsState()
    var revealed by remember(row.uid) { mutableStateOf(false) }
    LaunchedEffect(maskPref) { revealed = false }
    val visibility = HelmVisibility(masked = maskPref, revealed = revealed)

    // Keyed on the revision, not just the release: this panel is recomposed in place for
    // another release, and the previous release's payload must never show under the new one.
    var detailState by remember(row.latest.cacheKey) { mutableStateOf<HelmDecoded<HelmReleaseDetail>?>(null) }
    var detailAttempt by remember(row.latest.cacheKey) { mutableIntStateOf(0) }
    LaunchedEffect(row.latest.cacheKey, detailAttempt) { detailState = repository.detail(row.latest) }
    val detail = (detailState as? HelmDecoded.Ok)?.value
    // Only a cluster failure is worth another try (the repository doesn't cache it); a bad
    // payload reads the same however often it is fetched.
    val onRetry: (() -> Unit)? = if ((detailState as? HelmDecoded.Failed)?.transient == true) {
        {
            detailState = null
            detailAttempt++
        }
    } else {
        null
    }

    // The decoded detail's summary once it lands, the list row's until then.
    val summaryState = detail?.let { HelmSummaryState.Ready(it.summary) } ?: row.summary
    val fields = buildList {
        add(DetailField("Namespace", row.namespace))
        add(DetailField("Status", row.status, statusColor(row.status)))
        add(DetailField("Revision", row.latest.revision.toString()))
        add(DetailField("Chart", summaryText(summaryState, "Unreadable", "Unavailable") { it.chart }))
        add(DetailField("App version", summaryText(summaryState, EMPTY_DASH, EMPTY_DASH) { it.appVersion.ifBlank { EMPTY_DASH } }))
        add(DetailField("First deployed", detailText(detailState, detail) { it.firstDeployed?.let(::describeTime) ?: EMPTY_DASH }))
        add(DetailField("Last deployed", detailText(detailState, detail) { it.lastDeployed?.let(::describeTime) ?: EMPTY_DASH }))
        add(DetailField("Description", detailText(detailState, detail) { it.description.ifBlank { EMPTY_DASH } }))
        val driver = if (row.latest.driver == HelmDriver.SECRET) "Secret" else "ConfigMap"
        add(DetailField("Storage", "$driver ${row.latest.objectName}"))
        add(DetailField("Revisions stored", row.group.revisions.size.toString()))
    }

    val failure = detailState.failureMessage()
    val sections = listOf(
        if (failure != null) {
            OverviewSection("helm-error") {
                HelmDetailFailure("Couldn't read this release: $failure", onRetry)
            }
        } else {
            OverviewSection("helm-notes") {
                HelmNotesSection(detail, visibility, onReveal = { revealed = true })
            }
        },
    )

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
        extraTabs = listOf(
            ExtraTab("Values", Res.drawable.description_filled, isLoading = detailState == null) {
                HelmValuesTab(row, detailState, visibility, onReveal = { revealed = true }, onHide = { revealed = false }, onRetry = onRetry)
            },
            ExtraTab("Manifest", Res.drawable.code_filled, isLoading = detailState == null) {
                HelmManifestTab(row, detailState, visibility, onReveal = { revealed = true }, onHide = { revealed = false }, onRetry = onRetry)
            },
            ExtraTab("History", Res.drawable.schedule_filled) {
                HelmHistoryTab(row, repository)
            },
            ExtraTab("Resources", Res.drawable.account_tree_filled, isLoading = detailState == null) {
                HelmResourcesTab(detailState, onNavigate, onRetry)
            },
        ),
        overviewSections = sections,
        onDelete = null,
        actions = emptyList(),
        showYamlTab = false,
    )
}

/** The Overview's NOTES: behind the Reveal gate while masking is on, `null` [detail] = still decoding. */
@Composable
private fun HelmNotesSection(detail: HelmReleaseDetail?, visibility: HelmVisibility, onReveal: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp.orCompact(10.dp))) {
        Text(
            "NOTES".retroCaps(),
            style = MaterialTheme.typography.labelLarge.retroChrome(8.sp),
            color = KdTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            !visibility.showNotes -> HelmRevealGate("NOTES are", onReveal)

            detail == null -> Text("…", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)

            detail.notes.isBlank() -> Text(
                "This release has no NOTES.",
                style = MaterialTheme.typography.bodySmall,
                color = KdTextSecondary,
            )

            else -> Surface(modifier = Modifier.fillMaxWidth(), shape = 8.dp.kdCorner, color = KdSurfaceVariant) {
                SelectionContainer {
                    Text(
                        detail.notes,
                        fontFamily = kdMonoFamily(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

/** "3d4h ago · 2026-01-02T03:04:05Z". */
private fun describeTime(time: Instant): String = "${formatAge(time.toString())} ago · ${DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(ChronoUnit.SECONDS))}"

/** A field only the full payload holds: "…" while it decodes, a dash when it failed. */
private fun detailText(
    detailState: HelmDecoded<HelmReleaseDetail>?,
    detail: HelmReleaseDetail?,
    text: (HelmReleaseSummary) -> String,
): String = when {
    detail != null -> text(detail.summary)
    detailState == null -> "…"
    else -> EMPTY_DASH
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
