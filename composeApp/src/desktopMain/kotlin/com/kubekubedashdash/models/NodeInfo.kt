package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class NodeInfo(
    val uid: String,
    val name: String,
    val status: String,
    val roles: String,
    val version: String,
    val os: String,
    val arch: String,
    val containerRuntime: String,
    val cpu: String,
    val memory: String,
    val pods: String,
    val age: String,
    val labels: Map<String, String>,
)
