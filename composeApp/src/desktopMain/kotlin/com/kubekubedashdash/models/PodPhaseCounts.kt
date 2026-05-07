package com.kubekubedashdash.models

data class PodPhaseCounts(
    val running: Int,
    val pending: Int,
    val failed: Int,
    val succeeded: Int,
)
