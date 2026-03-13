package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class ContainerInfo(
    val name: String,
    val image: String,
    val ready: Boolean,
    val restartCount: Int,
    val state: String,
)
