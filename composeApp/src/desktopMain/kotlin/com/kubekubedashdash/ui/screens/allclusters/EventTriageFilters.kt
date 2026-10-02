package com.kubekubedashdash.ui.screens.allclusters

import kotlinx.serialization.Serializable

@Serializable
enum class TimeWindow(val label: String, val minutes: Long) {
    LAST_15M("15m", 15),
    LAST_1H("1h", 60),
    LAST_24H("24h", 1440),
}

@Serializable
enum class ViewMode { RAW, GROUPED }

@Serializable
data class EventTriageFilters(
    val types: Set<String> = DEFAULT_TYPES,
    val clusters: Set<String> = emptySet(),
    val namespaces: Set<String> = emptySet(),
    val reasons: Set<String> = emptySet(),
    val searchText: String = "",
    val timeWindow: TimeWindow = TimeWindow.LAST_1H,
    val mode: ViewMode = ViewMode.GROUPED,
    val heatmapVisible: Boolean = false,
) {
    val isDefault: Boolean
        get() = types == DEFAULT_TYPES &&
            clusters.isEmpty() &&
            namespaces.isEmpty() &&
            reasons.isEmpty() &&
            searchText.isEmpty() &&
            timeWindow == TimeWindow.LAST_1H &&
            !heatmapVisible

    companion object {
        /** The type allowlist a fresh All Clusters tab starts with. */
        val DEFAULT_TYPES: Set<String> = setOf("Warning", "Error")
    }
}
