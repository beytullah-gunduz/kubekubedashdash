package com.kubekubedashdash.ui.screens.deployments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSeriesMemory
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.services.metricshistory.DeploymentPodSet
import com.kubekubedashdash.services.metricshistory.GmpTarget
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.deploymentPodSet
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.components.ChartMarker
import com.kubekubedashdash.ui.components.ChartReference
import com.kubekubedashdash.ui.components.ChartSeries
import com.kubekubedashdash.ui.components.LogToolbarMenu
import com.kubekubedashdash.ui.components.LogToolbarMenuItem
import com.kubekubedashdash.ui.components.LogToolbarToggle
import com.kubekubedashdash.ui.components.MAX_CHART_SERIES
import com.kubekubedashdash.ui.components.TimeSeriesChart
import com.kubekubedashdash.ui.components.podSetHue
import com.kubekubedashdash.ui.screens.pods.HISTORY_REFRESH_MS
import com.kubekubedashdash.ui.screens.pods.HOST_NETWORK_NOTE
import com.kubekubedashdash.ui.screens.pods.LOADING_HISTORY_NOTE
import com.kubekubedashdash.ui.screens.pods.LoadedHistory
import com.kubekubedashdash.ui.screens.pods.NO_THROTTLING_DATA_NOTE
import com.kubekubedashdash.ui.screens.pods.SectionLabel
import com.kubekubedashdash.ui.screens.pods.containerColorIndex
import com.kubekubedashdash.ui.screens.pods.formatBytesValue
import com.kubekubedashdash.ui.screens.pods.formatCoresValue
import com.kubekubedashdash.ui.screens.pods.formatFractionValue
import com.kubekubedashdash.ui.screens.pods.formatRateValue
import com.kubekubedashdash.ui.screens.pods.needsFetch
import com.kubekubedashdash.ui.screens.pods.shortFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

internal const val NO_REPLICASETS_NOTE = "No ReplicaSets of this Deployment are loaded (check the namespace selection)."

// The pod panel's note says "set"; here no current pod may be loaded at all (scaled to 0, another namespace selected).
internal const val NO_CURRENT_CPU_LIMIT_NOTE = "No current pod has a CPU limit — CFS throttling does not apply."

internal const val GMP_STALENESS_NOTE =
    "Managed Prometheus keeps reporting a deleted pod for a few minutes (memory up to 5, CPU for its rate window, at least 5), so earlier pods' lines can run past a rollout marker, and Total counts them twice there."

internal const val CUT_POD_NAMES_NOTE =
    "These ReplicaSet names are longer than a pod name keeps, so the charts can include pods of another workload whose name starts the same way, and Per pod may not tell revisions apart."

// Every pod name is its ReplicaSet's prefix plus 5 generated characters.
private const val POD_SUFFIX_LENGTH = 5

/** The newest ReplicaSet's pods that the pod informer holds, by name (demo pods can have a blank uid), sorted. */
internal fun currentPodsOf(deployment: DeploymentInfo, podSet: DeploymentPodSet?, pods: List<PodInfo>): List<PodInfo> {
    val prefix = podSet?.currentReplicaSet?.let(PromQl::podPrefix) ?: return emptyList()
    return pods
        .filter { it.namespace == deployment.namespace && it.name.startsWith(prefix) && it.name.length == prefix.length + POD_SUFFIX_LENGTH }
        .sortedBy { it.name }
}

/** Network is the node's on a host-network pod, and throttling needs a CPU limit on some current pod. */
internal fun deploymentKinds(currentPods: List<PodInfo>): List<PodSeriesKind> = buildList {
    add(PodSeriesKind.Cpu)
    add(PodSeriesKind.Memory)
    if (currentPods.none { it.hostNetwork }) {
        add(PodSeriesKind.NetworkRx)
        add(PodSeriesKind.NetworkTx)
    }
    if (currentPods.any { pod -> pod.resources.any { it.cpuLimitMillis != null } }) add(PodSeriesKind.Throttling)
}

// A per-pod network chart would need two lines per pod, so network is always the sum.
private fun modeFor(kind: PodSeriesKind, mode: PodSetMode): PodSetMode = when (kind) {
    PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx -> PodSetMode.Total
    else -> mode
}

/**
 * The sentence shown instead of the charts when the history source itself failed; never a raw
 * response. It names only what the validated target holds (project, cluster, namespace, service).
 * Unlike the pod panel's text it never points at live metrics-server data: a Deployment panel has none.
 */
internal fun deploymentFailureMessage(choice: SourceChoice, failed: SeriesResult.Failed): String = when (choice) {
    is SourceChoice.Gmp -> gmpFailureText(choice.target, failed)
    is SourceChoice.InCluster -> inClusterFailureText(choice, failed)
    else -> genericFailureText(failed)
}

private fun genericFailureText(failed: SeriesResult.Failed) = "Metrics history unavailable (${failed.failure})."

private fun gmpFailureText(t: GmpTarget, failed: SeriesResult.Failed): String = when (failed.failure) {
    HistoryFailure.GcloudUnavailable ->
        "Metrics history needs the gcloud CLI, which isn't installed or isn't on the PATH."

    HistoryFailure.NotSignedIn ->
        "gcloud couldn't provide an access token — run `gcloud auth login`. Retrying every 30 s."

    HistoryFailure.Forbidden ->
        "No access to Managed Prometheus data in project ${t.project}: it needs roles/monitoring.viewer there. " +
            "If this cluster's metrics live in a separate monitoring project, set it in Settings → Metrics history. (${failed.detail})"

    HistoryFailure.NoContainerMetrics ->
        "Project ${t.project} has no container metrics for cluster ${t.cluster} (${t.location}). " +
            "Turn them on with `gcloud container clusters update ${t.cluster} --location=${t.location} --monitoring=SYSTEM,CADVISOR,KUBELET` " +
            "— that flag replaces the current list, so add any other packages you use. " +
            "If the metrics live in a separate monitoring project, set it in Settings → Metrics history."

    HistoryFailure.BadQuery -> "Managed Prometheus rejected the query (${failed.detail})."

    HistoryFailure.Unavailable -> "Managed Prometheus didn't answer (${failed.detail}). Retrying every 30 s."

    // The proxy transport produces these; a Managed Prometheus source never does.
    HistoryFailure.NoProxyAccess, HistoryFailure.ServiceGone, HistoryFailure.WrongPath -> genericFailureText(failed)
}

private fun inClusterFailureText(choice: SourceChoice.InCluster, failed: SeriesResult.Failed): String {
    val t = choice.target
    val where = "${t.namespace}/${t.service}"
    return when (failed.failure) {
        HistoryFailure.NoProxyAccess ->
            "Your Kubernetes role can't reach Prometheus through the API server: it needs get on services/proxy " +
                "in namespace ${t.namespace} (granted by edit or admin, not view)."

        HistoryFailure.Forbidden ->
            "Prometheus at $where refused the request (${failed.detail}) — it may sit behind an authenticating proxy."

        HistoryFailure.ServiceGone ->
            "Service $where no longer exists. " + if (choice.auto) "Searching again." else "Check Settings → Metrics history."

        HistoryFailure.WrongPath ->
            "$where answered 404 at the Prometheus API path — check the path prefix in Settings → Metrics history."

        HistoryFailure.NoContainerMetrics ->
            "This Prometheus has no cAdvisor container metrics (container_cpu_usage_seconds_total): " +
                "its scrape config must include the kubelet's /metrics/cadvisor."

        HistoryFailure.BadQuery -> "Prometheus rejected the query (${failed.detail})."

        HistoryFailure.Unavailable -> "Prometheus didn't answer (${failed.detail}). Retrying every 30 s."

        // gcloud is a Managed Prometheus matter; an in-cluster source never reports it.
        HistoryFailure.GcloudUnavailable, HistoryFailure.NotSignedIn -> genericFailureText(failed)
    }
}

/**
 * A Deployment's resource usage over time from the cluster's metrics-history source (Managed
 * Prometheus or a Prometheus behind a Service, the demo cluster's included), refreshed every
 * [refreshMs]: CPU, memory, network and CPU throttling of the pods of every ReplicaSet the Deployment
 * owns, current and old, with a marker at each rollout. Per pod draws one line per pod of the newest
 * ReplicaSet in colour and the pods of older ReplicaSets as grey lines; Total draws the sum. Network
 * is always the sum. There is no live chart to fall back on: without a source nothing is shown, and
 * while the source answers a line says so.
 *
 * Only pods of ReplicaSets that still exist are selected. Staleness differs by backend: Managed
 * Prometheus writes no staleness markers, so a deleted pod's memory line lingers up to 5 minutes and
 * its CPU line up to the rate window (5 minutes at least); lines can run past the marker of the rollout
 * that replaced them and Total counts those pods twice there. An in-cluster Prometheus marks a vanished
 * series stale, so its lines end with the pod. The section says so under the charts on Managed Prometheus.
 */
@Composable
internal fun DeploymentUsageHistorySection(
    deployment: DeploymentInfo,
    service: MetricsHistoryService,
    replicaSets: List<GenericResourceInfo>,
    pods: List<PodInfo>,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshMs: Long = HISTORY_REFRESH_MS,
) {
    val settings by service.settings.collectAsState()
    val detection by service.detectionState.collectAsState()
    // Not keyed on the live context: a panel first composed while disconnected shows nothing until
    // the settings, the detection or the Deployment change.
    val choice = remember(settings, detection, deployment.uid, deployment.namespace, deployment.name) { service.choice() }
    val source = remember(choice) { service.sourceFor(choice) }
    // The search lives in the detection's own scope, so leaving the panel never cancels it, and its
    // answer flips [choice]. Keyed on the detection states too: "Search again" during a search
    // abandons it and leaves Detecting equal to itself, so the choice alone would never start the
    // next one. ensureDetected joins a search under way and does nothing once there is an answer.
    LaunchedEffect(choice, detection) {
        if (choice is SourceChoice.Detecting) service.ensureDetected()
    }
    var range by remember { mutableStateOf(HistoryRange.H6) }
    var mode by remember { mutableStateOf(PodSetMode.PerPod) }
    val podSet = remember(deployment.uid, deployment.namespace, replicaSets) {
        deploymentPodSet(deployment.uid, deployment.namespace, replicaSets)
    }
    val currentPods = remember(deployment.namespace, podSet?.currentReplicaSet, pods) { currentPodsOf(deployment, podSet, pods) }
    val kinds = deploymentKinds(currentPods)
    val podSetRef = podSet?.ref
    var loaded by remember(deployment.uid, range, mode, choice, podSetRef, kinds) { mutableStateOf<LoadedHistory?>(null) }

    if (source != null && podSetRef != null) {
        LaunchedEffect(deployment.uid, range, mode, choice, podSetRef, kinds) {
            while (true) {
                val window = range.window(nowEpochSec())
                val current = loaded
                // A 24 h window moves only every 720 s: refetching an identical, fully answered one wastes quota.
                if (needsFetch(current, window)) {
                    loaded = LoadedHistory(
                        window,
                        coroutineScope {
                            kinds.map { k -> k to async { podSetSeriesOrFailed(source, k, podSetRef, modeFor(k, mode), window) } }
                                .associate { (k, deferred) -> k to deferred.await() }
                        },
                    )
                }
                delay(refreshMs)
            }
        }
    }

    // Without a source there is no live chart to show instead, so the section is absent.
    if (choice is SourceChoice.None) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Resource usage")
            // Measured last, so it is the source label that gives way in a narrow panel (or in Retro's
            // wider type), never the mode toggles or the range menu.
            Text(
                source?.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            LogToolbarToggle(
                label = "Per pod",
                on = mode == PodSetMode.PerPod,
                onToggle = { mode = PodSetMode.PerPod },
                description = "One line per pod; earlier revisions in grey.",
            )
            Spacer(Modifier.width(4.dp))
            LogToolbarToggle(
                label = "Total",
                on = mode == PodSetMode.Total,
                onToggle = { mode = PodSetMode.Total },
                description = "One line: the sum over every pod.",
            )
            Spacer(Modifier.width(8.dp))
            LogToolbarMenu(
                label = "${range.label} ▾",
                items = HistoryRange.entries.map { option -> LogToolbarMenuItem(option.label, option == range) { range = option } },
                description = "Time window of the charts.",
            )
        }
        val answer = loaded
        val failed = answer?.let(::headlineFailure)
        when {
            choice is SourceChoice.Detecting -> LoadingLine()
            podSet == null -> NoteLine(NO_REPLICASETS_NOTE)
            answer == null -> LoadingLine()
            failed != null -> NoteLine(deploymentFailureMessage(choice, failed))
            else -> DeploymentHistoryCharts(choice, podSet, mode, kinds, currentPods.firstOrNull(), answer)
        }
    }
}

/**
 * The failure that kept CPU and memory from drawing anything, CPU's first; null when either has a
 * point to draw or neither failed (an empty answer then shows the charts' own "No data in this range").
 */
internal fun headlineFailure(loaded: LoadedHistory): SeriesResult.Failed? {
    if (loaded.hasCpuOrMemoryPoints) return null
    return loaded.results[PodSeriesKind.Cpu] as? SeriesResult.Failed ?: loaded.results[PodSeriesKind.Memory] as? SeriesResult.Failed
}

@Composable
private fun LoadingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BusyIndicator(modifier = Modifier.size(12.dp), color = KdTextSecondary, strokeWidth = 1.5.dp)
        Text(LOADING_HISTORY_NOTE, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
    }
}

@Composable
private fun NoteLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
}

// Whatever a source throws becomes a failed chart: an exception would end the refresh loop and
// reach the window's handler, and its message is never shown or logged.
private suspend fun podSetSeriesOrFailed(
    source: MetricsHistorySource,
    kind: PodSeriesKind,
    pods: PodSetRef,
    mode: PodSetMode,
    window: QueryWindow,
): SeriesResult = try {
    source.podSetSeries(kind, pods, mode, window)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    SeriesResult.Failed(HistoryFailure.Unavailable, "error")
}

private val MutedLine get() = KdTextSecondary.copy(alpha = 0.5f)

/** Names of the series that belong to the newest ReplicaSet; none when the Deployment has no current one. */
private fun currentNames(result: SeriesResult?, currentPrefix: String?): List<String> = (result as? SeriesResult.Ok)?.series.orEmpty()
    .map { it.name }
    .filter { currentPrefix != null && it.startsWith(currentPrefix) }

/**
 * One line per pod: those of the newest ReplicaSet coloured by [colorIndex] and ordered by peak,
 * highest first, then the pods of older ReplicaSets as muted lines.
 */
internal fun perPodChartSeries(result: SeriesResult?, currentPrefix: String?, colorIndex: Map<String, Int>): List<ChartSeries> {
    val (current, earlier) = (result as? SeriesResult.Ok)?.series.orEmpty()
        .partition { currentPrefix != null && it.name.startsWith(currentPrefix) }
    val coloured = current
        .sortedByDescending { s -> s.points.maxOfOrNull { it.value } ?: 0.0 }
        .map { s -> ChartSeries(s.name, ClusterColor(hue = podSetHue(colorIndex[s.name] ?: 0)).composeColor, s.points) }
    return coloured + earlier.map { s -> ChartSeries(s.name, MutedLine, s.points, muted = true) }
}

internal const val TOTAL_SERIES_NAME = "All pods"

/**
 * The sum over every pod as one line; none when the answer has no series. Named, because the rollout
 * markers give the chart a legend, and an unnamed line would show there as a bare swatch.
 */
internal fun totalChartSeries(result: SeriesResult?): List<ChartSeries> = listOfNotNull(
    (result as? SeriesResult.Ok)?.series?.firstOrNull()?.let { ChartSeries(TOTAL_SERIES_NAME, KdPrimary, it.points) },
)

/** How many current pods exceed the coloured lines a chart draws, counted over CPU and memory. */
internal fun notDrawnCurrentPods(results: Map<PodSeriesKind, SeriesResult>, currentPrefix: String?): Int {
    val names = listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory).flatMap { currentNames(results[it], currentPrefix) }.distinct()
    return (names.size - MAX_CHART_SERIES).coerceAtLeast(0)
}

/**
 * Request and limit guide lines for one pod, summed over its containers: a value is omitted when
 * any container lacks it, and a pod that lists no containers has none.
 */
internal fun podSumReferences(pod: PodInfo?, kind: PodSeriesKind): List<ChartReference> {
    val containers = pod?.resources.orEmpty()
    if (containers.isEmpty()) return emptyList()

    fun sum(pick: (ContainerResources) -> Long?): Long? {
        val values = containers.map(pick)
        return if (values.any { it == null }) null else values.sumOf { it ?: 0L }
    }
    return when (kind) {
        PodSeriesKind.Cpu -> listOfNotNull(
            sum { it.cpuRequestMillis }?.let { ChartReference("request", it / 1000.0, dashed = true) },
            sum { it.cpuLimitMillis }?.let { ChartReference("limit", it / 1000.0, dashed = false) },
        )

        PodSeriesKind.Memory -> listOfNotNull(
            sum { it.memoryRequestBytes }?.let { ChartReference("request", it.toDouble(), dashed = true) },
            sum { it.memoryLimitBytes }?.let { ChartReference("limit", it.toDouble(), dashed = false) },
        )

        else -> emptyList()
    }
}

@Composable
private fun DeploymentHistoryCharts(
    choice: SourceChoice,
    podSet: DeploymentPodSet,
    mode: PodSetMode,
    kinds: List<PodSeriesKind>,
    referencePod: PodInfo?,
    loaded: LoadedHistory,
) {
    val results = loaded.results
    val currentPrefix = podSet.currentReplicaSet?.let(PromQl::podPrefix)
    // Sorted over every current pod of the three per-pod charts, so a pod keeps its colour in each of
    // them and across refreshes.
    val colorIndex = containerColorIndex(
        listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.Throttling).flatMap { currentNames(results[it], currentPrefix) },
    )

    fun usage(kind: PodSeriesKind): List<ChartSeries> = when (mode) {
        PodSetMode.PerPod -> perPodChartSeries(results[kind], currentPrefix, colorIndex)
        PodSetMode.Total -> totalChartSeries(results[kind])
    }

    fun noteOf(result: SeriesResult?): String? = (result as? SeriesResult.Failed)?.let(::shortFailure)

    fun references(kind: PodSeriesKind): List<ChartReference> = if (mode == PodSetMode.PerPod) podSumReferences(referencePod, kind) else emptyList()

    val markers = podSet.rollouts.map { ChartMarker(it.createdEpochSec, "rev ${it.revision}") }

    val rx = results[PodSeriesKind.NetworkRx]
    val tx = results[PodSeriesKind.NetworkTx]
    val networkNote = when {
        PodSeriesKind.NetworkRx !in kinds -> HOST_NETWORK_NOTE
        else -> noteOf(rx) ?: noteOf(tx)
    }
    val network = if (networkNote != null) {
        emptyList()
    } else {
        listOf(
            ChartSeries("Received", KdPrimary, firstPoints(rx)),
            ChartSeries("Sent", KdSeriesMemory, firstPoints(tx)),
        )
    }

    val throttling = results[PodSeriesKind.Throttling]
    val throttlingNote = when {
        PodSeriesKind.Throttling !in kinds -> NO_CURRENT_CPU_LIMIT_NOTE
        throttling is SeriesResult.Ok && throttling.series.isEmpty() -> NO_THROTTLING_DATA_NOTE
        else -> noteOf(throttling)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeSeriesChart(
                title = "CPU",
                series = usage(PodSeriesKind.Cpu),
                window = loaded.window,
                formatValue = ::formatCoresValue,
                modifier = Modifier.weight(1f),
                references = references(PodSeriesKind.Cpu),
                note = noteOf(results[PodSeriesKind.Cpu]),
                markers = markers,
            )
            TimeSeriesChart(
                title = "Memory",
                series = usage(PodSeriesKind.Memory),
                window = loaded.window,
                formatValue = ::formatBytesValue,
                modifier = Modifier.weight(1f),
                references = references(PodSeriesKind.Memory),
                note = noteOf(results[PodSeriesKind.Memory]),
                markers = markers,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeSeriesChart(
                title = "Network",
                series = network,
                window = loaded.window,
                formatValue = ::formatRateValue,
                modifier = Modifier.weight(1f),
                note = networkNote,
                markers = markers,
            )
            TimeSeriesChart(
                title = "CPU throttling",
                series = if (throttlingNote == null) usage(PodSeriesKind.Throttling) else emptyList(),
                window = loaded.window,
                formatValue = ::formatFractionValue,
                modifier = Modifier.weight(1f),
                note = throttlingNote,
                markers = markers,
            )
        }
        val notDrawn = if (mode == PodSetMode.PerPod) notDrawnCurrentPods(results, currentPrefix) else 0
        if (notDrawn > 0) {
            val pods = if (notDrawn == 1) "1 more pod is" else "$notDrawn more pods are"
            NoteLine("$pods not drawn — switch to Total.")
        }
        if (podSet.podNamesCut) NoteLine(CUT_POD_NAMES_NOTE)
        if (choice is SourceChoice.Gmp) NoteLine(GMP_STALENESS_NOTE)
    }
}

private fun firstPoints(result: SeriesResult?): List<SeriesPoint> = (result as? SeriesResult.Ok)?.series?.firstOrNull()?.points.orEmpty()
