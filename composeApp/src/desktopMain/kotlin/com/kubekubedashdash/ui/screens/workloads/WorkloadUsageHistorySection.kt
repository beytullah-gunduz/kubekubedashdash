package com.kubekubedashdash.ui.screens.workloads

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
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.ceilDiv
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

// The pod panel's note says "set"; here no current pod may be loaded at all (scaled to 0, another namespace selected).
internal const val NO_CURRENT_CPU_LIMIT_NOTE = "No current pod has a CPU limit — CFS throttling does not apply."

internal const val GMP_STALENESS_NOTE =
    "Managed Prometheus keeps reporting a deleted pod for a few minutes (memory up to 5, CPU for its rate window, at least 5), so earlier pods' lines can run past a rollout marker, and Total counts them twice there."

internal const val TOTAL_SERIES_NAME = "All pods"

internal const val DRIVER_SERIES_NAME = "Driver"

internal const val EXECUTORS_SERIES_NAME = "Executors"

internal const val EXECUTORS_MEAN_SERIES_NAME = "Executors (mean)"

// Every pod name is its ReplicaSet's prefix plus 5 generated characters.
private const val POD_SUFFIX_LENGTH = 5

/** The time span a usage section charts. */
internal sealed interface UsageWindow {
    /** A Job's or SparkApplication's run: [startSec] to [endSec], null while it is still going. */
    data class Run(val startSec: Long, val endSec: Long?) : UsageWindow

    /** The runs of a CronJob or ScheduledSparkApplication: from [startSec], the oldest known run's start, to the present. */
    data class Runs(val startSec: Long) : UsageWindow

    /** The last [range]. */
    data class Sliding(val range: HistoryRange) : UsageWindow
}

/** How the pods of a set are drawn in the per-pod view; plain data, so equal targets are equal. */
internal sealed interface UsageGrouping {
    /**
     * One line per pod: those whose name starts with one of [currentPrefixes] in colour (null: every pod),
     * the others as muted lines behind them.
     */
    data class PerPod(val currentPrefixes: Set<String>?) : UsageGrouping {
        fun isCurrent(podName: String): Boolean = currentPrefixes == null || currentPrefixes.any { podName.startsWith(it) }
    }

    /** Two lines: the sum over the [driverPods] and the sum (the mean, for throttling) over the other pods. */
    data class DriverExecutors(val driverPods: Set<String>) : UsageGrouping
}

/**
 * What [WorkloadUsageHistorySection] charts. Every field is a value, and the section keys its state on the
 * fields rather than on the target, because [currentPods] is rebuilt on every informer tick.
 *
 * @property key identity of the workload: the section's state starts over when it changes.
 * @property podSet the pods to chart; null when none can be named.
 * @property missingNote what the section says instead of the charts when [podSet] is null.
 * @property defaultWindow the window shown first; a [UsageWindow.Run] or [UsageWindow.Runs] also puts a
 * [runLabel] entry first in the window menu.
 * @property markers vertical marks across the charts, such as rollouts or run starts.
 * @property currentPods the live pods of the workload: they decide which charts apply (network, throttling)
 * and carry the request and limit lines.
 * @property notes lines under the charts, after the count of pods left out and before the short-run and
 * staleness notes.
 * @property runLabel the menu entry and button label of a [UsageWindow.Run] or [UsageWindow.Runs] default.
 * @property perPodDescription the "Per pod" toggle's tooltip.
 * @property noCpuLimitNote what the CPU throttling chart says when no pod can be throttled.
 * @property stalenessNote the note under the charts on Managed Prometheus.
 * @property throttlingWithoutLivePods whether CPU throttling is charted while no live pod is known: the pods
 * of a finished run are gone, though their limits applied.
 * @property defaultMode the mode shown first; the section's choice starts over when [key] changes.
 * @property totalReferences the guide lines of each chart in the Total mode (the Per pod mode draws the
 * request and limit lines of [currentPods] instead).
 * @property networkExcludedNote when set, Network is not charted and its chart shows this note instead.
 */
internal data class UsageTarget(
    val key: String,
    val podSet: PodSetRef?,
    val missingNote: String,
    val grouping: UsageGrouping,
    val defaultWindow: UsageWindow,
    val markers: List<ChartMarker>,
    val currentPods: List<PodInfo>,
    val notes: List<String>,
    val runLabel: String,
    val perPodDescription: String,
    val noCpuLimitNote: String = NO_CURRENT_CPU_LIMIT_NOTE,
    val stalenessNote: String = GMP_STALENESS_NOTE,
    val throttlingWithoutLivePods: Boolean = false,
    val defaultMode: PodSetMode = PodSetMode.PerPod,
    val totalReferences: Map<PodSeriesKind, List<ChartReference>> = emptyMap(),
    val networkExcludedNote: String? = null,
)

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

/**
 * The charts of a target: [deploymentKinds] of its live pods, or every kind when it has none and
 * [throttlingWithoutLivePods] (a run whose pods are gone), since their limits cannot be read.
 */
internal fun usageKinds(currentPods: List<PodInfo>, throttlingWithoutLivePods: Boolean): List<PodSeriesKind> = if (throttlingWithoutLivePods && currentPods.isEmpty()) {
    PodSeriesKind.entries.toList()
} else {
    deploymentKinds(currentPods)
}

// A per-pod network chart would need two lines per pod, so network is always the sum.
private fun modeFor(kind: PodSeriesKind, mode: PodSetMode): PodSetMode = when (kind) {
    PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx -> PodSetMode.Total
    else -> mode
}

// The longest fixed range: a run that lasted longer shows its last week instead of a window of months.
private val MAX_RUN_WINDOW_SEC = HistoryRange.D7.seconds

/**
 * The window to query for [window] at [nowSec]. A run window is the run (its last week, when longer) plus a
 * margin on both sides, which reaches into the future for a run that is going or has just ended. It then
 * ends at [nowSec], aligned down to its step: it moves every step until the margin is past, and each
 * refresh asks again.
 */
internal fun queryWindowOf(window: UsageWindow, nowSec: Long): QueryWindow = when (window) {
    is UsageWindow.Sliding -> window.range.window(nowSec)
    is UsageWindow.Run -> runWindow(window.startSec, window.endSec ?: nowSec, nowSec)
    is UsageWindow.Runs -> runWindow(window.startSec, nowSec, nowSec)
}

private fun runWindow(fromSec: Long, toSec: Long, nowSec: Long): QueryWindow = untilNow(QueryWindow.spanning(maxOf(fromSec, toSec - MAX_RUN_WINDOW_SEC), toSec), nowSec)

// A run that starts after this machine's clock (skew) keeps its window in the future rather than ending it before it starts.
private fun untilNow(window: QueryWindow, nowSec: Long): QueryWindow {
    if (window.endEpochSec <= nowSec) return window
    val end = nowSec - nowSec % window.stepSec
    return if (end > window.startEpochSec) window.copy(endEpochSec = end) else window
}

/**
 * The note for a [UsageWindow.Run] shorter than the window CPU is averaged over on [choice]'s Prometheus,
 * measured on the run alone whichever window is shown; null for anything else.
 */
internal fun shortRunNote(choice: SourceChoice, window: UsageWindow, nowSec: Long, stepSec: Long): String? {
    val run = window as? UsageWindow.Run ?: return null
    val rateWindowSec = when (choice) {
        is SourceChoice.Gmp -> PromQl.rateWindowSec(stepSec)
        is SourceChoice.InCluster -> PromQl.inClusterRateWindowSec(stepSec)
        else -> return null
    }
    if ((run.endSec ?: nowSec) - run.startSec >= rateWindowSec) return null
    return "This run is shorter than the ${ceilDiv(rateWindowSec, 60)}-minute window CPU is averaged over, so its CPU line is spread out and below the real peak."
}

/**
 * The sentence shown instead of the charts when the history source itself failed; never a raw
 * response. It names only what the validated target holds (project, cluster, namespace, service).
 * Unlike the pod panel's text it never points at live metrics-server data: a workload panel has none.
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
 * A workload's resource usage over time from the cluster's metrics-history source (Managed Prometheus or
 * a Prometheus behind a Service, the demo cluster's included), refreshed every [refreshMs]: CPU, memory,
 * network and CPU throttling of the pods [target] selects. Per pod draws one line per pod, the current
 * ones in colour and the others as grey lines; Driver + executors draws one line for the driver and one
 * for the executors; Total draws the sum. Network is always the sum. There is no live chart to fall back
 * on: without a source nothing is shown, and while the source answers a line says so.
 *
 * The window menu lists the target's run first when its default window is a run. A run window follows
 * the run and is final once the run and its margin are past; a final answer is kept even when it has no
 * points, where a sliding window asks again for as long as it has nothing to draw.
 *
 * Staleness differs by backend. Managed Prometheus writes no staleness markers, so a deleted pod's
 * memory line lingers up to 5 minutes and its CPU line up to the rate window (5 minutes at least); lines
 * can run past the marker of the rollout that replaced them and Total counts those pods twice there. An
 * in-cluster Prometheus marks a vanished series stale, so its lines end with the pod. The section says so
 * under the charts on Managed Prometheus.
 */
@Composable
internal fun WorkloadUsageHistorySection(
    target: UsageTarget,
    service: MetricsHistoryService,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshMs: Long = HISTORY_REFRESH_MS,
) {
    val settings by service.settings.collectAsState()
    val detection by service.detectionState.collectAsState()
    // Not keyed on the live context: a panel first composed while disconnected shows nothing until
    // the settings, the detection or the workload change.
    val choice = remember(settings, detection, target.key, target.podSet?.namespace) { service.choice() }
    val source = remember(choice) { service.sourceFor(choice) }
    // The search lives in the detection's own scope, so leaving the panel never cancels it, and its
    // answer flips [choice]. Keyed on the detection states too: "Search again" during a search
    // abandons it and leaves Detecting equal to itself, so the choice alone would never start the
    // next one. ensureDetected joins a search under way and does nothing once there is an answer.
    LaunchedEffect(choice, detection) {
        if (choice is SourceChoice.Detecting) service.ensureDetected()
    }
    // The range the user picked; null keeps the target's own window, read fresh on every composition, so a run
    // that gains its end, or a run window that replaces the fallback range once the Jobs or runs load, takes over.
    var selected by remember(target.key) { mutableStateOf<HistoryRange?>(null) }
    var mode by remember(target.key) { mutableStateOf(target.defaultMode) }
    val window = selected?.let { UsageWindow.Sliding(it) } ?: target.defaultWindow
    val shownRange = (window as? UsageWindow.Sliding)?.range
    val kinds = usageKinds(target.currentPods, target.throttlingWithoutLivePods).let { all ->
        if (target.networkExcludedNote != null) all - PodSeriesKind.NetworkRx - PodSeriesKind.NetworkTx else all
    }
    val podSet = target.podSet
    // Keyed on what the user picks and on the workload's identity, not on the pod set, the kinds or the run
    // window: those move while a workload runs (an executor or a run appears, a Job gains its end), and the
    // charts keep the last answer while the next one is fetched.
    var loaded by remember(target.key, mode, choice, selected) { mutableStateOf<LoadedHistory?>(null) }
    // What [loaded] answered, so a changed pod set or kinds ask again even when the window did not move.
    var answeredFor by remember(target.key, mode, choice, selected) { mutableStateOf<Pair<PodSetRef, List<PodSeriesKind>>?>(null) }

    if (source != null && podSet != null) {
        // [selected] is a key too: [loaded] starts over with it, so the effect has to restart onto the new state even when the window is the same.
        LaunchedEffect(target.key, selected, window, mode, choice, podSet, kinds) {
            while (true) {
                val now = nowEpochSec()
                val queryWindow = queryWindowOf(window, now)
                // A run's window stops moving once the run and its margin are past: asking again would only
                // repeat an answer, an empty one included. A sliding window asks again while it has nothing to draw.
                val finalWhenAnswered = window !is UsageWindow.Sliding && queryWindow.endEpochSec <= now
                // A 24 h window moves only every 720 s: refetching an identical, fully answered one wastes quota.
                if (answeredFor != (podSet to kinds) || needsFetch(loaded, queryWindow, finalWhenAnswered)) {
                    loaded = LoadedHistory(
                        queryWindow,
                        coroutineScope {
                            kinds.map { k -> k to async { podSetSeriesOrFailed(source, k, podSet, modeFor(k, mode), queryWindow) } }
                                .associate { (k, deferred) -> k to deferred.await() }
                        },
                    )
                    answeredFor = podSet to kinds
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
            // wider type), never the mode toggles or the window menu.
            Text(
                source?.label.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = KdTextSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            val (splitLabel, splitDescription) = when (target.grouping) {
                is UsageGrouping.PerPod -> "Per pod" to target.perPodDescription
                is UsageGrouping.DriverExecutors -> "Driver + executors" to "One line for the driver, one for the sum of the executors."
            }
            LogToolbarToggle(
                label = splitLabel,
                on = mode == PodSetMode.PerPod,
                onToggle = { mode = PodSetMode.PerPod },
                description = splitDescription,
            )
            Spacer(Modifier.width(4.dp))
            LogToolbarToggle(
                label = "Total",
                on = mode == PodSetMode.Total,
                onToggle = { mode = PodSetMode.Total },
                description = "One line: the sum over every pod.",
            )
            Spacer(Modifier.width(8.dp))
            val rangeItems = HistoryRange.entries.map { option -> LogToolbarMenuItem(option.label, option == shownRange) { selected = option } }
            LogToolbarMenu(
                label = "${shownRange?.label ?: target.runLabel} ▾",
                items = if (target.defaultWindow is UsageWindow.Sliding) {
                    rangeItems
                } else {
                    listOf(LogToolbarMenuItem(target.runLabel, selected == null) { selected = null }) + rangeItems
                },
                description = "Time window of the charts.",
            )
        }
        val answer = loaded
        val failed = answer?.let(::headlineFailure)
        when {
            choice is SourceChoice.Detecting -> LoadingLine()

            podSet == null -> NoteLine(target.missingNote)

            answer == null -> LoadingLine()

            failed != null -> NoteLine(deploymentFailureMessage(choice, failed))

            else -> WorkloadHistoryCharts(
                choice = choice,
                target = target,
                mode = mode,
                // The kinds the shown answer was fetched for: while the next one loads, a kind just added
                // would otherwise draw an empty chart.
                kinds = answeredFor?.second ?: kinds,
                loaded = answer,
                shortRun = shortRunNote(choice, target.defaultWindow, nowEpochSec(), answer.window.stepSec),
            )
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

private fun prefixMatcher(currentPrefix: String?): (String) -> Boolean = { name -> currentPrefix != null && name.startsWith(currentPrefix) }

/** Names of the series that belong to the current pods. */
private fun currentNames(result: SeriesResult?, isCurrent: (String) -> Boolean): List<String> = (result as? SeriesResult.Ok)?.series.orEmpty()
    .map { it.name }
    .filter(isCurrent)

/** Names of the series that belong to the newest ReplicaSet; none when the Deployment has no current one. */
private fun currentNames(result: SeriesResult?, currentPrefix: String?): List<String> = currentNames(result, prefixMatcher(currentPrefix))

/**
 * One line per pod: the current ones coloured by [colorIndex] and ordered by peak, highest first, then
 * the others as muted lines.
 */
internal fun perPodChartSeries(result: SeriesResult?, isCurrent: (String) -> Boolean, colorIndex: Map<String, Int>): List<ChartSeries> {
    val (current, earlier) = (result as? SeriesResult.Ok)?.series.orEmpty().partition { isCurrent(it.name) }
    val coloured = current
        .sortedByDescending { s -> s.points.maxOfOrNull { it.value } ?: 0.0 }
        .map { s -> ChartSeries(s.name, ClusterColor(hue = podSetHue(colorIndex[s.name] ?: 0)).composeColor, s.points) }
    return coloured + earlier.map { s -> ChartSeries(s.name, MutedLine, s.points, muted = true) }
}

/** [perPodChartSeries] where the current pods are those of the newest ReplicaSet, named by [currentPrefix] (none: no pod is current). */
internal fun perPodChartSeries(result: SeriesResult?, currentPrefix: String?, colorIndex: Map<String, Int>): List<ChartSeries> = perPodChartSeries(result, prefixMatcher(currentPrefix), colorIndex)

/**
 * Two lines for a Spark application: the sum over the series named in [driverPods] and the sum over the
 * others, the mean for throttling, which is a ratio. A line is left out when no series feeds it.
 */
internal fun driverExecutorsChartSeries(result: SeriesResult?, driverPods: Set<String>, kind: PodSeriesKind): List<ChartSeries> {
    val (driver, executors) = (result as? SeriesResult.Ok)?.series.orEmpty().partition { it.name in driverPods }
    val executorsName = if (kind == PodSeriesKind.Throttling) EXECUTORS_MEAN_SERIES_NAME else EXECUTORS_SERIES_NAME
    return listOfNotNull(
        driver.takeIf { it.isNotEmpty() }
            ?.let { ChartSeries(DRIVER_SERIES_NAME, ClusterColor(hue = podSetHue(0)).composeColor, pointwise(it, mean = false)) },
        executors.takeIf { it.isNotEmpty() }
            ?.let { ChartSeries(executorsName, ClusterColor(hue = podSetHue(1)).composeColor, pointwise(it, mean = kind == PodSeriesKind.Throttling)) },
    )
}

// Combines series that share their timestamps, adding what is present at each one (or averaging it).
private fun pointwise(series: List<Series>, mean: Boolean): List<SeriesPoint> = series.flatMap { it.points }
    .groupBy({ it.epochSec }, { it.value })
    .toSortedMap()
    .map { (epochSec, values) -> SeriesPoint(epochSec, if (mean) values.average() else values.sum()) }

/**
 * The sum over every pod as one line; none when the answer has no series. Named, because the rollout
 * markers give the chart a legend, and an unnamed line would show there as a bare swatch.
 */
internal fun totalChartSeries(result: SeriesResult?): List<ChartSeries> = listOfNotNull(
    (result as? SeriesResult.Ok)?.series?.firstOrNull()?.let { ChartSeries(TOTAL_SERIES_NAME, KdPrimary, it.points) },
)

/** How many current pods exceed the coloured lines a chart draws, counted over CPU and memory. */
internal fun notDrawnCurrentPods(results: Map<PodSeriesKind, SeriesResult>, isCurrent: (String) -> Boolean): Int {
    val names = listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory).flatMap { currentNames(results[it], isCurrent) }.distinct()
    return (names.size - MAX_CHART_SERIES).coerceAtLeast(0)
}

/** [notDrawnCurrentPods] where the current pods are those of the newest ReplicaSet, named by [currentPrefix]. */
internal fun notDrawnCurrentPods(results: Map<PodSeriesKind, SeriesResult>, currentPrefix: String?): Int = notDrawnCurrentPods(results, prefixMatcher(currentPrefix))

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
private fun WorkloadHistoryCharts(
    choice: SourceChoice,
    target: UsageTarget,
    mode: PodSetMode,
    kinds: List<PodSeriesKind>,
    loaded: LoadedHistory,
    shortRun: String?,
) {
    val results = loaded.results
    val grouping = target.grouping
    val perPod = grouping as? UsageGrouping.PerPod
    val isCurrent: (String) -> Boolean = { name -> perPod?.isCurrent(name) ?: true }
    // Sorted over every current pod of the three per-pod charts, so a pod keeps its colour in each of
    // them and across refreshes.
    val colorIndex = containerColorIndex(
        listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.Throttling).flatMap { currentNames(results[it], isCurrent) },
    )

    fun usage(kind: PodSeriesKind): List<ChartSeries> = when {
        mode == PodSetMode.Total -> totalChartSeries(results[kind])
        grouping is UsageGrouping.DriverExecutors -> driverExecutorsChartSeries(results[kind], grouping.driverPods, kind)
        else -> perPodChartSeries(results[kind], isCurrent, colorIndex)
    }

    fun noteOf(result: SeriesResult?): String? = (result as? SeriesResult.Failed)?.let(::shortFailure)

    // The first current pod stands for the rest; a Spark application has none, as its executors differ from its driver.
    fun references(kind: PodSeriesKind): List<ChartReference> = when {
        mode == PodSetMode.Total -> target.totalReferences[kind].orEmpty()
        perPod != null -> podSumReferences(target.currentPods.firstOrNull(), kind)
        else -> emptyList()
    }

    val markers = target.markers

    val rx = results[PodSeriesKind.NetworkRx]
    val tx = results[PodSeriesKind.NetworkTx]
    val networkNote = when {
        PodSeriesKind.NetworkRx !in kinds -> target.networkExcludedNote ?: HOST_NETWORK_NOTE
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
        PodSeriesKind.Throttling !in kinds -> target.noCpuLimitNote
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
        val notDrawn = if (perPod != null && mode == PodSetMode.PerPod) notDrawnCurrentPods(results, isCurrent) else 0
        if (notDrawn > 0) {
            val pods = if (notDrawn == 1) "1 more pod is" else "$notDrawn more pods are"
            NoteLine("$pods not drawn — switch to Total.")
        }
        target.notes.forEach { NoteLine(it) }
        if (shortRun != null) NoteLine(shortRun)
        if (choice is SourceChoice.Gmp) NoteLine(target.stalenessNote)
    }
}

private fun firstPoints(result: SeriesResult?): List<SeriesPoint> = (result as? SeriesResult.Ok)?.series?.firstOrNull()?.points.orEmpty()
