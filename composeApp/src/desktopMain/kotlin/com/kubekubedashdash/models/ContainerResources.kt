package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

/**
 * A container's declared requests and limits, parsed. Null = not set; a zero
 * or unparseable quantity also reads as not set.
 */
@Serializable
data class ContainerResources(
    val name: String,
    val cpuRequestMillis: Long? = null,
    val cpuLimitMillis: Long? = null,
    val memoryRequestBytes: Long? = null,
    val memoryLimitBytes: Long? = null,
)
