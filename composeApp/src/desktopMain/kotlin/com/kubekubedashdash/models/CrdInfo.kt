package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
enum class CrdScope { NAMESPACED, CLUSTER }

@Serializable
data class CrdColumnSpec(
    val name: String,
    val type: String,
    val jsonPath: String,
    val priority: Int = 0,
)

@Serializable
data class CrdInfo(
    val group: String,
    val version: String,
    val kind: String,
    val plural: String,
    val singular: String,
    val shortNames: List<String>,
    val categories: List<String>,
    val scope: CrdScope,
    val columns: List<CrdColumnSpec>,
) {
    val key: String get() = "$group/$kind"
    val groupVersionKind: String get() = "$group/$version/$kind"
    val namespaced: Boolean get() = scope == CrdScope.NAMESPACED
}
