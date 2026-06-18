package com.kubekubedashdash.util

import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.models.ServiceInfo
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

class KubeClient(
    private val connectionManager: KubeConnectionManager,
) : Closeable {

    private val log = LoggerFactory.getLogger(KubeClient::class.java)

    val isConnected: Boolean get() = connectionManager.isConnected

    val connectionError: StateFlow<String?> = connectionManager.connectionError

    fun reportSuccess() = connectionManager.reportSuccess()
    fun reportError(message: String) = connectionManager.reportError(message)

    private val client: KubernetesClient
        get() = connectionManager.client

    internal val kubernetesClient: KubernetesClient
        get() = client

    fun connect(context: String? = null): Result<String> = connectionManager.connect(context)
    fun getContexts(): List<String> = connectionManager.getContexts()
    fun getCurrentContext(): String = connectionManager.getCurrentContext()
    fun getClusterServer(): String = connectionManager.getClusterServer()

    // ── Namespaces ──────────────────────────────────────────────────────────────

    fun getNamespaceNames(): List<String> = client.namespaces().list().items.map { it.metadata.name }

    fun getNamespacesGeneric(): List<GenericResourceInfo> = client.namespaces().list().items.map { ns ->
        GenericResourceInfo(
            uid = ns.metadata.uid ?: "",
            name = ns.metadata.name,
            namespace = null,
            status = ns.status?.phase ?: "Active",
            age = formatAge(ns.metadata.creationTimestamp),
            labels = ns.metadata.labels ?: emptyMap(),
            annotations = ns.metadata.annotations ?: emptyMap(),
        )
    }

    // ── Pods ────────────────────────────────────────────────────────────────────

    fun getPods(namespace: String?): List<PodInfo> {
        log.debug("Fetching pods namespace={}", namespace ?: "all")
        val pods = namespacedList(namespace) { ns ->
            if (ns != null) {
                pods().inNamespace(ns).list().items
            } else {
                pods().inAnyNamespace().list().items
            }
        }
        log.debug("Fetched {} pods", pods.size)
        return pods.map(::mapPod)
    }

    fun getPodsByNode(nodeName: String): List<PodInfo> = client.pods().inAnyNamespace().list().items
        .filter { it.spec?.nodeName == nodeName || it.status?.nominatedNodeName == nodeName }
        .map(::mapPod)

    // Audit A2: deduplicated against ReactiveKubeClient — both now share
    // the single pure implementation in ResourceMappers. The old local
    // copy here had drifted (it omitted `creationTimestamp`); routing
    // through ResourceMappers fixes that drift, which means pods returned
    // by getPods()/getPodsByNode() now carry creationTimestamp. Observable
    // change: the MCP `list_resources pod` JSON gains a creationTimestamp
    // field (additive). NodeDetailPanel is unaffected (UI ignores it).
    private fun mapPod(pod: Pod): PodInfo = ResourceMappers.mapPod(pod)

    // ── Deployments ─────────────────────────────────────────────────────────────

    fun getDeployments(namespace: String?): List<DeploymentInfo> {
        log.debug("Fetching deployments namespace={}", namespace ?: "all")
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                apps().deployments().inNamespace(ns).list().items
            } else {
                apps().deployments().inAnyNamespace().list().items
            }
        }
        log.debug("Fetched {} deployments", items.size)
        return items.map { dep ->
            val ready = dep.status?.readyReplicas ?: 0
            val desired = dep.spec?.replicas ?: 0
            DeploymentInfo(
                uid = dep.metadata.uid ?: "",
                name = dep.metadata.name,
                namespace = dep.metadata.namespace ?: "",
                ready = "$ready/$desired",
                upToDate = dep.status?.updatedReplicas ?: 0,
                available = dep.status?.availableReplicas ?: 0,
                age = formatAge(dep.metadata.creationTimestamp),
                strategy = dep.spec?.strategy?.type ?: "",
                labels = dep.metadata.labels ?: emptyMap(),
                annotations = dep.metadata.annotations ?: emptyMap(),
                conditions = dep.status?.conditions?.map { "${it.type}=${it.status}" } ?: emptyList(),
            )
        }
    }

    // ── Services ────────────────────────────────────────────────────────────────

    fun getServices(namespace: String?): List<ServiceInfo> {
        log.debug("Fetching services namespace={}", namespace ?: "all")
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                services().inNamespace(ns).list().items
            } else {
                services().inAnyNamespace().list().items
            }
        }
        log.debug("Fetched {} services", items.size)
        return items.map { svc ->
            val ports = svc.spec?.ports?.joinToString(", ") { p ->
                val np = if (p.nodePort != null && p.nodePort > 0) ":${p.nodePort}" else ""
                "${p.port}$np/${p.protocol}"
            } ?: ""
            val externalIPs = if (svc.spec?.type == "LoadBalancer") {
                svc.status?.loadBalancer?.ingress
                    ?.mapNotNull { it.ip?.ifBlank { null } ?: it.hostname }
                    ?.joinToString(", ").orEmpty()
            } else {
                svc.spec?.externalIPs?.joinToString(", ").orEmpty()
            }
            ServiceInfo(
                uid = svc.metadata.uid ?: "",
                name = svc.metadata.name,
                namespace = svc.metadata.namespace ?: "",
                type = svc.spec?.type ?: "",
                clusterIP = svc.spec?.clusterIP ?: "",
                externalIPs = externalIPs,
                ports = ports,
                age = formatAge(svc.metadata.creationTimestamp),
                selector = svc.spec?.selector ?: emptyMap(),
                labels = svc.metadata.labels ?: emptyMap(),
                annotations = svc.metadata.annotations ?: emptyMap(),
            )
        }
    }

    // ── Nodes ───────────────────────────────────────────────────────────────────

    fun getNodes(): List<NodeInfo> {
        log.debug("Fetching nodes")
        val nodeItems = client.nodes().list().items
        log.debug("Fetched {} nodes", nodeItems.size)
        return nodeItems.map { node ->
            val readyCond = node.status?.conditions?.find { it.type == "Ready" }
            val roles = node.metadata.labels
                ?.filterKeys { it.startsWith("node-role.kubernetes.io/") }
                ?.keys?.map { it.removePrefix("node-role.kubernetes.io/") }
                ?.joinToString(", ")?.ifEmpty { "<none>" } ?: "<none>"
            val alloc = node.status?.allocatable
            NodeInfo(
                uid = node.metadata.uid ?: "",
                name = node.metadata.name,
                status = if (readyCond?.status == "True") "Ready" else "NotReady",
                roles = roles,
                version = node.status?.nodeInfo?.kubeletVersion ?: "",
                os = node.status?.nodeInfo?.osImage ?: "",
                arch = node.status?.nodeInfo?.architecture ?: "",
                containerRuntime = node.status?.nodeInfo?.containerRuntimeVersion ?: "",
                cpu = alloc?.get("cpu")?.toString() ?: "",
                memory = alloc?.get("memory")?.toString() ?: "",
                pods = alloc?.get("pods")?.toString() ?: "",
                age = formatAge(node.metadata.creationTimestamp),
                unschedulable = node.spec?.unschedulable ?: false,
                labels = node.metadata.labels ?: emptyMap(),
                annotations = node.metadata.annotations ?: emptyMap(),
            )
        }
    }

    // ── Events ──────────────────────────────────────────────────────────────────

    fun getEvents(namespace: String?): List<EventInfo> {
        log.debug("Fetching events namespace={}", namespace ?: "all")
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                v1().events().inNamespace(ns).list().items
            } else {
                v1().events().inAnyNamespace().list().items
            }
        }
        log.debug("Fetched {} events", items.size)
        return items.sortedByDescending { it.metadata?.creationTimestamp }.map { ev ->
            val lastTs = ev.lastTimestamp ?: ev.metadata?.creationTimestamp ?: ""
            EventInfo(
                uid = ev.metadata?.uid ?: "",
                type = ev.type ?: "Normal",
                reason = ev.reason ?: "",
                objectRef = "${ev.involvedObject?.kind ?: ""}/${ev.involvedObject?.name ?: ""}",
                message = ev.message ?: "",
                count = ev.count ?: 1,
                firstSeen = formatAge(ev.firstTimestamp ?: ev.metadata?.creationTimestamp),
                lastSeen = formatAge(lastTs),
                lastSeenTimestamp = lastTs,
                namespace = ev.metadata?.namespace ?: "",
                node = ev.source?.host ?: "",
            )
        }
    }

    fun getEventsForNode(nodeName: String): List<EventInfo> {
        val items = client.v1().events().inAnyNamespace().list().items
            .filter { it.involvedObject?.kind == "Node" && it.involvedObject?.name == nodeName }
            .sortedByDescending { it.metadata?.creationTimestamp }
        return items.map { ev ->
            val lastTs = ev.lastTimestamp ?: ev.metadata?.creationTimestamp ?: ""
            EventInfo(
                uid = ev.metadata?.uid ?: "",
                type = ev.type ?: "Normal",
                reason = ev.reason ?: "",
                objectRef = "${ev.involvedObject?.kind ?: ""}/${ev.involvedObject?.name ?: ""}",
                message = ev.message ?: "",
                count = ev.count ?: 1,
                firstSeen = formatAge(ev.firstTimestamp ?: ev.metadata?.creationTimestamp),
                lastSeen = formatAge(lastTs),
                lastSeenTimestamp = lastTs,
                namespace = ev.metadata?.namespace ?: "",
                node = ev.source?.host ?: "",
            )
        }
    }

    // ── Generic Resource Fetchers ───────────────────────────────────────────────

    fun getConfigMaps(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                configMaps().inNamespace(ns).list().items
            } else {
                configMaps().inAnyNamespace().list().items
            }
        }
        return items.map { cm ->
            GenericResourceInfo(
                uid = cm.metadata.uid ?: "",
                name = cm.metadata.name,
                namespace = cm.metadata.namespace,
                status = null,
                age = formatAge(cm.metadata.creationTimestamp),
                labels = cm.metadata.labels ?: emptyMap(),
                annotations = cm.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Data" to "${(cm.data?.size ?: 0) + (cm.binaryData?.size ?: 0)}"),
            )
        }
    }

    fun getSecrets(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                secrets().inNamespace(ns).list().items
            } else {
                secrets().inAnyNamespace().list().items
            }
        }
        return items.map { s ->
            GenericResourceInfo(
                uid = s.metadata.uid ?: "",
                name = s.metadata.name,
                namespace = s.metadata.namespace,
                status = null,
                age = formatAge(s.metadata.creationTimestamp),
                labels = s.metadata.labels ?: emptyMap(),
                annotations = s.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Type" to (s.type ?: ""), "Data" to "${s.data?.size ?: 0}"),
            )
        }
    }

    fun getStatefulSets(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                apps().statefulSets().inNamespace(ns).list().items
            } else {
                apps().statefulSets().inAnyNamespace().list().items
            }
        }
        return items.map { ss ->
            val r = ss.status?.readyReplicas ?: 0
            val d = ss.spec?.replicas ?: 0
            GenericResourceInfo(
                uid = ss.metadata.uid ?: "",
                name = ss.metadata.name,
                namespace = ss.metadata.namespace,
                status = "$r/$d",
                age = formatAge(ss.metadata.creationTimestamp),
                labels = ss.metadata.labels ?: emptyMap(),
                annotations = ss.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Ready" to "$r/$d"),
            )
        }
    }

    fun getDaemonSets(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                apps().daemonSets().inNamespace(ns).list().items
            } else {
                apps().daemonSets().inAnyNamespace().list().items
            }
        }
        return items.map { ds ->
            val desired = ds.status?.desiredNumberScheduled ?: 0
            val ready = ds.status?.numberReady ?: 0
            GenericResourceInfo(
                uid = ds.metadata.uid ?: "",
                name = ds.metadata.name,
                namespace = ds.metadata.namespace,
                status = "$ready/$desired",
                age = formatAge(ds.metadata.creationTimestamp),
                labels = ds.metadata.labels ?: emptyMap(),
                annotations = ds.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Desired" to "$desired", "Ready" to "$ready"),
            )
        }
    }

    fun getReplicaSets(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                apps().replicaSets().inNamespace(ns).list().items
            } else {
                apps().replicaSets().inAnyNamespace().list().items
            }
        }
        return items.map { rs ->
            val r = rs.status?.readyReplicas ?: 0
            val d = rs.spec?.replicas ?: 0
            GenericResourceInfo(
                uid = rs.metadata.uid ?: "",
                name = rs.metadata.name,
                namespace = rs.metadata.namespace,
                status = "$r/$d",
                age = formatAge(rs.metadata.creationTimestamp),
                labels = rs.metadata.labels ?: emptyMap(),
                annotations = rs.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Ready" to "$r/$d"),
            )
        }
    }

    fun getJobs(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                batch().v1().jobs().inNamespace(ns).list().items
            } else {
                batch().v1().jobs().inAnyNamespace().list().items
            }
        }
        return items.map { job ->
            val succ = job.status?.succeeded ?: 0
            val comp = job.spec?.completions ?: 1
            val status = when {
                (job.status?.active ?: 0) > 0 -> "Running"
                succ >= comp -> "Complete"
                (job.status?.failed ?: 0) > 0 -> "Failed"
                else -> "Pending"
            }
            GenericResourceInfo(
                uid = job.metadata.uid ?: "",
                name = job.metadata.name,
                namespace = job.metadata.namespace,
                status = status,
                age = formatAge(job.metadata.creationTimestamp),
                labels = job.metadata.labels ?: emptyMap(),
                annotations = job.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Completions" to "$succ/$comp", "Status" to status),
            )
        }
    }

    fun getCronJobs(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                batch().v1().cronjobs().inNamespace(ns).list().items
            } else {
                batch().v1().cronjobs().inAnyNamespace().list().items
            }
        }
        return items.map { cj ->
            GenericResourceInfo(
                uid = cj.metadata.uid ?: "",
                name = cj.metadata.name,
                namespace = cj.metadata.namespace,
                status = if (cj.spec?.suspend == true) "Suspended" else "Active",
                age = formatAge(cj.metadata.creationTimestamp),
                labels = cj.metadata.labels ?: emptyMap(),
                annotations = cj.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Schedule" to (cj.spec?.schedule ?: ""),
                    "Active" to "${cj.status?.active?.size ?: 0}",
                ),
            )
        }
    }

    fun getIngresses(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                network().v1().ingresses().inNamespace(ns).list().items
            } else {
                network().v1().ingresses().inAnyNamespace().list().items
            }
        }
        return items.map { ing ->
            val hosts = ing.spec?.rules?.mapNotNull { it.host }?.joinToString(", ") ?: ""
            GenericResourceInfo(
                uid = ing.metadata.uid ?: "",
                name = ing.metadata.name,
                namespace = ing.metadata.namespace,
                status = null,
                age = formatAge(ing.metadata.creationTimestamp),
                labels = ing.metadata.labels ?: emptyMap(),
                annotations = ing.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Hosts" to hosts),
            )
        }
    }

    fun getEndpoints(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                endpoints().inNamespace(ns).list().items
            } else {
                endpoints().inAnyNamespace().list().items
            }
        }
        return items.map { ep ->
            val count = ep.subsets?.flatMap { it.addresses ?: emptyList() }?.size ?: 0
            GenericResourceInfo(
                uid = ep.metadata.uid ?: "",
                name = ep.metadata.name,
                namespace = ep.metadata.namespace,
                status = null,
                age = formatAge(ep.metadata.creationTimestamp),
                labels = ep.metadata.labels ?: emptyMap(),
                annotations = ep.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Endpoints" to "$count"),
            )
        }
    }

    fun getNetworkPolicies(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                network().v1().networkPolicies().inNamespace(ns).list().items
            } else {
                network().v1().networkPolicies().inAnyNamespace().list().items
            }
        }
        return items.map { np ->
            GenericResourceInfo(
                uid = np.metadata.uid ?: "",
                name = np.metadata.name,
                namespace = np.metadata.namespace,
                status = null,
                age = formatAge(np.metadata.creationTimestamp),
                labels = np.metadata.labels ?: emptyMap(),
                annotations = np.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Policy Types" to (np.spec?.policyTypes?.joinToString(", ") ?: "")),
            )
        }
    }

    fun getPersistentVolumes(): List<GenericResourceInfo> = client.persistentVolumes().list().items.map { pv ->
        GenericResourceInfo(
            uid = pv.metadata.uid ?: "",
            name = pv.metadata.name,
            namespace = null,
            status = pv.status?.phase,
            age = formatAge(pv.metadata.creationTimestamp),
            labels = pv.metadata.labels ?: emptyMap(),
            annotations = pv.metadata.annotations ?: emptyMap(),
            extraColumns = mapOf(
                "Capacity" to (pv.spec?.capacity?.get("storage")?.toString() ?: ""),
                "Access Modes" to (pv.spec?.accessModes?.joinToString(", ") ?: ""),
                "Reclaim" to (pv.spec?.persistentVolumeReclaimPolicy ?: ""),
                "Claim" to (pv.spec?.claimRef?.let { "${it.namespace}/${it.name}" } ?: ""),
            ),
        )
    }

    fun getPersistentVolumeClaims(namespace: String?): List<GenericResourceInfo> {
        val items = namespacedList(namespace) { ns ->
            if (ns != null) {
                persistentVolumeClaims().inNamespace(ns).list().items
            } else {
                persistentVolumeClaims().inAnyNamespace().list().items
            }
        }
        return items.map { pvc ->
            GenericResourceInfo(
                uid = pvc.metadata.uid ?: "",
                name = pvc.metadata.name,
                namespace = pvc.metadata.namespace,
                status = pvc.status?.phase,
                age = formatAge(pvc.metadata.creationTimestamp),
                labels = pvc.metadata.labels ?: emptyMap(),
                annotations = pvc.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Capacity" to (pvc.status?.capacity?.get("storage")?.toString() ?: ""),
                    "Access Modes" to (pvc.status?.accessModes?.joinToString(", ") ?: ""),
                    "Storage Class" to (pvc.spec?.storageClassName ?: ""),
                    "Volume" to (pvc.spec?.volumeName ?: ""),
                ),
            )
        }
    }

    fun getStorageClasses(): List<GenericResourceInfo> = client.storage().v1().storageClasses().list().items.map { sc ->
        val isDefault = sc.metadata.annotations
            ?.containsKey("storageclass.kubernetes.io/is-default-class") == true
        GenericResourceInfo(
            uid = sc.metadata.uid ?: "",
            name = sc.metadata.name,
            namespace = null,
            status = if (isDefault) "Default" else null,
            age = formatAge(sc.metadata.creationTimestamp),
            labels = sc.metadata.labels ?: emptyMap(),
            annotations = sc.metadata.annotations ?: emptyMap(),
            extraColumns = mapOf(
                "Provisioner" to (sc.provisioner ?: ""),
                "Reclaim Policy" to (sc.reclaimPolicy ?: ""),
                "Binding Mode" to (sc.volumeBindingMode ?: ""),
            ),
        )
    }

    // ── YAML / Detail ───────────────────────────────────────────────────────────

    fun getResourceYaml(
        kind: String,
        name: String,
        namespace: String?,
        group: String? = null,
        version: String? = null,
        plural: String? = null,
    ): String = try {
        log.debug("Fetching YAML kind={} name={} namespace={} group={} version={}", kind, name, namespace, group, version)
        val res: Any? = when (kind.lowercase()) {
            "pod" -> namespace?.let { client.pods().inNamespace(it).withName(name).get() }

            "deployment" -> namespace?.let { client.apps().deployments().inNamespace(it).withName(name).get() }

            "service" -> namespace?.let { client.services().inNamespace(it).withName(name).get() }

            "node" -> client.nodes().withName(name).get()

            "namespace" -> client.namespaces().withName(name).get()

            "configmap" -> namespace?.let { client.configMaps().inNamespace(it).withName(name).get() }

            "secret" -> namespace?.let { client.secrets().inNamespace(it).withName(name).get() }

            "statefulset" -> namespace?.let { client.apps().statefulSets().inNamespace(it).withName(name).get() }

            "daemonset" -> namespace?.let { client.apps().daemonSets().inNamespace(it).withName(name).get() }

            "replicaset" -> namespace?.let { client.apps().replicaSets().inNamespace(it).withName(name).get() }

            "job" -> namespace?.let { client.batch().v1().jobs().inNamespace(it).withName(name).get() }

            "cronjob" -> namespace?.let { client.batch().v1().cronjobs().inNamespace(it).withName(name).get() }

            "ingress" -> namespace?.let { client.network().v1().ingresses().inNamespace(it).withName(name).get() }

            "persistentvolume" -> client.persistentVolumes().withName(name).get()

            "persistentvolumeclaim" -> namespace?.let { client.persistentVolumeClaims().inNamespace(it).withName(name).get() }

            "storageclass" -> client.storage().v1().storageClasses().withName(name).get()

            else -> if (!group.isNullOrBlank() && !version.isNullOrBlank()) {
                fetchGenericResource(group, version, kind, plural, name, namespace)
            } else {
                null
            }
        }
        if (res != null) {
            Serialization.asYaml(res)
        } else {
            log.warn("Resource not found kind={} name={} namespace={}", kind, name, namespace)
            "# Resource not found"
        }
    } catch (e: Exception) {
        log.error("Failed to fetch YAML kind={} name={} namespace={}: {}", kind, name, namespace, e.message)
        "# Error: ${e.message}"
    }

    private fun fetchGenericResource(
        group: String,
        version: String,
        kind: String,
        plural: String?,
        name: String,
        namespace: String?,
    ): io.fabric8.kubernetes.api.model.GenericKubernetesResource? {
        val effectivePlural = plural?.takeIf { it.isNotBlank() } ?: pluralizeForApi(kind)
        val rdc = io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
            .withGroup(group)
            .withVersion(version)
            .withKind(kind)
            .withPlural(effectivePlural)
            .withNamespaced(namespace != null)
            .build()
        val op = client.genericKubernetesResources(rdc)
        return if (namespace != null) {
            op.inNamespace(namespace).withName(name).get()
        } else {
            op.withName(name).get()
        }
    }

    private fun pluralizeForApi(kind: String): String {
        val lower = kind.lowercase()
        return when {
            lower.endsWith("y") -> lower.dropLast(1) + "ies"
            lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh") -> lower + "es"
            else -> lower + "s"
        }
    }

    // ── Pod Logs ────────────────────────────────────────────────────────────────

    fun getPodLogs(name: String, namespace: String, container: String?, tailLines: Int = 1000): String = try {
        log.debug("Fetching pod logs pod={} namespace={} container={} tailLines={}", name, namespace, container, tailLines)
        val op = client.pods().inNamespace(namespace).withName(name)
        val withC = if (container != null) op.inContainer(container) else op
        withC.tailingLines(tailLines).log ?: ""
    } catch (e: Exception) {
        log.error("Failed to fetch pod logs pod={} namespace={}: {}", name, namespace, e.message)
        "Error fetching logs: ${e.message}"
    }

    // ── Pod Metrics (single pod) ────────────────────────────────────────────────

    fun getPodMetrics(name: String, namespace: String): PodMetricsSnapshot? = try {
        val podMetricItems = client.top().pods().inNamespace(namespace).metrics().items
        val pm = podMetricItems?.find { it.metadata?.name == name }
        if (pm != null) {
            var cpu = 0L
            var mem = 0L
            for (c in pm.containers ?: emptyList()) {
                cpu += parseCpuToMillis(c.usage?.get("cpu")?.toString() ?: "0")
                mem += parseMemoryToBytes(c.usage?.get("memory")?.toString() ?: "0")
            }
            PodMetricsSnapshot(System.currentTimeMillis(), cpu, mem)
        } else {
            log.debug("No metrics found for pod={} namespace={}", name, namespace)
            null
        }
    } catch (e: Exception) {
        log.debug("Metrics server unavailable for pod={}: {}", name, e.message)
        null
    }

    // ── Resource Usage (Metrics Server) ─────────────────────────────────────────

    fun getResourceUsage(namespace: String?): ResourceUsageSummary {
        log.debug("Fetching resource usage namespace={}", namespace ?: "all")
        try {
            val podMetricItems = try {
                if (namespace != null) {
                    client.top().pods().inNamespace(namespace).metrics().items ?: emptyList()
                } else {
                    client.top().pods().metrics().items ?: emptyList()
                }
            } catch (e: Exception) {
                log.warn("Metrics server unavailable: {}", e.message)
                return ResourceUsageSummary(0, 0, 0, 0, metricsAvailable = false)
            }

            var cpuUsed = 0L
            var memUsed = 0L
            for (pm in podMetricItems) {
                for (c in pm.containers ?: emptyList()) {
                    cpuUsed += parseCpuToMillis(c.usage?.get("cpu")?.toString() ?: "0")
                    memUsed += parseMemoryToBytes(c.usage?.get("memory")?.toString() ?: "0")
                }
            }

            val nodes = try {
                client.nodes().list().items ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            var cpuCap = 0L
            var memCap = 0L
            for (node in nodes) {
                val alloc = node.status?.allocatable ?: continue
                cpuCap += parseCpuToMillis(alloc["cpu"]?.toString() ?: "0")
                memCap += parseMemoryToBytes(alloc["memory"]?.toString() ?: "0")
            }

            log.debug("Resource usage: cpu={}m/{}m mem={}/{}", cpuUsed, cpuCap, memUsed, memCap)
            return ResourceUsageSummary(cpuUsed, cpuCap, memUsed, memCap, metricsAvailable = true)
        } catch (e: Exception) {
            log.warn("Failed to compute resource usage: {}", e.message)
            return ResourceUsageSummary(0, 0, 0, 0, metricsAvailable = false)
        }
    }

    // ── Cluster Overview ────────────────────────────────────────────────────────

    fun getClusterInfo(namespace: String?): ClusterInfo {
        log.debug("Fetching cluster info namespace={}", namespace ?: "all")
        val v = client.kubernetesVersion
        val major = v?.major.orEmpty()
        val minor = v?.minor.orEmpty()
        val versionStr = if (major.isNotBlank() && minor.isNotBlank()) "$major.$minor" else ""
        val nodes = client.nodes().list().items
        val namespaces = client.namespaces().list().items
        val pods = if (namespace != null) {
            client.pods().inNamespace(namespace).list().items
        } else {
            client.pods().inAnyNamespace().list().items
        }
        val deps = if (namespace != null) {
            client.apps().deployments().inNamespace(namespace).list().items
        } else {
            client.apps().deployments().inAnyNamespace().list().items
        }
        val svcs = if (namespace != null) {
            client.services().inNamespace(namespace).list().items
        } else {
            client.services().inAnyNamespace().list().items
        }
        return ClusterInfo(
            name = getCurrentContext(), server = getClusterServer(),
            version = versionStr,
            nodesCount = nodes.size, namespacesCount = namespaces.size,
            podsCount = pods.size, deploymentsCount = deps.size, servicesCount = svcs.size,
            runningPods = pods.count { it.status?.phase == "Running" },
            pendingPods = pods.count { it.status?.phase == "Pending" },
            failedPods = pods.count { it.status?.phase == "Failed" },
            succeededPods = pods.count { it.status?.phase == "Succeeded" },
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun <T> namespacedList(namespace: String?, fetch: KubernetesClient.(String?) -> List<T>): List<T> = client.fetch(namespace)

    override fun close() {
        // Connection lifecycle is managed by KubeConnectionManager
    }
}

fun parseCpuToMillis(value: String): Long {
    if (value.isBlank() || value == "0") return 0
    // Parse via Double and round, not integer division: nanocore/microcore
    // values below one millicore (e.g. "500000n" = 0.5m) truncated to 0, and
    // fractional core values ("0.5") parsed to 0 — both understated cluster CPU.
    return when {
        value.endsWith("n") -> value.removeSuffix("n").toDoubleOrNull()?.div(1_000_000.0)?.roundToLong() ?: 0
        value.endsWith("u") -> value.removeSuffix("u").toDoubleOrNull()?.div(1_000.0)?.roundToLong() ?: 0
        value.endsWith("m") -> value.removeSuffix("m").toDoubleOrNull()?.roundToLong() ?: 0
        else -> value.toDoubleOrNull()?.times(1_000.0)?.roundToLong() ?: 0
    }
}

fun parseMemoryToBytes(value: String): Long {
    if (value.isBlank() || value == "0") return 0
    // Parse the magnitude via Double so fractional quantities (e.g. "1.5Gi",
    // "0.5G") scale correctly instead of `toLongOrNull()` returning null -> 0,
    // which under-counted node/pod memory capacity.
    fun scaled(suffix: String, factor: Double): Long = value.removeSuffix(suffix).toDoubleOrNull()?.times(factor)?.toLong() ?: 0
    return when {
        value.endsWith("Ti") -> scaled("Ti", 1024.0 * 1024 * 1024 * 1024)
        value.endsWith("Gi") -> scaled("Gi", 1024.0 * 1024 * 1024)
        value.endsWith("Mi") -> scaled("Mi", 1024.0 * 1024)
        value.endsWith("Ki") -> scaled("Ki", 1024.0)
        value.endsWith("T") -> scaled("T", 1_000_000_000_000.0)
        value.endsWith("G") -> scaled("G", 1_000_000_000.0)
        value.endsWith("M") -> scaled("M", 1_000_000.0)
        value.endsWith("K") || value.endsWith("k") -> scaled(value.takeLast(1), 1_000.0)
        else -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: 0
    }
}

fun formatMemorySize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 * 1024 -> "%.1f TiB".format(bytes.toDouble() / (1024.0 * 1024 * 1024 * 1024))
    bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes.toDouble() / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0f MiB".format(bytes.toDouble() / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KiB".format(bytes.toDouble() / 1024.0)
    else -> "$bytes B"
}

fun formatCpuCores(millis: Long): String = when {
    millis >= 1000 -> "%.1f cores".format(millis.toDouble() / 1000.0)
    else -> "${millis}m"
}

private val ageLog = LoggerFactory.getLogger("com.kubekubedashdash.util.formatAge")

// approximate: 365-day years, 30-day months
fun formatAge(timestamp: String?, now: Instant = Instant.now()): String {
    if (timestamp.isNullOrBlank()) return ""
    return try {
        val created = Instant.parse(timestamp)
        val dur = Duration.between(created, now)
        if (dur.isNegative) {
            ageLog.debug("Negative age duration: now={} created={} (clock skew?)", now, created)
            return "0s"
        }
        when {
            dur.toDays() > 365 -> "${dur.toDays() / 365}y${(dur.toDays() % 365) / 30}mo"
            dur.toDays() > 30 -> "${dur.toDays() / 30}mo${dur.toDays() % 30}d"
            dur.toDays() > 0 -> "${dur.toDays()}d${dur.toHours() % 24}h"
            dur.toHours() > 0 -> "${dur.toHours()}h${dur.toMinutes() % 60}m"
            dur.toMinutes() > 0 -> "${dur.toMinutes()}m"
            else -> "${dur.seconds}s"
        }
    } catch (_: Exception) {
        timestamp
    }
}
