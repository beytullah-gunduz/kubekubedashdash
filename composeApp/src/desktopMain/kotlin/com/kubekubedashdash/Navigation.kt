package com.kubekubedashdash

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo

sealed class Screen(val title: String) {

    sealed class Main(title: String) : Screen(title) {
        data object ClusterOverview : Main("Cluster")
        data class Nodes(val selectNodeName: String? = null) : Main("Nodes")
        data object Namespaces : Main("Namespaces")
        data object Events : Main("Events")

        data class Pods(val selectPodUid: String? = null) : Main("Pods")
        data object Deployments : Main("Deployments")
        data object StatefulSets : Main("StatefulSets")
        data object DaemonSets : Main("DaemonSets")
        data object ReplicaSets : Main("ReplicaSets")
        data object Jobs : Main("Jobs")
        data object CronJobs : Main("CronJobs")

        data object ConfigMaps : Main("ConfigMaps")
        data object Secrets : Main("Secrets")

        data object Services : Main("Services")
        data object Ingresses : Main("Ingresses")
        data object Endpoints : Main("Endpoints")
        data object NetworkPolicies : Main("Network Policies")

        data object PersistentVolumes : Main("Persistent Volumes")
        data object PersistentVolumeClaims : Main("Persistent Volume Claims")
        data object StorageClasses : Main("Storage Classes")

        data object Connecting : Main("Connecting")
        data class ConnectionError(val error: String?, val retryCountdown: Int) : Main("Connection Error")

        data object Logs : Main("Logs")
        data object Settings : Main("Settings")
    }

    sealed class Detail(title: String) : Screen(title) {
        data class EventDetail(
            val event: com.kubekubedashdash.models.EventInfo,
        ) : Detail("Event: ${event.reason}")

        data class ResourceDetail(
            val kind: String,
            val name: String,
            val namespace: String?,
        ) : Detail("$kind: $name")

        data class PodDetail(
            val pod: PodInfo,
        ) : Detail("Pod: ${pod.name}")

        data class NodeDetail(
            val node: NodeInfo,
        ) : Detail("Node: ${node.name}")

        data class DeploymentDetail(
            val deployment: DeploymentInfo,
        ) : Detail("Deployment: ${deployment.name}")

        data class ServiceDetail(
            val service: ServiceInfo,
        ) : Detail("Service: ${service.name}")

        data class NamespaceDetail(
            val namespace: GenericResourceInfo,
        ) : Detail("Namespace: ${namespace.name}")

        data class PodLogs(
            val podName: String,
            val namespace: String,
            val containerName: String? = null,
        ) : Detail("Logs: $podName")
    }
}
