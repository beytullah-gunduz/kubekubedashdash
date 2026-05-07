package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class ServiceInfo(
    val uid: String,
    val name: String,
    val namespace: String,
    val type: String,
    val clusterIP: String,
    val externalIPs: String = "",
    val ports: String,
    val age: String,
    val selector: Map<String, String>,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
)
