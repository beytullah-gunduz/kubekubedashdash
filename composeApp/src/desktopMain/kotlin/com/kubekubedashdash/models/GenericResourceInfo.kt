package com.kubekubedashdash.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class GenericResourceInfo(
    override val uid: String,
    val name: String,
    val namespace: String?,
    val status: String?,
    val age: String,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val extraColumns: Map<String, String> = emptyMap(),
    val owners: List<OwnerRefInfo> = emptyList(),
    // RFC 3339 creation time, filled for ReplicaSets and Jobs (rollout and run markers). @Transient keeps it out of the MCP JSON.
    @Transient
    val creationTimestamp: String = "",
    // A Job's times and indexing (Job rows only), for the usage history charts. @Transient keeps it out of the MCP JSON.
    @Transient
    val run: WorkloadRun? = null,
    // A SparkApplication's pods and submission times (Spark operator rows only). @Transient keeps it out of the MCP JSON.
    @Transient
    val spark: SparkRun? = null,
) : Identifiable {
    override val fallbackKey: String get() = "${namespace.orEmpty()}/$name"
}
