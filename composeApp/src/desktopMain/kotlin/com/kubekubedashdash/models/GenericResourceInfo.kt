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
    // RFC 3339 creation time, filled for ReplicaSets only (rollout markers). @Transient keeps it out of the MCP JSON.
    @Transient
    val creationTimestamp: String = "",
) : Identifiable {
    override val fallbackKey: String get() = "${namespace.orEmpty()}/$name"
}
