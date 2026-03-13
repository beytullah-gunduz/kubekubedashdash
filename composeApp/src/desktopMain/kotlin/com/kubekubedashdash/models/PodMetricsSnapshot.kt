package com.kubekubedashdash.models

data class PodMetricsSnapshot(
    val timestampMs: Long,
    val cpuMillis: Long,
    val memoryBytes: Long,
)
