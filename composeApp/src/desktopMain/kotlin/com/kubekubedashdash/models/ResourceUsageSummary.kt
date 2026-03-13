package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class ResourceUsageSummary(
    val cpuUsedMillis: Long,
    val cpuCapacityMillis: Long,
    val memoryUsedBytes: Long,
    val memoryCapacityBytes: Long,
    val metricsAvailable: Boolean,
)
