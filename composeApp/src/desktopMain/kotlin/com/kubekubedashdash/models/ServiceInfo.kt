package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class ServiceInfo(
    override val uid: String,
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
    // Structured spec.ports; [ports] above stays the display string.
    val portSpecs: List<ServicePortInfo> = emptyList(),
) : Identifiable {
    override val fallbackKey: String get() = "$namespace/$name"
}

/** One `spec.ports` entry. [targetPort] is the raw IntOrString as text ("8080" or "http"); empty when unset. */
@Serializable
data class ServicePortInfo(
    val name: String = "",
    val port: Int,
    val targetPort: String = "",
    val protocol: String = "TCP",
)
