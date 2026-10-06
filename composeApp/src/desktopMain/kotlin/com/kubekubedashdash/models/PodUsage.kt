package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

/** One container's sampled usage, from a `PodMetrics` entry. */
@Serializable
data class ContainerUsage(
    val name: String,
    val cpuMillis: Long,
    val memoryBytes: Long,
)

/**
 * One pod's sampled usage: the sum over the containers metrics-server
 * reports, plus each container — the memory alert compares every container
 * with its own limit, because a container is OOM-killed at its own limit,
 * not at the pod's.
 */
@Serializable
data class PodUsage(
    val cpuMillis: Long,
    val memoryBytes: Long,
    val containers: List<ContainerUsage>,
)

/** Key of [ResourceUsageSummary.podUsages]. `PodMetrics` carry no UID, so namespace + name. */
fun podUsageKey(namespace: String, name: String): String = "$namespace/$name"
