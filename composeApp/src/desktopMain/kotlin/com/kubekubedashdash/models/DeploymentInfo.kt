package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class DeploymentInfo(
    val uid: String,
    val name: String,
    val namespace: String,
    val ready: String,
    val upToDate: Int,
    val available: Int,
    val age: String,
    val strategy: String,
    val labels: Map<String, String>,
    val conditions: List<String>,
)
