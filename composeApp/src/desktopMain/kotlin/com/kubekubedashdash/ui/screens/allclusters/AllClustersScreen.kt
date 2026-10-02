package com.kubekubedashdash.ui.screens.allclusters

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.Screen
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel
import com.kubekubedashdash.ui.screens.cluster.UsageScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AllClustersScreen() {
    val viewModel = AllClustersViewModel.instance
    val clusterInfo by viewModel.aggregatedClusterInfo.collectAsState()
    val usage by viewModel.aggregatedUsage.collectAsState()
    val cpuHistory by viewModel.cpuHistory.collectAsState()
    val memHistory by viewModel.memHistory.collectAsState()
    val podsCapacity by viewModel.aggregatedPodsCapacity.collectAsState()
    val podsHistory by viewModel.podsHistory.collectAsState()
    val topNodes by viewModel.topClusterNodes.collectAsState()
    val summaries by viewModel.clusterSummaries.collectAsState()

    val filters by viewModel.filters.collectAsState()
    val filteredEvents by viewModel.filteredEvents.collectAsState()
    val groupedEvents by viewModel.groupedEvents.collectAsState()
    val availableClusters by viewModel.availableClusters.collectAsState()
    val availableNamespaces by viewModel.availableNamespaces.collectAsState()
    val availableReasons by viewModel.availableReasons.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val heatmapData by viewModel.heatmapData.collectAsState()

    val statsPanelsExpanded by PreferenceRepository.statsPanelsExpanded.collectAsState()
    val statsExpanded = statsPanelsExpanded[PreferenceRepository.STATS_PANEL_ALL_CLUSTERS] ?: true

    // Kept out of the filters on purpose: an auto-opened heatmap is not a filter the user set, so
    // it must not make the table "filtered" (empty-state wording, Clear filters) or land in a preset.
    var heatmapAutoOpened by remember { mutableStateOf(false) }
    val heatmapDismissed by PreferenceRepository.heatmapAutoOpenDismissed.collectAsState()
    val heatmapShown = filters.heatmapVisible || heatmapAutoOpened

    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        val density = LocalDensity.current
        // Height of everything above the table, measured; the table gets the
        // rest of the viewport but never less than MIN_EVENT_TABLE_HEIGHT, and
        // the page scrolls when both do not fit (finding 3).
        // Starts at 0: the first frame gives the table the whole viewport, then
        // onSizeChanged settles it. No loop — the chrome never depends on tableHeight.
        var chromeHeight by remember { mutableStateOf(0.dp) }
        val tableHeight = maxOf(MIN_EVENT_TABLE_HEIGHT, maxHeight - chromeHeight)

        // The heatmap opens by itself at most once per visit (this page is disposed while another
        // tab shows), and only from data present on arrival: data that qualifies later only marks
        // the toggle, so the table never moves under the pointer. A close stops it for good, until
        // the user opens it by hand; a short window keeps the table its minimum instead.
        val viewport = maxHeight
        LaunchedEffect(Unit) {
            if (PreferenceRepository.heatmapAutoOpenDismissed.value || viewModel.filters.value.heatmapVisible) return@LaunchedEffect
            val data = withTimeoutOrNull(HEATMAP_ARRIVAL_WINDOW_MS) {
                viewModel.heatmapData.first(::heatmapWorthOpening)
            } ?: return@LaunchedEffect
            val chrome = snapshotFlow { chromeHeight }.first { it > 0.dp }
            if (heatmapFits(viewport, chrome, data.clusters.size)) heatmapAutoOpened = true
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }) {
                FleetStrip(
                    clusterInfo = clusterInfo,
                    usage = usage,
                    cpuHistory = cpuHistory,
                    memHistory = memHistory,
                    podsCapacity = podsCapacity,
                    podsHistory = podsHistory,
                    summaries = summaries,
                    topNodes = topNodes,
                    timeWindow = filters.timeWindow,
                    scope = allClustersUsageScope(summaries.map { it.namespace }),
                    expanded = statsExpanded,
                    onToggleExpanded = { PreferenceRepository.setStatsPanelExpanded(PreferenceRepository.STATS_PANEL_ALL_CLUSTERS, !statsExpanded) },
                    onOpenCluster = { WorkspaceManager.activateClusterTab(it) },
                    onShowWarnings = { context -> viewModel.updateFilters { f -> warningsFilter(f, context) } },
                    onNodeClick = { node ->
                        WorkspaceManager.openInClusterTab(node.sessionId, Screen.Main.Nodes(selectNodeName = node.usage.nodeName))
                    },
                )
                Spacer(Modifier.height(12.dp))
                AllClustersEventsFilterBar(
                    filters = filters,
                    availableTypes = setOf("Normal", "Warning", "Error"),
                    availableClusters = availableClusters,
                    availableNamespaces = availableNamespaces,
                    availableReasons = availableReasons,
                    onUpdateFilters = viewModel::updateFilters,
                    presetMenuSlot = {
                        AllClustersPresetMenu(
                            presets = presets,
                            onApplyPreset = viewModel::applyPreset,
                            onDeletePreset = viewModel::deletePreset,
                            onSavePreset = viewModel::saveCurrentAsPreset,
                        )
                    },
                    heatmapVisible = heatmapShown,
                    heatmapHint = !heatmapShown && !heatmapDismissed && heatmapWorthOpening(heatmapData),
                    onToggleHeatmap = {
                        // Any close, of an auto-opened or a hand-opened heatmap, stops auto-opening;
                        // opening it by hand allows it again.
                        if (heatmapShown) {
                            heatmapAutoOpened = false
                            viewModel.updateFilters { f -> f.copy(heatmapVisible = false) }
                            PreferenceRepository.setHeatmapAutoOpenDismissed(true)
                        } else {
                            viewModel.updateFilters { f -> f.copy(heatmapVisible = true) }
                            PreferenceRepository.setHeatmapAutoOpenDismissed(false)
                        }
                    },
                )
                AnimatedVisibility(
                    // Mirrors the disabled toggle: without this, a previously-on heatmap stays stuck open when clusters drop below 2.
                    visible = heatmapShown && availableClusters.size > 1,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                    exit = shrinkVertically(animationSpec = tween(250)) + fadeOut(animationSpec = tween(200)),
                ) {
                    AllClustersReasonHeatmap(
                        data = heatmapData,
                        onCellClick = { cluster, reason -> viewModel.onHeatmapCellClick(cluster, reason) },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(tableHeight)) {
                AllClustersEventsTable(
                    events = filteredEvents,
                    groupedEvents = groupedEvents,
                    mode = filters.mode,
                    hasActiveFilters = !filters.isDefault,
                    onClearFilters = viewModel::resetFilters,
                    onEventClick = { ev -> eventJumpTarget(ev)?.let { (sessionId, screen) -> WorkspaceManager.openInClusterTab(sessionId, screen) } },
                )
            }
        }
    }
}

/**
 * Header wording for the All Clusters usage section. A tab with a namespace
 * selected contributes that namespace's pods and usage but its whole
 * cluster's capacity; the panels say which namespace each tab follows.
 * [namespaces] holds one entry per open cluster tab, null for all namespaces.
 */
internal fun allClustersUsageScope(namespaces: List<String?>): UsageScope? {
    val scoped = namespaces.filterNotNull()
    val total = namespaces.size
    return when {
        scoped.isEmpty() -> null

        total == 1 -> UsageScope.namespace(scoped.single())

        else -> {
            val which = when (scoped.size) {
                1 -> "1 of $total clusters cover one namespace (see its panel)"
                total -> "all $total clusters cover one namespace each (see their panels)"
                else -> "${scoped.size} of $total clusters cover one namespace each (see their panels)"
            }
            UsageScope(
                title = "Usage Statistics · namespace-scoped",
                note = "Pods and usage for $which; capacity is whole-cluster",
            )
        }
    }
}
