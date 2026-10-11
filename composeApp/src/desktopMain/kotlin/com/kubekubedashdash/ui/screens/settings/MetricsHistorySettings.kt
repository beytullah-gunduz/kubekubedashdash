package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.resolveMetricsSource
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.GkeClusterDiscoverer

/** The second line of a context's row: what pod charts will use (D15). */
internal fun metricsSourceStatus(context: String, settings: Map<String, MetricsSourceSetting>): String = when (val choice = resolveMetricsSource(context, DemoContext.isMockContext(context), settings, detection = null)) {
    SourceChoice.Demo -> "Demo data"

    is SourceChoice.Gmp ->
        (if (choice.auto) "Auto → " else "") +
            "Google Managed Prometheus · ${choice.target.project} · ${choice.target.location} · ${choice.target.cluster}"

    is SourceChoice.InCluster ->
        (if (choice.auto) "Auto → " else "") +
            "In-cluster Prometheus · ${choice.target.namespace}/${choice.target.service}:${choice.target.port}${choice.target.pathPrefix}"

    SourceChoice.Detecting -> "Auto → looking for a Prometheus service…"

    is SourceChoice.None -> when (choice.reason) {
        NoSourceReason.NothingFound -> "Auto → none: no Prometheus service found."

        NoSourceReason.CannotSearch -> "Auto → none: not allowed to list services."

        NoSourceReason.SearchFailed -> "Auto → none: the search failed."

        NoSourceReason.Disabled -> "Off: pod charts show live metrics-server data only."

        NoSourceReason.IncompleteSetting ->
            if (settings[DemoContext.preferenceKey(context)]?.mode == MetricsSourceMode.IN_CLUSTER) {
                "Incomplete: enter a valid namespace, service and port."
            } else {
                "Incomplete: enter a valid project, location and cluster."
            }

        NoSourceReason.NotConnected -> ""
    }
}

private val MetricsSourceModes = listOf(
    MetricsSourceMode.AUTO to "Auto",
    MetricsSourceMode.GMP to "Managed Prometheus",
    MetricsSourceMode.OFF to "Off",
)

/**
 * One row per known cluster context: its name, a mode picker, the resolved source
 * (so Auto's guess is visible) and, for Managed Prometheus, the project, location
 * and cluster fields. The fields are draft-and-commit like the Default namespace
 * rows: never bound straight to [settings], whose flow re-seeds after the write.
 */
@Composable
internal fun MetricsHistorySettings(
    contexts: List<String>,
    settings: Map<String, MetricsSourceSetting>,
    onSet: (String, MetricsSourceSetting) -> Unit,
) {
    if (contexts.isEmpty()) {
        Text(
            "No cluster contexts found. Add a kubeconfig context to choose where its metrics history comes from.",
            style = MaterialTheme.typography.bodyMedium,
            color = KdTextSecondary,
        )
        return
    }
    Text(
        "A pod's charts can show history from Google Managed Prometheus. Auto uses it for contexts named " +
            "gke_<project>_<location>_<cluster> (gcloud's default). Pick Managed Prometheus to enter the project, " +
            "location and cluster yourself, for example when the context was renamed or the metrics live in a " +
            "separate monitoring project. It needs the gcloud CLI, signed in, with roles/monitoring.viewer on that project.",
        style = MaterialTheme.typography.bodyMedium,
        color = KdTextSecondary,
    )
    Spacer(Modifier.height(12.dp))
    val drafts = remember { mutableStateMapOf<String, String>() }
    contexts.forEachIndexed { index, ctx ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        MetricsHistoryContextRow(ctx = ctx, settings = settings, drafts = drafts, onSet = onSet)
    }
}

private fun draftKey(ctx: String, field: String) = "$ctx|$field"

private class MetricsFieldSpec(val name: String, val value: String, val withValue: (String) -> MetricsSourceSetting)

@Composable
private fun MetricsHistoryContextRow(
    ctx: String,
    settings: Map<String, MetricsSourceSetting>,
    drafts: SnapshotStateMap<String, String>,
    onSet: (String, MetricsSourceSetting) -> Unit,
) {
    val stored = settings[DemoContext.preferenceKey(ctx)] ?: MetricsSourceSetting()
    val parsed = GkeClusterDiscoverer.parseGkeContext(ctx)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            ctx,
            style = MaterialTheme.typography.bodySmall,
            color = KdTextPrimary,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        // Three equal segments give "Managed Prometheus" about a third of the row, so the row sits
        // under the name; at its cap the label holds one line in both styles, and below it wraps.
        FullWidthSingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 540.dp)) {
            MetricsSourceModes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = stored.mode == mode,
                    onClick = {
                        // Prefill only the blank fields, and only for Managed Prometheus: the
                        // gke_ context name is a guess the user can then correct.
                        val prefill = mode == MetricsSourceMode.GMP && parsed != null
                        // A draft left from earlier typing would otherwise cover the stored value.
                        listOf("project", "location", "cluster").forEach { drafts.remove(draftKey(ctx, it)) }
                        onSet(
                            ctx,
                            stored.copy(
                                mode = mode,
                                project = if (prefill) stored.project.ifBlank { parsed.first } else stored.project,
                                location = if (prefill) stored.location.ifBlank { parsed.second } else stored.location,
                                cluster = if (prefill) stored.cluster.ifBlank { parsed.third } else stored.cluster,
                            ),
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = MetricsSourceModes.size,
                        baseShape = kdRoundShape,
                    ),
                ) {
                    Text(label, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            metricsSourceStatus(ctx, settings),
            style = MaterialTheme.typography.labelSmall,
            color = KdTextSecondary,
        )
        if (stored.mode == MetricsSourceMode.GMP) {
            Spacer(Modifier.height(6.dp))
            val fields = listOf(
                MetricsFieldSpec("project", stored.project) { stored.copy(project = it) },
                MetricsFieldSpec("location", stored.location) { stored.copy(location = it) },
                MetricsFieldSpec("cluster", stored.cluster) { stored.copy(cluster = it) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field ->
                    val key = draftKey(ctx, field.name)
                    val draft = drafts[key] ?: field.value
                    SettingsTextField(
                        value = draft,
                        onValueChange = { drafts[key] = it },
                        onCommit = {
                            val trimmed = draft.trim()
                            // Only write on a real change: an untouched field blurs on every Tab,
                            // and each commit is a DataStore transaction.
                            if (trimmed != field.value) onSet(ctx, field.withValue(trimmed))
                        },
                        placeholder = field.name,
                        modifier = Modifier.width(150.dp),
                    )
                }
            }
        }
    }
}
