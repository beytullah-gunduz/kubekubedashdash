package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.InClusterTargetValidation
import com.kubekubedashdash.services.metricshistory.MetricsSourceMode
import com.kubekubedashdash.services.metricshistory.MetricsSourceSetting
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.resolveMetricsSource
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.GkeClusterDiscoverer

/**
 * The second line of a context's row: what pod charts will use (D15). [detection] holds what
 * InClusterDetection.current says per preference key, the clock already applied, so an expired
 * "nothing found" reads as not searched yet. Only validated fields reach the text.
 */
internal fun metricsSourceStatus(
    context: String,
    settings: Map<String, MetricsSourceSetting>,
    detection: Map<String, DetectionState>,
): String {
    val state = detection[DemoContext.preferenceKey(context)]
    return when (val choice = resolveMetricsSource(context, DemoContext.isMockContext(context), settings, state)) {
        SourceChoice.Demo -> "Demo data"

        is SourceChoice.Gmp ->
            (if (choice.auto) "Auto → " else "") +
                "Google Managed Prometheus · ${choice.target.project} · ${choice.target.location} · ${choice.target.cluster}"

        is SourceChoice.InCluster ->
            if (choice.auto) {
                "Auto → in-cluster Prometheus · ${inClusterTargetText(choice.target)}"
            } else {
                "In-cluster Prometheus · ${inClusterTargetText(choice.target)}" + if (choice.target.https) " · HTTPS" else ""
            }

        SourceChoice.Detecting ->
            if (state == DetectionState.Searching) {
                "Auto → searching this cluster for a Prometheus service…"
            } else {
                "Auto → looks for a Prometheus service when a pod's charts open."
            }

        is SourceChoice.None -> when (choice.reason) {
            NoSourceReason.NothingFound -> "Auto → none: no Prometheus service found. Pick In-cluster to enter one."

            NoSourceReason.CannotSearch ->
                "Auto → none: no permission to list services across namespaces. Pick In-cluster to enter one."

            NoSourceReason.SearchFailed -> "Auto → the search for a Prometheus service failed; it retries within a minute."

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
}

private fun inClusterTargetText(target: InClusterTarget) = "${target.namespace}/${target.service}:${target.port}${target.pathPrefix}"

private val MetricsSourceModes = listOf(
    MetricsSourceMode.AUTO to "Auto",
    MetricsSourceMode.GMP to "Managed Prometheus",
    MetricsSourceMode.IN_CLUSTER to "In-cluster",
    MetricsSourceMode.OFF to "Off",
)

/**
 * One row per known cluster context: its name, a mode picker, the resolved source
 * (so Auto's guess is visible) and, for Managed Prometheus, the project, location
 * and cluster fields, or, for In-cluster, the namespace, service, port, path prefix and HTTPS
 * switch. The fields are draft-and-commit like the Default namespace rows: never bound
 * straight to [settings], whose flow re-seeds after the write.
 *
 * [detection] is what InClusterDetection holds per preference key with the clock applied; it
 * feeds the status line and the In-cluster prefill. [onSearchAgain] gets a context whose Auto
 * search should run afresh.
 */
@Composable
internal fun MetricsHistorySettings(
    contexts: List<String>,
    settings: Map<String, MetricsSourceSetting>,
    onSet: (String, MetricsSourceSetting) -> Unit,
    detection: Map<String, DetectionState> = emptyMap(),
    onSearchAgain: (String) -> Unit = {},
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
            "separate monitoring project. It needs the gcloud CLI, signed in, with roles/monitoring.viewer on that project. " +
            "On other contexts Auto searches the cluster for a Prometheus Service, or pick In-cluster to enter its " +
            "namespace, service and port; either way the history is read through the API server's service proxy, " +
            "so your Kubernetes role needs get on services/proxy there (the edit role has it, view does not).",
        style = MaterialTheme.typography.bodyMedium,
        color = KdTextSecondary,
    )
    Spacer(Modifier.height(12.dp))
    val drafts = remember { mutableStateMapOf<String, String>() }
    contexts.forEachIndexed { index, ctx ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        MetricsHistoryContextRow(
            ctx = ctx,
            settings = settings,
            detection = detection,
            drafts = drafts,
            onSet = onSet,
            onSearchAgain = onSearchAgain,
        )
    }
}

private fun draftKey(ctx: String, field: String) = "$ctx|$field"

private val GmpFieldNames = listOf("project", "location", "cluster")
private val InClusterFieldNames = listOf("namespace", "service", "port", "pathPrefix")

/**
 * One draft-and-commit text field. [label] is shown above the field when not null, [error] below
 * it; both stay within [width].
 */
private class MetricsFieldSpec(
    val name: String,
    val value: String,
    val width: Dp = 150.dp,
    val label: String? = null,
    val placeholder: String = name,
    val error: String? = null,
    val withValue: (String) -> MetricsSourceSetting,
)

// Judged on the committed value: a half-typed draft is not an error yet. Blank is fine here, since
// "incomplete" is the status line's word; the message never repeats the value.
private fun namespaceError(value: String) = "Not a valid namespace name".takeIf { value.isNotBlank() && !InClusterTargetValidation.isValidNamespace(value.trim()) }

private fun serviceError(value: String) = "Not a valid service name".takeIf { value.isNotBlank() && !InClusterTargetValidation.isValidService(value.trim()) }

private fun portError(value: String) = "Port: a number 1-65535 or a port name".takeIf { value.isNotBlank() && !InClusterTargetValidation.isValidPort(value.trim()) }

private fun pathPrefixError(value: String) = "Path: like /prometheus".takeIf { value.isNotBlank() && InClusterTargetValidation.normalizedPathPrefix(value.trim()) == null }

@Composable
private fun MetricsDraftField(
    ctx: String,
    field: MetricsFieldSpec,
    drafts: SnapshotStateMap<String, String>,
    onSet: (String, MetricsSourceSetting) -> Unit,
) {
    val key = draftKey(ctx, field.name)
    val draft = drafts[key] ?: field.value
    Column(modifier = Modifier.width(field.width)) {
        field.label?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
            Spacer(Modifier.height(2.dp))
        }
        SettingsTextField(
            value = draft,
            onValueChange = { drafts[key] = it },
            onCommit = {
                val trimmed = draft.trim()
                // Only write on a real change: an untouched field blurs on every Tab,
                // and each commit is a DataStore transaction.
                if (trimmed != field.value) onSet(ctx, field.withValue(trimmed))
            },
            placeholder = field.placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
        field.error?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = KdError)
        }
    }
}

@Composable
private fun MetricsHistoryContextRow(
    ctx: String,
    settings: Map<String, MetricsSourceSetting>,
    detection: Map<String, DetectionState>,
    drafts: SnapshotStateMap<String, String>,
    onSet: (String, MetricsSourceSetting) -> Unit,
    onSearchAgain: (String) -> Unit,
) {
    val key = DemoContext.preferenceKey(ctx)
    val stored = settings[key] ?: MetricsSourceSetting()
    val parsed = GkeClusterDiscoverer.parseGkeContext(ctx)
    val found = (detection[key] as? DetectionState.Found)?.target
    // Auto searches only where it has nothing better: a gke_ context goes to Managed Prometheus, the demo to its own data.
    val autoSearches = stored.mode == MetricsSourceMode.AUTO &&
        resolveMetricsSource(ctx, DemoContext.isMockContext(ctx), settings, detection[key]).let { it !is SourceChoice.Gmp && it != SourceChoice.Demo }

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
        // Four equal segments leave "Managed Prometheus" 122 dp at the smallest dialog and 136 dp at
        // the cap. It needs about 162 dp (Default) or 174 dp (Retro) on one line, check mark
        // included, so it wraps to two lines in both styles at any pane width. The buttons share
        // the tallest one's height, so the row stays one even band.
        FullWidthSingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 540.dp).height(IntrinsicSize.Min)) {
            MetricsSourceModes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = stored.mode == mode,
                    onClick = {
                        // Prefill only the blank fields, and only for the mode being entered: the
                        // gke_ context name and the detected Service are guesses the user can then correct.
                        val prefillGmp = mode == MetricsSourceMode.GMP && parsed != null
                        val prefillInCluster = mode == MetricsSourceMode.IN_CLUSTER && found != null
                        // A draft left from earlier typing would otherwise cover the stored value.
                        (GmpFieldNames + InClusterFieldNames).forEach { drafts.remove(draftKey(ctx, it)) }
                        val allInClusterBlank = listOf(stored.namespace, stored.service, stored.port, stored.pathPrefix).all { it.isBlank() }
                        onSet(
                            ctx,
                            stored.copy(
                                mode = mode,
                                project = if (prefillGmp) stored.project.ifBlank { parsed.first } else stored.project,
                                location = if (prefillGmp) stored.location.ifBlank { parsed.second } else stored.location,
                                cluster = if (prefillGmp) stored.cluster.ifBlank { parsed.third } else stored.cluster,
                                namespace = if (prefillInCluster) stored.namespace.ifBlank { found.namespace } else stored.namespace,
                                service = if (prefillInCluster) stored.service.ifBlank { found.service } else stored.service,
                                port = if (prefillInCluster) stored.port.ifBlank { found.port } else stored.port,
                                pathPrefix = if (prefillInCluster) stored.pathPrefix.ifBlank { found.pathPrefix } else stored.pathPrefix,
                                // Taken from the search only when none of the four fields held anything.
                                https = if (prefillInCluster && allInClusterBlank) found.https else stored.https,
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                metricsSourceStatus(ctx, settings, detection),
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (autoSearches) {
                TextButton(
                    onClick = { onSearchAgain(ctx) },
                    modifier = Modifier.height(28.dp),
                    shape = kdRoundShape,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Search again", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (stored.mode == MetricsSourceMode.GMP) {
            Spacer(Modifier.height(6.dp))
            val fields = listOf(
                MetricsFieldSpec("project", stored.project) { stored.copy(project = it) },
                MetricsFieldSpec("location", stored.location) { stored.copy(location = it) },
                MetricsFieldSpec("cluster", stored.cluster) { stored.copy(cluster = it) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEach { field -> MetricsDraftField(ctx, field, drafts, onSet) }
            }
        }
        if (stored.mode == MetricsSourceMode.IN_CLUSTER) {
            Spacer(Modifier.height(6.dp))
            val fields = listOf(
                MetricsFieldSpec(
                    "namespace",
                    stored.namespace,
                    width = 150.dp,
                    label = "Namespace",
                    placeholder = "e.g. monitoring",
                    error = namespaceError(stored.namespace),
                ) { stored.copy(namespace = it) },
                MetricsFieldSpec(
                    "service",
                    stored.service,
                    width = 170.dp,
                    label = "Service",
                    placeholder = "e.g. prometheus-server",
                    error = serviceError(stored.service),
                ) { stored.copy(service = it) },
                MetricsFieldSpec(
                    "port",
                    stored.port,
                    width = 140.dp,
                    label = "Port",
                    placeholder = "number or name",
                    error = portError(stored.port),
                ) { stored.copy(port = it) },
                MetricsFieldSpec(
                    "pathPrefix",
                    stored.pathPrefix,
                    width = 180.dp,
                    label = "Path prefix (optional)",
                    placeholder = "e.g. /prometheus",
                    error = pathPrefixError(stored.pathPrefix),
                ) { stored.copy(pathPrefix = it) },
            )
            // Wraps instead of overflowing: the smallest Settings dialog leaves 485 dp for the row.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fields.forEach { field -> MetricsDraftField(ctx, field, drafts, onSet) }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = stored.https, onCheckedChange = { onSet(ctx, stored.copy(https = it)) })
                Text(
                    "HTTPS: the Service itself speaks TLS",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stored.https) MaterialTheme.colorScheme.primary else KdTextSecondary,
                )
            }
        }
    }
}
