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
    val types: Set<String> = setOf("Warning", "Error"),
    val clusters: Set<String> = emptySet(),
    val namespaces: Set<String> = emptySet(),
    val reasons: Set<String> = emptySet(),
    val searchText: String = "",
    val timeWindow: TimeWindow = TimeWindow.LAST_1H,
    val mode: ViewMode = ViewMode.GROUPED,
    val heatmapVisible: Boolean = false,
) {
    val isDefault: Boolean
        get() = types == setOf("Warning", "Error") &&
            clusters.isEmpty() &&
            namespaces.isEmpty() &&
            reasons.isEmpty() &&
            searchText.isEmpty() &&
            timeWindow == TimeWindow.LAST_1H &&
            !heatmapVisible
}
