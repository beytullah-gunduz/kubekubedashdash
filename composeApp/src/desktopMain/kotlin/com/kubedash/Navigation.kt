package com.kubedash

sealed class Screen(val title: String) {
    data object ClusterOverview : Screen("Cluster")
    data object Nodes : Screen("Nodes")
    data object Namespaces : Screen("Namespaces")
    data object Events : Screen("Events")

    data object Pods : Screen("Pods")
    data object Deployments : Screen("Deployments")
    data object StatefulSets : Screen("StatefulSets")
    data object DaemonSets : Screen("DaemonSets")
    data object ReplicaSets : Screen("ReplicaSets")
    data object Jobs : Screen("Jobs")
    data object CronJobs : Screen("CronJobs")

    data object ConfigMaps : Screen("ConfigMaps")
    data object Secrets : Screen("Secrets")

    data object Services : Screen("Services")
    data object Ingresses : Screen("Ingresses")
    data object Endpoints : Screen("Endpoints")
    data object NetworkPolicies : Screen("Network Policies")

    data object PersistentVolumes : Screen("Persistent Volumes")
    data object PersistentVolumeClaims : Screen("Persistent Volume Claims")
    data object StorageClasses : Screen("Storage Classes")

    data class ResourceDetail(
        val kind: String,
        val name: String,
        val namespace: String?,
    ) : Screen("$kind: $name")

    data class PodLogs(
        val podName: String,
        val namespace: String,
        val containerName: String? = null,
    ) : Screen("Logs: $podName")
}
