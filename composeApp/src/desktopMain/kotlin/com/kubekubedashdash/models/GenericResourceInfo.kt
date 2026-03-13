package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class GenericResourceInfo(
    val uid: String,
    val name: String,
    val namespace: String?,
    val status: String?,
    val age: String,
    val labels: Map<String, String>,
    val extraColumns: Map<String, String> = emptyMap(),
)
