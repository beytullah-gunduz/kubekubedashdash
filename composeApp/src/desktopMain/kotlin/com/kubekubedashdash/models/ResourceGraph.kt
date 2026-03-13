package com.kubekubedashdash.models

data class ResourceGraph(
    val nodes: List<ResourceGraphNode>,
    val edges: List<ResourceGraphEdge>,
)
