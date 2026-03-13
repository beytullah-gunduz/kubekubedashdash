package com.kubekubedashdash.models

data class ResourceGraphNode(
    val id: String,
    val name: String,
    val kind: String,
    val status: String? = null,
)
