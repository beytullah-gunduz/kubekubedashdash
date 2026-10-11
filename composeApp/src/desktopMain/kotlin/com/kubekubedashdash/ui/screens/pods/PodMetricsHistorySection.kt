package com.kubekubedashdash.ui.screens.pods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSeriesMemory
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.NoSourceReason
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.components.ChartReference
import com.kubekubedashdash.ui.components.ChartSeries
import com.kubekubedashdash.ui.components.LogToolbarMenu
import com.kubekubedashdash.ui.components.LogToolbarMenuItem
import com.kubekubedashdash.ui.components.TimeSeriesChart
import com.kubekubedashdash.ui.components.podSetHue
import com.kubekubedashdash.util.formatMemorySize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToLong

internal const val HISTORY_REFRESH_MS = 30_000L
internal const val HOST_NETWORK_NOTE = "Host network — these counters are the node's, not the pod's."
internal const val NO_CPU_LIMIT_NOTE = "No CPU limit set — CFS throttling does not apply."
internal const val NO_THROTTLING_DATA_NOTE = "No throttling data in this range."
internal const val LOADING_HISTORY_NOTE = "Loading history…"
internal const val NO_HISTORY_NOTE = "No history for this pod in this range. Retrying every 30 s; showing live metrics-server data."

/** What the section asks the source for: network is the node's on a host-network pod, throttling needs a CPU limit. */
internal fun historyKinds(pod: PodInfo): List<PodSeriesKind> = buildList {
    add(PodSeriesKind.Cpu)
    add(PodSeriesKind.Memory)
    if (!pod.hostNetwork) {
        add(PodSeriesKind.NetworkRx)
        add(PodSeriesKind.NetworkTx)
    }
    if (pod.resources.any { it.cpuLimitMillis != null }) add(PodSeriesKind.Throttling)
}

/** One completed fetch: the window it asked for and what each kind answered. */
internal data class LoadedHistory(val window: QueryWindow, val results: Map<PodSeriesKind, SeriesResult>) {
    /** Whether CPU or memory has a point to draw; without one the live charts stand in. */
    val hasCpuOrMemoryPoints: Boolean
        get() = listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory).any { kind ->
            (results[kind] as? SeriesResult.Ok)?.series.orEmpty().any { it.points.isNotEmpty() }
        }
}

/**
 * Whether the refresh loop asks again: a new window, or one not fully answered. An empty answer
 * counts as not answered too, so a new pod's history shows up within a refresh even on a 24 h
 * range, whose window only moves every 720 s.
 */
internal fun needsFetch(current: LoadedHistory?, window: QueryWindow): Boolean = current == null ||
    current.window != window ||
    current.results.values.any { it !is SeriesResult.Ok } ||
    !current.hasCpuOrMemoryPoints

/** What the section shows for a source choice and the fetch so far. */
internal sealed interface HistoryView {
    /** No history source: the metrics-server charts, under [hint] when it is not blank. */
    data class LiveOnly(val hint: String) : HistoryView

    /** The first fetch for this pod and range is still out: the metrics-server charts stand in meanwhile. */
    data object Loading : HistoryView

    /** Neither CPU nor memory has a point to draw (both failed, or nothing recorded): [message] explains, the metrics-server charts follow. */
    data class LiveFallback(val message: String) : HistoryView

    data class Charts(val loaded: LoadedHistory) : HistoryView
}

internal fun historyView(choice: SourceChoice, loaded: LoadedHistory?): HistoryView {
    if (choice is SourceChoice.None) {
        return HistoryView.LiveOnly(
            when (choice.reason) {
                NoSourceReason.NotConnected -> ""

                NoSourceReason.Disabled -> "Live data only — metrics history is off for this cluster (Settings → Metrics history)."

                // Auto found no Prometheus to read from, so no line: the section looks exactly as it
                // did before metrics history existed. Settings says why.
                NoSourceReason.NothingFound, NoSourceReason.CannotSearch, NoSourceReason.SearchFailed -> ""

                NoSourceReason.IncompleteSetting ->
                    "Live data only — this cluster's Managed Prometheus settings are incomplete (Settings → Metrics history)."
            },
        )
    }
    if (loaded == null) return HistoryView.Loading
    if (loaded.hasCpuOrMemoryPoints) return HistoryView.Charts(loaded)
    // A failure explains better than "no history": it is what kept the points away.
    val failed = loaded.results[PodSeriesKind.Cpu] as? SeriesResult.Failed
        ?: loaded.results[PodSeriesKind.Memory] as? SeriesResult.Failed
    return HistoryView.LiveFallback(failed?.let { failureMessage(choice, it) } ?: NO_HISTORY_NOTE)
}

/** The sentence shown above the live charts when the history source itself failed; never a raw response. */
internal fun failureMessage(choice: SourceChoice, failed: SeriesResult.Failed): String {
    val t = (choice as? SourceChoice.Gmp)?.target ?: return "Metrics history unavailable (${failed.failure})."
    return when (failed.failure) {
        HistoryFailure.GcloudUnavailable ->
            "Metrics history needs the gcloud CLI, which isn't installed or isn't on the PATH. Showing live metrics-server data."

        HistoryFailure.NotSignedIn ->
            "gcloud couldn't provide an access token — run `gcloud auth login`. Retrying every 30 s; showing live metrics-server data."

        HistoryFailure.Forbidden ->
            "No access to Managed Prometheus data in project ${t.project}: it needs roles/monitoring.viewer there. " +
                "If this cluster's metrics live in a separate monitoring project, set it in Settings → Metrics history. (${failed.detail})"

        HistoryFailure.NoContainerMetrics ->
            "Project ${t.project} has no container metrics for cluster ${t.cluster} (${t.location}). " +
                "Turn them on with `gcloud container clusters update ${t.cluster} --location=${t.location} --monitoring=SYSTEM,CADVISOR,KUBELET` " +
                "— that flag replaces the current list, so add any other packages you use. " +
                "If the metrics live in a separate monitoring project, set it in Settings → Metrics history."

        HistoryFailure.BadQuery -> "Managed Prometheus rejected the query (${failed.detail})."

        HistoryFailure.Unavailable ->
            "Managed Prometheus didn't answer (${failed.detail}). Retrying every 30 s; showing live metrics-server data."
    }
}

/** The one-phrase note a single chart shows when only its own query failed. */
internal fun shortFailure(failed: SeriesResult.Failed): String = when (failed.failure) {
    HistoryFailure.GcloudUnavailable -> "gcloud not found"
    HistoryFailure.NotSignedIn -> "gcloud not signed in"
    HistoryFailure.Forbidden -> "No access"
    HistoryFailure.NoContainerMetrics -> "No data"
    HistoryFailure.BadQuery -> "Query rejected (${failed.detail})"
    HistoryFailure.Unavailable -> "Not answering (${failed.detail})"
}

/** Sorted, distinct names to 0, 1, 2…: a container keeps its colour in every chart and across refreshes. */
internal fun containerColorIndex(names: List<String>): Map<String, Int> = names.distinct().sorted().withIndex().associate { (index, name) -> name to index }

internal fun formatCoresValue(cores: Double): String = if (cores < 1.0) {
    "${(cores * 1000).roundToLong()}m"
} else {
    String.format(Locale.ROOT, "%.2f cores", cores)
}

internal fun formatBytesValue(bytes: Double): String = formatMemorySize(bytes.roundToLong())

internal fun formatRateValue(bytesPerSecond: Double): String = formatBytesValue(bytesPerSecond) + "/s"

internal fun formatFractionValue(fraction: Double): String = String.format(Locale.ROOT, "%.0f%%", fraction * 100)

/**
 * Request and limit guide lines for a chart that shows one container, taken from that container's
 * resources; none when several lines share the chart or the container is unknown.
 */
internal fun usageReferences(pod: PodInfo, series: List<ChartSeries>, kind: PodSeriesKind): List<ChartReference> {
    val container = series.singleOrNull()?.let { only -> pod.resources.firstOrNull { it.name == only.name } } ?: return emptyList()
    return when (kind) {
        PodSeriesKind.Cpu -> listOfNotNull(
            container.cpuRequestMillis?.let { ChartReference("request", it / 1000.0, dashed = true) },
            container.cpuLimitMillis?.let { ChartReference("limit", it / 1000.0, dashed = false) },
        )

        PodSeriesKind.Memory -> listOfNotNull(
            container.memoryRequestBytes?.let { ChartReference("request", it.toDouble(), dashed = true) },
            container.memoryLimitBytes?.let { ChartReference("limit", it.toDouble(), dashed = false) },
        )

        else -> emptyList()
    }
}

/**
 * Pod resource usage over time from the cluster's metrics-history source (Managed Prometheus, or the
 * demo's simulator), refreshed every [refreshMs]. Without a source, while the first fetch is out, or
 * when CPU and memory have nothing to draw, the metrics-server charts of the last few minutes stand
 * in, under a line that says why (none for a cluster Auto cannot give history to).
 */
@Composable
internal fun PodMetricsHistorySection(
    pod: PodInfo,
    service: MetricsHistoryService,
    liveHistory: List<PodMetricsSnapshot>,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshMs: Long = HISTORY_REFRESH_MS,
) {
    val settings by service.settings.collectAsState()
    // Not keyed on the live context: a panel first composed while disconnected stays live-only until
    // the settings or the pod change.
    val choice = remember(settings, pod.namespace, pod.name) { service.choice() }
    val source = remember(choice) { service.sourceFor(choice) }
    var range by remember { mutableStateOf(HistoryRange.H1) }
    val podRef = remember(pod.namespace, pod.name, pod.resources) { PodRef(pod.namespace, pod.name, pod.resources) }
    val kinds = remember(pod.hostNetwork, pod.resources) { historyKinds(pod) }
    // Keyed on the name and namespace too: demo pods can have a blank uid.
    var loaded by remember(pod.uid, pod.namespace, pod.name, range, choice) { mutableStateOf<LoadedHistory?>(null) }

    if (source != null) {
        LaunchedEffect(pod.uid, pod.namespace, pod.name, range, choice) {
            while (true) {
                val window = range.window(nowEpochSec())
                val current = loaded
                // A 24 h window moves only every 720 s: refetching an identical, fully answered one wastes quota.
                if (needsFetch(current, window)) {
                    loaded = LoadedHistory(
                        window,
                        coroutineScope {
                            kinds.map { k -> k to async { podSeriesOrFailed(source, k, podRef, window) } }
                                .associate { (k, deferred) -> k to deferred.await() }
                        },
                    )
                }
                delay(refreshMs)
            }
        }
    }

    when (val view = historyView(choice, loaded)) {
        // The live charts carry their own "Resource Usage" title, so the hint goes under it.
        is HistoryView.LiveOnly -> if (liveHistory.isNotEmpty()) {
            PodMetricsSection(liveHistory, note = view.hint.ifBlank { null })
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Resource Usage")
                Spacer(Modifier.weight(1f))
                Text(source?.label.orEmpty(), style = MaterialTheme.typography.labelSmall, color = KdTextSecondary)
                Spacer(Modifier.width(8.dp))
                LogToolbarMenu(
                    label = "${range.label} ▾",
                    items = HistoryRange.entries.map { option -> LogToolbarMenuItem(option.label, option == range) { range = option } },
                    description = "Time window of the charts.",
                )
            }
            when (view) {
                // A slow or hanging source (gcloud, the network) must not hide the live charts.
                HistoryView.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BusyIndicator(modifier = Modifier.size(12.dp), color = KdTextSecondary, strokeWidth = 1.5.dp)
                        Text(LOADING_HISTORY_NOTE, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                    }
                    if (liveHistory.isNotEmpty()) PodMetricsSection(liveHistory, showTitle = false)
                }

                // The header stays, so a range that keeps failing can still be changed; the live
                // charts follow without their own title.
                is HistoryView.LiveFallback -> {
                    Text(view.message, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                    if (liveHistory.isNotEmpty()) PodMetricsSection(liveHistory, showTitle = false)
                }

                is HistoryView.Charts -> HistoryCharts(pod, kinds, view.loaded)

                is HistoryView.LiveOnly -> Unit
            }
        }
    }
}

// Whatever a source throws becomes a failed chart: an exception would end the refresh loop and
// reach the window's handler, and its message is never shown or logged.
private suspend fun podSeriesOrFailed(source: MetricsHistorySource, kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult = try {
    source.podSeries(kind, pod, window)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    SeriesResult.Failed(HistoryFailure.Unavailable, "error")
}

@Composable
private fun HistoryCharts(pod: PodInfo, kinds: List<PodSeriesKind>, loaded: LoadedHistory) {
    val results = loaded.results
    val colorIndex = containerColorIndex(
        pod.resources.map { it.name } +
            listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory, PodSeriesKind.Throttling)
                .flatMap { kind -> (results[kind] as? SeriesResult.Ok)?.series.orEmpty().map { it.name } },
    )

    fun containerSeries(result: SeriesResult?): List<ChartSeries> = (result as? SeriesResult.Ok)?.series.orEmpty().map { s ->
        ChartSeries(s.name, ClusterColor(hue = podSetHue(colorIndex[s.name] ?: 0)).composeColor, s.points)
    }

    fun noteOf(result: SeriesResult?): String? = (result as? SeriesResult.Failed)?.let(::shortFailure)

    val cpu = containerSeries(results[PodSeriesKind.Cpu])
    val memory = containerSeries(results[PodSeriesKind.Memory])

    // Hands the network chart its two lines, or the note that stands in for them.
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
        PodSeriesKind.Throttling !in kinds -> NO_CPU_LIMIT_NOTE
        throttling is SeriesResult.Ok && throttling.series.isEmpty() -> NO_THROTTLING_DATA_NOTE
        else -> noteOf(throttling)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeSeriesChart(
                title = "CPU",
                series = cpu,
                window = loaded.window,
                formatValue = ::formatCoresValue,
                modifier = Modifier.weight(1f),
                references = usageReferences(pod, cpu, PodSeriesKind.Cpu),
                note = noteOf(results[PodSeriesKind.Cpu]),
            )
            TimeSeriesChart(
                title = "Memory",
                series = memory,
                window = loaded.window,
                formatValue = ::formatBytesValue,
                modifier = Modifier.weight(1f),
                references = usageReferences(pod, memory, PodSeriesKind.Memory),
                note = noteOf(results[PodSeriesKind.Memory]),
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
            )
            TimeSeriesChart(
                title = "CPU throttling",
                series = if (throttlingNote == null) containerSeries(throttling) else emptyList(),
                window = loaded.window,
                formatValue = ::formatFractionValue,
                modifier = Modifier.weight(1f),
                note = throttlingNote,
            )
        }
    }
}

private fun firstPoints(result: SeriesResult?) = (result as? SeriesResult.Ok)?.series?.firstOrNull()?.points.orEmpty()
