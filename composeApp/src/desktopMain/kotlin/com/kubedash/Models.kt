package com.kubedash

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

data class PodInfo(
    val uid: String,
    val name: String,
    val namespace: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val age: String,
    val node: String,
    val ip: String,
    val labels: Map<String, String>,
    val containers: List<ContainerInfo>,
)

data class ContainerInfo(
    val name: String,
    val image: String,
    val ready: Boolean,
    val restartCount: Int,
    val state: String,
)

data class DeploymentInfo(
    val uid: String,
    val name: String,
    val namespace: String,
    val ready: String,
    val upToDate: Int,
    val available: Int,
    val age: String,
    val strategy: String,
    val labels: Map<String, String>,
    val conditions: List<String>,
)

data class ServiceInfo(
    val uid: String,
    val name: String,
    val namespace: String,
    val type: String,
    val clusterIP: String,
    val ports: String,
    val age: String,
    val selector: Map<String, String>,
    val labels: Map<String, String>,
)

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

data class EventInfo(
    val uid: String,
    val type: String,
    val reason: String,
    val objectRef: String,
    val message: String,
    val count: Int,
    val firstSeen: String,
    val lastSeen: String,
    val namespace: String,
)

data class GenericResourceInfo(
    val uid: String,
    val name: String,
    val namespace: String?,
    val status: String?,
    val age: String,
    val labels: Map<String, String>,
    val extraColumns: Map<String, String> = emptyMap(),
)

data class ResourceUsageSummary(
    val cpuUsedMillis: Long,
    val cpuCapacityMillis: Long,
    val memoryUsedBytes: Long,
    val memoryCapacityBytes: Long,
    val metricsAvailable: Boolean,
)

sealed class ResourceState<out T> {
    data object Loading : ResourceState<Nothing>()

    data class Error(
        val message: String,
    ) : ResourceState<Nothing>()

    data class Success<T>(
        val data: T,
    ) : ResourceState<T>()
}
