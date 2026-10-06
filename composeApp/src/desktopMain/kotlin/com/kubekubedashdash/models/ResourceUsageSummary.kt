package com.kubekubedashdash.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ResourceUsageSummary(
    val cpuUsedMillis: Long,
    val cpuCapacityMillis: Long,
    val memoryUsedBytes: Long,
    val memoryCapacityBytes: Long,
    val metricsAvailable: Boolean,
    // Per-pod usage from the same `top pods` sample the totals above add up,
    // keyed by podUsageKey: one fetch feeds the KPI strip and the Pods table,
    // so they never disagree. Only ReactiveKubeClient.resourceUsage fills it.
    // @Transient keeps it out of the MCP `get_resource_usage` JSON.
    @Transient
    val podUsages: Map<String, PodUsage> = emptyMap(),
)
