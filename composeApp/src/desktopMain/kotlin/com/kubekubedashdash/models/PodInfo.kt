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
    val node: String,
    val ip: String,
    val labels: Map<String, String>,
    val containers: List<ContainerInfo>,
)
