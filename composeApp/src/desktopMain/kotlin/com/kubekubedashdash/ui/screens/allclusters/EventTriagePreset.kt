package com.kubekubedashdash.ui.screens.allclusters

import com.kubekubedashdash.ui.screens.allclusters.viewmodel.AllClustersViewModel
import kotlinx.serialization.Serializable

@Serializable
data class EventTriagePreset(
    val id: String,
    val name: String,
    val filters: EventTriageFilters,
    val builtIn: Boolean = false,
)

/**
 * Returns the two built-in presets.
 *
 * The "this-cluster-only" preset stores placeholder filters; at apply-time,
 * [AllClustersViewModel.applyPreset] detects the id and recomputes from
 * [AllClustersViewModel.clusterSummaries].
 */
fun buildBuiltIns(summaries: List<AllClustersViewModel.ClusterSummary>): List<EventTriagePreset> = listOf(
    EventTriagePreset(
        id = "critical-only",
        name = "Critical only",
        filters = EventTriageFilters(
            types = setOf("Warning", "Error"),
            clusters = emptySet(),
            namespaces = emptySet(),
            reasons = emptySet(),
            searchText = "",
            timeWindow = TimeWindow.LAST_15M,
            mode = ViewMode.GROUPED,
        ),
        builtIn = true,
    ),
    EventTriagePreset(
        id = "this-cluster-only",
        name = "This cluster only",
        // Placeholder — recomputed at apply-time from live clusterSummaries.
        filters = EventTriageFilters(
            types = setOf("Warning", "Error"),
            clusters = emptySet(),
            namespaces = emptySet(),
            reasons = emptySet(),
            searchText = "",
            timeWindow = TimeWindow.LAST_1H,
            mode = ViewMode.GROUPED,
        ),
        builtIn = true,
    ),
)
