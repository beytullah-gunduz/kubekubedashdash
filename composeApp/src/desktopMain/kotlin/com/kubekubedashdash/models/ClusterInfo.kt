package com.kubekubedashdash.models

import kotlinx.serialization.Serializable

@Serializable
data class ClusterInfo(
    val name: String,
    val server: String,
    val version: String,
    val nodesCount: Int,
    val namespacesCount: Int,
    val podsCount: Int,
    val deploymentsCount: Int,
    val servicesCount: Int,
    val runningPods: Int,
    val pendingPods: Int,
    val failedPods: Int,
    val succeededPods: Int,
)
