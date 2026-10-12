package com.kubekubedashdash.ui.screens.cluster

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSeriesMemory
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.keyboard_arrow_down_filled
import com.kubekubedashdash.resources.keyboard_arrow_up_filled
import com.kubekubedashdash.services.metricshistory.HistoryFailure
import com.kubekubedashdash.services.metricshistory.HistoryRange
import com.kubekubedashdash.services.metricshistory.MetricsHistoryService
import com.kubekubedashdash.services.metricshistory.MetricsHistorySource
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.SeriesResult
import com.kubekubedashdash.services.metricshistory.SourceChoice
import com.kubekubedashdash.ui.components.BusyIndicator
import com.kubekubedashdash.ui.components.ChartSeries
import com.kubekubedashdash.ui.components.LogToolbarMenu
import com.kubekubedashdash.ui.components.LogToolbarMenuItem
import com.kubekubedashdash.ui.components.TimeSeriesChart
import com.kubekubedashdash.ui.screens.pods.HISTORY_REFRESH_MS
import com.kubekubedashdash.ui.screens.pods.LOADING_HISTORY_NOTE
import com.kubekubedashdash.ui.screens.pods.formatBytesValue
import com.kubekubedashdash.ui.screens.pods.formatCoresValue
import com.kubekubedashdash.ui.screens.pods.shortFailure
import com.kubekubedashdash.ui.screens.workloads.deploymentFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

internal const val USAGE_HISTORY_TITLE = "Usage over time"

/** The line under the charts; the CPU sentence is why the card and the live gauge above it can disagree. */
internal fun usageHistoryNote(sourceLabel: String): String = "From $sourceLabel. CPU is a trailing average over several minutes, so it lags the live gauge and can differ from it."

/** The namespaces the history sums over; every namespace for [NamespaceScope.All]. */
internal fun scopeRefOf(scope: NamespaceScope): ScopeRef = ScopeRef(if (scope is NamespaceScope.Only) scope.namespaces else emptySet())

/** One completed fetch: the window it asked for and what each kind answered. */
internal data class LoadedScope(val window: QueryWindow, val results: Map<ScopeKind, SeriesResult>) {
    /** Whether CPU or memory has a point to draw. */
    val hasPoints: Boolean
        get() = results.values.any { result -> (result as? SeriesResult.Ok)?.series.orEmpty().any { it.points.isNotEmpty() } }
}

/**
 * Whether the refresh loop asks again: a new window, or one not fully answered. An empty answer
 * counts as not answered too, so history that starts arriving shows up within a refresh even on a
 * 24 h range, whose window only moves every 720 s.
 */
internal fun needsScopeFetch(current: LoadedScope?, window: QueryWindow): Boolean = current == null ||
    current.window != window ||
    current.results.values.any { it !is SeriesResult.Ok } ||
    !current.hasPoints

/**
 * The failure that kept CPU and memory from drawing anything, CPU's first; null when either has a
 * point to draw or neither failed (an empty answer then shows the charts' own "No data in this range").
 */
internal fun scopeHeadlineFailure(loaded: LoadedScope): SeriesResult.Failed? {
    if (loaded.hasPoints) return null
    return loaded.results[ScopeKind.CpuCores] as? SeriesResult.Failed ?: loaded.results[ScopeKind.MemoryBytes] as? SeriesResult.Failed
}

/**
 * Cluster-wide CPU and memory over time for the namespace selection, from the cluster's
 * metrics-history source (Managed Prometheus or a Prometheus behind a Service, the demo cluster's
 * included), refreshed every [refreshMs] while the card is composed and [expanded].
 *
 * Without a source the card is absent, spacing included: while Auto still searches, when history is
 * off or incomplete, and when Auto found nothing. The card runs that search itself, so the Overview
 * alone is enough to start it.
 */
@Composable
internal fun ClusterUsageHistoryCard(
    service: MetricsHistoryService,
    namespaceScope: NamespaceScope,
    expanded: Boolean,
    onToggle: () -> Unit,
    nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    refreshMs: Long = HISTORY_REFRESH_MS,
) {
    val settings by service.settings.collectAsState()
    val detection by service.detectionState.collectAsState()
    // Not keyed on the live context: an Overview first composed while disconnected stays hidden
    // until the settings, the detection or the namespace selection change.
    val choice = remember(settings, detection, namespaceScope) { service.choice() }
    val source = remember(choice) { service.sourceFor(choice) }
    // The search lives in the detection's own scope, so leaving the Overview never cancels it, and
    // its answer flips [choice]. Keyed on the detection states too: "Search again" during a search
    // abandons it and leaves Detecting equal to itself, so the choice alone would never start the
    // next one. ensureDetected joins a search under way and does nothing once there is an answer.
    LaunchedEffect(choice, detection) {
        if (choice is SourceChoice.Detecting) service.ensureDetected()
    }
    var range by remember { mutableStateOf(HistoryRange.H6) }
    val scopeRef = remember(namespaceScope) { scopeRefOf(namespaceScope) }
    // Outlives a collapse: expanding again finds the last answer instead of a loading line.
    var loaded by remember(namespaceScope, range, choice) { mutableStateOf<LoadedScope?>(null) }

    if (expanded && source != null) {
        LaunchedEffect(namespaceScope, range, choice) {
            while (true) {
                val window = range.window(nowEpochSec())
                // A 24 h window moves only every 720 s: refetching an identical, fully answered one wastes quota.
                if (needsScopeFetch(loaded, window)) {
                    loaded = LoadedScope(
                        window,
                        coroutineScope {
                            ScopeKind.entries.map { kind -> kind to async { scopeSeriesOrFailed(source, kind, scopeRef, window) } }
                                .associate { (kind, deferred) -> kind to deferred.await() }
                        },
                    )
                }
                delay(refreshMs)
            }
        }
    }

    if (source == null) return

    Spacer(Modifier.height(24.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = 10.dp.kdCorner,
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(KdBorder)),
    ) {
        Column {
            // The range menu is a clickable of its own, so opening it does not reach the header's toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    USAGE_HISTORY_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = KdTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
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
                    val failed = answer?.let(::scopeHeadlineFailure)
                    when {
                        answer == null -> LoadingLine()

                        failed != null -> NoteLine(deploymentFailureMessage(choice, failed))

                        else -> {
                            UsageHistoryCharts(answer)
                            NoteLine(usageHistoryNote(source.label))
                        }
                    }
                }
            }
        }
    }
}

// Whatever a source throws becomes a failed chart: an exception would end the refresh loop and
// reach the window's handler, and its message is never shown or logged.
private suspend fun scopeSeriesOrFailed(source: MetricsHistorySource, kind: ScopeKind, scope: ScopeRef, window: QueryWindow): SeriesResult = try {
    source.scopeSeries(kind, scope, window)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    SeriesResult.Failed(HistoryFailure.Unavailable, "error")
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

@Composable
private fun UsageHistoryCharts(loaded: LoadedScope) {
    val cpu = loaded.results[ScopeKind.CpuCores]
    val memory = loaded.results[ScopeKind.MemoryBytes]

    @Composable
    fun Chart(title: String, result: SeriesResult?, color: Color, formatValue: (Double) -> String, modifier: Modifier) {
        val points = sumPoints(result)
        TimeSeriesChart(
            title = title,
            series = if (points.isEmpty()) emptyList() else listOf(ChartSeries("", color, points)),
            window = loaded.window,
            formatValue = formatValue,
            modifier = modifier,
            note = (result as? SeriesResult.Failed)?.let(::shortFailure),
        )
    }

    // The same split as the gauges' section above: side by side from USAGE_SPLIT_MIN_WIDTH.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (usageSectionsSideBySide(maxWidth)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chart("CPU", cpu, KdPrimary, ::formatCoresValue, Modifier.weight(1f))
                Chart("Memory", memory, KdSeriesMemory, ::formatBytesValue, Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Chart("CPU", cpu, KdPrimary, ::formatCoresValue, Modifier.fillMaxWidth())
                Chart("Memory", memory, KdSeriesMemory, ::formatBytesValue, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The points of the one series a scope query answers with; none for a failure or an empty answer. */
private fun sumPoints(result: SeriesResult?): List<SeriesPoint> = (result as? SeriesResult.Ok)?.series?.firstOrNull()?.points.orEmpty()
