package com.kubekubedashdash

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ServiceInfo

sealed class Screen(val title: String) {

    sealed class Main(title: String) : Screen(title) {
        data object ClusterOverview : Main("Cluster")
        data class Nodes(
            val selectNodeName: String? = null,
            // Pre-seeds the existing status filter chip on entry. Driven by
            // the NODES_NOT_READY banner click → setOf("NotReady").
            val statusFilter: Set<String>? = null,
            // When true, the Nodes screen hides nodes whose CPU- and memory-
            // utilisation are both below NODE_PRESSURE_THRESHOLD. Driven by
            // the NODES_UNDER_PRESSURE banner click. User can clear via chip.
            val pressureOnly: Boolean = false,
        ) : Main("Nodes")
        data object Namespaces : Main("Namespaces")
        data class Events(
            val selectEventUid: String? = null,
            // When non-null, the Events screen seeds its type-filter chip to
            // this single type (typically "Warning") on entry. Cleared by
            // user toggling. Driven by clicks on the cluster health banner.
            val typeFilter: String? = null,
        ) : Main("Events")

        data class Pods(
            val selectPodUid: String? = null,
            // When non-null, the Pods screen seeds its status filter to
            // this allowlist on entry. Driven by clicks on the cluster
            // health banner ("3 pods in error" → errorPodStatuses()).
            val statusFilter: Set<String>? = null,
        ) : Main("Pods")
        data class Deployments(
            // When true, the Deployments screen hides deployments whose
            // ready/desired replica count is at quorum. Driven by the
            // DEPLOYMENTS_DEGRADED banner click. User can clear via chip.
            val degradedOnly: Boolean = false,
        ) : Main("Deployments")
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

        data object ClusterTopology : Main("Topology")

        /**
         * A custom resource list view, identified by its CRD group + kind. The
         * router resolves the matching `CrdInfo` from
         * `ReactiveKubeClient.crds` to fetch column metadata and stream
         * instances.
         */
        data class CustomResource(
            val group: String,
            val version: String,
            val kind: String,
            val plural: String,
            val namespaced: Boolean,
        ) : Main(kind)

        data object Connecting : Main("Connecting")
        data class ConnectionError(val error: String?, val retryCountdown: Int) : Main("Connection Error")
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
    }
}
