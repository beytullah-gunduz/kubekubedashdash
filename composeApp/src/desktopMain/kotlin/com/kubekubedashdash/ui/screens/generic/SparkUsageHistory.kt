package com.kubekubedashdash.ui.screens.generic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.services.metricshistory.SparkGrouping
import com.kubekubedashdash.services.metricshistory.isTransient
import com.kubekubedashdash.ui.ClusterColor
import com.kubekubedashdash.ui.components.ChartAreaTransition
import com.kubekubedashdash.ui.components.ChartSeries
import com.kubekubedashdash.ui.components.HistoryLoadingLine
import com.kubekubedashdash.ui.components.HistoryRetryLine
import com.kubekubedashdash.ui.components.LogToolbarMenu
import com.kubekubedashdash.ui.components.LogToolbarMenuItem
import com.kubekubedashdash.ui.components.TimeSeriesChart
import com.kubekubedashdash.ui.components.podSetHue
import com.kubekubedashdash.ui.screens.cluster.scopeRefOf
import com.kubekubedashdash.ui.screens.cluster.usageSectionsSideBySide
import com.kubekubedashdash.ui.screens.pods.HISTORY_REFRESH_MS
import com.kubekubedashdash.ui.screens.pods.formatBytesValue
import com.kubekubedashdash.ui.screens.pods.formatCoresValue
import com.kubekubedashdash.ui.screens.pods.shortFailure
import com.kubekubedashdash.ui.screens.workloads.DRIVER_SERIES_NAME
import com.kubekubedashdash.ui.screens.workloads.EXECUTORS_SERIES_NAME
import com.kubekubedashdash.ui.screens.workloads.TOTAL_SERIES_NAME
import com.kubekubedashdash.ui.screens.workloads.deploymentFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

internal const val SPARK_HISTORY_TITLE = "Spark usage over time"
internal const val SPARK_HISTORY_TOP_APPS = 5
internal const val SPARK_OTHER_SERIES_NAME = "Other"
internal const val SPARK_HISTORY_NOTE =
    "Other: applications no longer listed (a TTL deletes them after their run) and those beyond the five largest. Each point averages several minutes; a pod sampled only once has memory but no CPU."

internal enum class SparkHistoryView(val label: String) {
    Applications("Applications"),
    Roles("Drivers / executors"),
    Total("Total"),
    ;

    /** Applications asks for one series per pod to attribute them; the others only need the two roles. */
    val grouping: SparkGrouping get() = if (this == Applications) SparkGrouping.ByPod else SparkGrouping.ByRole
}

/** Points added up by timestamp, sorted. */
internal fun sumPointwise(series: List<List<SeriesPoint>>): List<SeriesPoint> = series.flatten()
    .groupBy { it.epochSec }
    .map { (epochSec, points) -> SeriesPoint(epochSec, points.sumOf { it.value }) }
    .sortedBy { it.epochSec }

/**
 * One line per listed application among the [SPARK_HISTORY_TOP_APPS] largest (sum of its points), then
 * [SPARK_OTHER_SERIES_NAME] for every other pod, from per-pod series named "<namespace>/<pod>". A pod belongs to the
 * app whose status names it (driver or executor) in its namespace. A line is labelled with the app's name, or
 * "<name> (<namespace>)" when two listed apps share a name. Lines are largest first (ties by label); Other last,
 * absent when empty. A pod named in two apps' status goes to the first of them in [apps] order. An app with no
 * namespace, no spark data, or no attributed point gets no line. A listed app literally named "Other" shares the label.
 */
internal fun sparkApplicationLines(podSeries: List<Series>, apps: List<GenericResourceInfo>): List<Series> {
    // "<namespace>/<pod>" -> index in [apps] of the first application that names the pod.
    val owner = HashMap<String, Int>()
    apps.forEachIndexed { index, app ->
        val namespace = app.namespace?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val spark = app.spark ?: return@forEachIndexed
        (listOf(spark.driverPod) + spark.executorPods).filter { it.isNotBlank() }.forEach { owner.putIfAbsent("$namespace/$it", index) }
    }
    // Over every listed app, not only those with a line, so an app keeps one label on both charts.
    val nameCounts = apps.groupingBy { it.name }.eachCount()

    val perApp = LinkedHashMap<Int, MutableList<List<SeriesPoint>>>()
    val unattributed = mutableListOf<List<SeriesPoint>>()
    podSeries.filter { it.points.isNotEmpty() }.forEach { series ->
        val index = owner[series.name]
        if (index == null) unattributed += series.points else perApp.getOrPut(index) { mutableListOf() } += series.points
    }

    val ranked = perApp.map { (index, lists) ->
        val app = apps[index]
        val label = if ((nameCounts[app.name] ?: 0) > 1) "${app.name} (${app.namespace})" else app.name
        val points = sumPointwise(lists)
        Triple(label, points, points.sumOf { it.value })
    }.sortedWith(compareByDescending<Triple<String, List<SeriesPoint>, Double>> { it.third }.thenBy { it.first })

    val lines = ranked.take(SPARK_HISTORY_TOP_APPS).map { (label, points, _) -> Series(label, points) }
    val other = sumPointwise(unattributed + ranked.drop(SPARK_HISTORY_TOP_APPS).map { it.second })
    return if (other.isEmpty()) lines else lines + Series(SPARK_OTHER_SERIES_NAME, other)
}

/**
 * One colour index per line label across both charts, so an app keeps its colour when it ranks differently on CPU and
 * memory: the CPU labels in order, then the memory-only ones; [SPARK_OTHER_SERIES_NAME] gets the index after them all.
 */
internal fun sparkColorIndex(cpuLines: List<Series>, memoryLines: List<Series>): Map<String, Int> {
    val labels = (cpuLines + memoryLines).map { it.name }.filter { it != SPARK_OTHER_SERIES_NAME }.distinct()
    return labels.withIndex().associate { (index, label) -> label to index } + (SPARK_OTHER_SERIES_NAME to labels.size)
}

/** One completed fetch: the window and view it asked for and what each kind answered. */
internal data class LoadedSpark(val window: QueryWindow, val view: SparkHistoryView, val results: Map<ScopeKind, SeriesResult>) {
    /** Whether CPU or memory has a point to draw. */
    val hasPoints: Boolean
        get() = results.values.any { result -> (result as? SeriesResult.Ok)?.series.orEmpty().any { it.points.isNotEmpty() } }
}

/** Whether the refresh loop asks again; the same rule as the Overview card's. */
internal fun needsSparkFetch(current: LoadedSpark?, window: QueryWindow, view: SparkHistoryView): Boolean = current == null ||
    current.window != window ||
    current.view != view ||
    current.results.values.any { it !is SeriesResult.Ok } ||
    !current.hasPoints

/** The failure that kept CPU and memory from drawing anything, CPU's first; null when either has a point or neither failed. */
private fun sparkHeadlineFailure(loaded: LoadedSpark): SeriesResult.Failed? {
    if (loaded.hasPoints) return null
    return loaded.results[ScopeKind.CpuCores] as? SeriesResult.Failed ?: loaded.results[ScopeKind.MemoryBytes] as? SeriesResult.Failed
}

/**
 * The Spark pods' CPU and memory over time for the namespace selection, from the cluster's metrics-history
 * source, refreshed every [refreshMs] while the card is composed and [expanded]. [apps] (the screen's listed
 * SparkApplications) only attribute the pods to applications at render: it is not a fetch key, as the list
 * re-emits on every cluster tick. Without a source the card is absent, [modifier] (the screen's padding) included.
 */
@Composable
internal fun SparkUsageHistoryCard(
    service: MetricsHistoryService,
    namespaceScope: NamespaceScope,
    apps: List<GenericResourceInfo>,
    expanded: Boolean,
    onToggle: () -> Unit,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshMs: Long = HISTORY_REFRESH_MS,
    modifier: Modifier = Modifier,
) {
    val settings by service.settings.collectAsState()
    val detection by service.detectionState.collectAsState()
    val choice = remember(settings, detection, namespaceScope) { service.choice() }
    val source = remember(choice) { service.sourceFor(choice) }
    // Keyed on the detection states too, as the Overview card is: "Search again" leaves Detecting equal to itself.
    LaunchedEffect(choice, detection) {
        if (choice is SourceChoice.Detecting) service.ensureDetected()
    }
    var range by remember { mutableStateOf(HistoryRange.H6) }
    var view by remember { mutableStateOf(SparkHistoryView.Applications) }
    val scopeRef = remember(namespaceScope) { scopeRefOf(namespaceScope) }
    // Outlives a collapse: expanding again finds the last answer instead of a loading line.
    // Retry bumps [retry], a key of both this state and the fetch below: the key change cancels the
    // fetch in flight and starts a fresh one, with no imperative reset.
    var retry by remember { mutableIntStateOf(0) }
    var loaded by remember(namespaceScope, range, choice, view, retry) { mutableStateOf<LoadedSpark?>(null) }

    if (expanded && source != null) {
        LaunchedEffect(namespaceScope, range, choice, view, retry) {
            while (true) {
                val window = range.window(nowEpochSec())
                if (needsSparkFetch(loaded, window, view)) {
                    loaded = LoadedSpark(
                        window,
                        view,
                        coroutineScope {
                            ScopeKind.entries.map { kind -> kind to async { sparkSeriesOrFailed(source, kind, scopeRef, view.grouping, window) } }
                                .associate { (kind, deferred) -> kind to deferred.await() }
                        },
                    )
                }
                delay(refreshMs)
            }
        }
    }

    if (source == null) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = 10.dp.kdCorner,
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(KdBorder)),
    ) {
        Column {
            // The menus are clickables of their own, so opening one does not reach the header's toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Weighted without filling, so in a narrow pane the menus are measured first and the title gives way.
                Text(
                    SPARK_HISTORY_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Takes the rest of the row and gives way first: a Service label can be long.
                Text(
                    source.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = KdTextSecondary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                LogToolbarMenu(
                    label = "${view.label} ▾",
                    items = SparkHistoryView.entries.map { option -> LogToolbarMenuItem(option.label, option == view) { view = option } },
                    description = "What the lines show.",
                )
                Spacer(Modifier.width(8.dp))
                LogToolbarMenu(
                    label = "${range.label} ▾",
                    items = HistoryRange.entries.map { option -> LogToolbarMenuItem(option.label, option == range) { range = option } },
                    description = "Time window of the charts.",
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    painter = painterResource(
                        if (expanded) {
                            Res.drawable.keyboard_arrow_up_filled
                        } else {
                            Res.drawable.keyboard_arrow_down_filled
                        },
                    ),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = KdTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val answer = loaded
                    val failed = answer?.let(::sparkHeadlineFailure)
                    // A structural failure collapses to its message. Anything else keeps the charts, a skeleton
                    // while loading and a failure note in place after a transient one, so the card never resizes.
                    // The values travel in the state: the outgoing content is still drawn after `loaded` moved on.
                    val area = if (failed != null && !failed.isTransient()) {
                        SparkArea.Note(deploymentFailureMessage(choice, failed))
                    } else {
                        SparkArea.Charts(answer, failed)
                    }
                    ChartAreaTransition(area, contentKey = { it::class }) { a ->
                        when (a) {
                            is SparkArea.Note -> NoteLine(a.text)

                            is SparkArea.Charts -> {
                                // The list re-emits often; recomputing the lines here never refetches.
                                val charts = remember(a.answer, apps) {
                                    a.answer?.let { sparkChartSeries(it, apps) } ?: SparkCharts(emptyList(), emptyList())
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SparkHistoryCharts(a.answer, charts, range.window(nowEpochSec()), reserveLegend = view != SparkHistoryView.Total)
                                    when {
                                        a.answer == null -> HistoryLoadingLine()

                                        a.failed != null -> HistoryRetryLine(deploymentFailureMessage(choice, a.failed)) { retry++ }

                                        else -> {
                                            NoteLine(SPARK_HISTORY_NOTE)
                                            NoteLine("From ${source.label}.")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the card's chart area shows, and the state [ChartAreaTransition] swaps on. */
private sealed interface SparkArea {
    data class Charts(val answer: LoadedSpark?, val failed: SeriesResult.Failed?) : SparkArea

    data class Note(val text: String) : SparkArea
}

/** What the two charts draw, already coloured. */
private data class SparkCharts(val cpu: List<ChartSeries>, val memory: List<ChartSeries>)

private fun sparkChartSeries(loaded: LoadedSpark, apps: List<GenericResourceInfo>): SparkCharts {
    val cpu = (loaded.results[ScopeKind.CpuCores] as? SeriesResult.Ok)?.series.orEmpty()
    val memory = (loaded.results[ScopeKind.MemoryBytes] as? SeriesResult.Ok)?.series.orEmpty()

    fun colour(index: Int) = ClusterColor(hue = podSetHue(index)).composeColor

    return when (loaded.view) {
        SparkHistoryView.Applications -> {
            val cpuLines = sparkApplicationLines(cpu, apps)
            val memoryLines = sparkApplicationLines(memory, apps)
            val colorIndex = sparkColorIndex(cpuLines, memoryLines)
            fun chartLines(lines: List<Series>) = lines.map { ChartSeries(it.name, colour(colorIndex.getValue(it.name)), it.points) }
            SparkCharts(chartLines(cpuLines), chartLines(memoryLines))
        }

        SparkHistoryView.Roles -> {
            fun roleLines(series: List<Series>): List<ChartSeries> {
                fun line(container: String, name: String, index: Int) = series.firstOrNull { it.name == container }
                    ?.takeIf { it.points.isNotEmpty() }
                    ?.let { ChartSeries(name, colour(index), it.points) }
                return listOfNotNull(
                    line(PromQl.SPARK_DRIVER_CONTAINER, DRIVER_SERIES_NAME, 0),
                    line(PromQl.SPARK_EXECUTOR_CONTAINER, EXECUTORS_SERIES_NAME, 1),
                )
            }
            SparkCharts(roleLines(cpu), roleLines(memory))
        }

        SparkHistoryView.Total -> {
            fun total(series: List<Series>): List<ChartSeries> = sumPointwise(series.map { it.points })
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(ChartSeries(TOTAL_SERIES_NAME, KdPrimary, it)) }
                .orEmpty()
            SparkCharts(total(cpu), total(memory))
        }
    }
}

// Whatever a source throws becomes a failed chart: an exception would end the refresh loop and
// reach the window's handler, and its message is never shown or logged.
private suspend fun sparkSeriesOrFailed(source: MetricsHistorySource, kind: ScopeKind, scope: ScopeRef, grouping: SparkGrouping, window: QueryWindow): SeriesResult = try {
    source.sparkSeries(kind, scope, grouping, window)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    SeriesResult.Failed(HistoryFailure.Unavailable, "error")
}

@Composable
private fun NoteLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
}

@Composable
private fun SparkHistoryCharts(loaded: LoadedSpark?, charts: SparkCharts, fallbackWindow: QueryWindow, reserveLegend: Boolean) {
    @Composable
    fun Chart(title: String, series: List<ChartSeries>, result: SeriesResult?, formatValue: (Double) -> String, modifier: Modifier) {
        TimeSeriesChart(
            title = title,
            series = series,
            window = loaded?.window ?: fallbackWindow,
            formatValue = formatValue,
            modifier = modifier,
            note = (result as? SeriesResult.Failed)?.let(::shortFailure),
            loading = loaded == null,
            reserveLegend = reserveLegend,
        )
    }

    val cpu = loaded?.results?.get(ScopeKind.CpuCores)
    val memory = loaded?.results?.get(ScopeKind.MemoryBytes)
    // The same split as the Overview card: side by side from the usage sections' minimum width.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (usageSectionsSideBySide(maxWidth)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chart("CPU", charts.cpu, cpu, ::formatCoresValue, Modifier.weight(1f))
                Chart("Memory", charts.memory, memory, ::formatBytesValue, Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Chart("CPU", charts.cpu, cpu, ::formatCoresValue, Modifier.fillMaxWidth())
                Chart("Memory", charts.memory, memory, ::formatBytesValue, Modifier.fillMaxWidth())
            }
        }
    }
}
