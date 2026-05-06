package com.kubekubedashdash.models

data class ResourceGraphNode(
    val id: String,
    val name: String,
    val kind: String,
    val status: String? = null,
    val namespace: String? = null,
    val subKind: String? = null,
    val image: String? = null,
    val restartCount: Int? = null,
)
