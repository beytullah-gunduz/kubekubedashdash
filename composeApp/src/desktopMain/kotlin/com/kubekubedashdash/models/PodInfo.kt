package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class PodInfo(
    val uid: String,
    val name: String,
    val namespace: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val age: String,
    val creationTimestamp: String = "",
    val node: String,
    val ip: String,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val containers: List<ContainerInfo>,
    // Raw `pod.status.phase` (Pending/Running/Succeeded/Failed/Unknown).
    // Distinct from `status` above, which is the *effective* status string
    // (e.g. "CrashLoopBackOff") composed from phase + container states for
    // display. Tallies in clusterInfo's combine count by raw phase.
    val phase: String = "",
)
