package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceGraphEdge
import com.kubekubedashdash.models.ResourceGraphNode
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.DaemonSet
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler
import io.fabric8.kubernetes.api.model.batch.v1.CronJob
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.networking.v1.Ingress

/**
 * Pure resource-graph assembly, extracted from `ReactiveKubeClient` (audit
 * finding A1). The Kubernetes list/get calls stay in `ReactiveKubeClient`
 * (blocking I/O on `Dispatchers.IO`); this object receives the already-
 * fetched fabric8 objects and performs only deterministic node/edge
 * assembly — no client, no I/O, no logging, no mutable shared state.
 *
 * This is the single largest and most owner-reference-heavy block that was
 * buried in the god-class (the `findRoot` owner-ref walk in particular),
 * and it was previously impossible to unit-test because it was welded to a
 * live `KubernetesClient`. Node/edge *insertion order* is preserved exactly
 * (the previous code fetched lists interleaved with assembly; reads have no
 * side effects, so hoisting every fetch ahead of assembly is behaviour-
 * equivalent — only log cardinality on a fetch failure changes, and the
 * caller still logs).
 */
object ResourceGraphBuilder {

    /**
     * Deployment-centric graph: Deployment → ReplicaSets → Pods, plus
     * Services/Ingresses selecting it, mounted ConfigMaps/Secrets/PVCs,
     * its ServiceAccount, and HPAs targeting it.
     *
     * Mirrors the previous `ReactiveKubeClient.getDeploymentResourceGraph`
     * partial-failure semantics: the caller passes `emptyList()` for any
     * group whose fetch threw, which yields exactly the same graph the old
     * per-section `catch` produced (the assembly itself is null-safe and
     * cannot throw).
     */
    fun buildDeploymentGraph(
        name: String,
        deployment: Deployment?,
        allReplicaSets: List<ReplicaSet>,
        allPods: List<Pod>,
        allServices: List<Service>,
        allIngresses: List<Ingress>,
        allHpas: List<HorizontalPodAutoscaler>,
    ): ResourceGraph {
        deployment ?: return ResourceGraph(emptyList(), emptyList())

        val graphNodes = mutableListOf<ResourceGraphNode>()
        val edges = mutableListOf<ResourceGraphEdge>()
        val addedNodeIds = mutableSetOf<String>()

        fun addNode(node: ResourceGraphNode) {
            if (addedNodeIds.add(node.id)) graphNodes.add(node)
        }

        val depUid = deployment.metadata.uid ?: return ResourceGraph(emptyList(), emptyList())
        val depId = "Deployment:$depUid"
        val matchLabels = deployment.spec?.selector?.matchLabels ?: emptyMap()
        val readyReplicas = deployment.status?.readyReplicas ?: 0
        val desiredReplicas = deployment.spec?.replicas ?: 0
        addNode(
            ResourceGraphNode(
                id = depId,
                name = name,
                kind = "Deployment",
                status = if (readyReplicas >= desiredReplicas && desiredReplicas > 0) "Available" else "Progressing",
            ),
        )

        run {
            val ownedRS = allReplicaSets.filter { rs -> rs.metadata?.ownerReferences?.any { it.uid == depUid } == true }
            for (rs in ownedRS) {
                val rsUid = rs.metadata?.uid ?: continue
                val r = rs.status?.readyReplicas ?: 0
                val d = rs.spec?.replicas ?: 0
                if (d == 0 && r == 0) continue
                val rsId = "ReplicaSet:$rsUid"
                addNode(ResourceGraphNode(rsId, rs.metadata.name, "ReplicaSet", "$r/$d"))
                edges.add(ResourceGraphEdge(depId, rsId))
                val rsPods = allPods.filter { pod -> pod.metadata?.ownerReferences?.any { it.uid == rsUid } == true }
                for (pod in rsPods) {
                    val podUid = pod.metadata?.uid ?: continue
                    val podId = "Pod:$podUid"
                    addNode(ResourceGraphNode(podId, pod.metadata.name, "Pod", ResourceMappers.effectivePodStatus(pod)))
                    edges.add(ResourceGraphEdge(rsId, podId))
                }
            }
        }

        run {
            for (svc in allServices) {
                val selector = svc.spec?.selector ?: continue
                if (selector.isEmpty()) continue
                if (!selector.all { (k, v) -> matchLabels[k] == v }) continue
                val svcUid = svc.metadata?.uid ?: continue
                val svcId = "Service:$svcUid"
                addNode(ResourceGraphNode(svcId, svc.metadata.name, "Service", svc.spec?.type))
                edges.add(ResourceGraphEdge(svcId, depId))
                for (ing in allIngresses) {
                    val matches = ing.spec?.rules?.any { rule ->
                        rule.http?.paths?.any { path -> path.backend?.service?.name == svc.metadata.name } == true
                    } == true
                    if (matches) {
                        val ingUid = ing.metadata?.uid ?: continue
                        val ingId = "Ingress:$ingUid"
                        addNode(ResourceGraphNode(ingId, ing.metadata.name, "Ingress", null))
                        edges.add(ResourceGraphEdge(ingId, svcId))
                    }
                }
            }
        }

        val podSpec = deployment.spec?.template?.spec
        val cmNames = mutableSetOf<String>()
        val secretNames = mutableSetOf<String>()
        val pvcNames = mutableSetOf<String>()
        podSpec?.volumes?.forEach { vol ->
            vol.configMap?.name?.let { cmNames.add(it) }
            vol.secret?.secretName?.let { secretNames.add(it) }
            vol.persistentVolumeClaim?.claimName?.let { pvcNames.add(it) }
        }
        val allContainers = (podSpec?.containers ?: emptyList()) + (podSpec?.initContainers ?: emptyList())
        for (c in allContainers) {
            c.envFrom?.forEach { ef ->
                ef.configMapRef?.name?.let { cmNames.add(it) }
                ef.secretRef?.name?.let { secretNames.add(it) }
            }
            c.env?.forEach { ev ->
                ev.valueFrom?.configMapKeyRef?.name?.let { cmNames.add(it) }
                ev.valueFrom?.secretKeyRef?.name?.let { secretNames.add(it) }
            }
        }

        val podNodeIds = graphNodes.filter { it.kind == "Pod" }.map { it.id }
        for (cm in cmNames) {
            val cmId = "ConfigMap:$cm"
            addNode(ResourceGraphNode(cmId, cm, "ConfigMap", null))
            podNodeIds.forEach { pid -> edges.add(ResourceGraphEdge(pid, cmId)) }
            if (podNodeIds.isEmpty()) edges.add(ResourceGraphEdge(depId, cmId))
        }
        for (s in secretNames) {
            val sId = "Secret:$s"
            addNode(ResourceGraphNode(sId, s, "Secret", null))
            podNodeIds.forEach { pid -> edges.add(ResourceGraphEdge(pid, sId)) }
            if (podNodeIds.isEmpty()) edges.add(ResourceGraphEdge(depId, sId))
        }
        for (pvc in pvcNames) {
            val pvcId = "PVC:$pvc"
            addNode(ResourceGraphNode(pvcId, pvc, "PVC", null))
            podNodeIds.forEach { pid -> edges.add(ResourceGraphEdge(pid, pvcId)) }
            if (podNodeIds.isEmpty()) edges.add(ResourceGraphEdge(depId, pvcId))
        }

        val saName = podSpec?.serviceAccountName ?: podSpec?.serviceAccount
        if (saName != null && saName != "default") {
            val saId = "ServiceAccount:$saName"
            addNode(ResourceGraphNode(saId, saName, "ServiceAccount", null))
            podNodeIds.forEach { pid -> edges.add(ResourceGraphEdge(pid, saId)) }
            if (podNodeIds.isEmpty()) edges.add(ResourceGraphEdge(depId, saId))
        }

        run {
            for (hpa in allHpas) {
                if (hpa.spec?.scaleTargetRef?.kind == "Deployment" && hpa.spec?.scaleTargetRef?.name == name) {
                    val hpaUid = hpa.metadata?.uid ?: continue
                    val hpaId = "HPA:$hpaUid"
                    addNode(ResourceGraphNode(hpaId, hpa.metadata.name, "HPA", null))
                    edges.add(ResourceGraphEdge(hpaId, depId))
                }
            }
        }

        return ResourceGraph(graphNodes, edges)
    }

    /**
     * Whole-namespace topology: Ingress → Service → WorkloadGroup
     * (Deployment/StatefulSet/DaemonSet/Job/CronJob/CRD root, with child
     * Pod nodes), External LB endpoints, and mounted helpers. `crdKinds`
     * is the set of CRD `kind`s the cluster carries (previously read from
     * `ReactiveKubeClient.crds.value`), used so that pods owned by a
     * custom resource (e.g. SparkApplication) climb to that root.
     *
     * Returns the truncated graph (plus a `__truncated__` Warning node)
     * when more than 200 column-occupying nodes would be produced — Pod
     * nodes are exempt from the cap because the UI renders them as hidden
     * children of their WorkloadGroup.
     */
    fun buildClusterTopology(
        ingresses: List<Ingress>,
        services: List<Service>,
        allPods: List<Pod>,
        allRS: List<ReplicaSet>,
        allJobs: List<Job>,
        allDeployments: List<Deployment>,
        allStatefulSets: List<StatefulSet>,
        allDaemonSets: List<DaemonSet>,
        allCronJobs: List<CronJob>,
        crdKinds: Set<String>,
    ): ResourceGraph {
        val graphNodes = mutableListOf<ResourceGraphNode>()
        val edges = mutableListOf<ResourceGraphEdge>()
        val addedNodeIds = mutableSetOf<String>()

        fun addNode(node: ResourceGraphNode) {
            if (addedNodeIds.add(node.id)) graphNodes.add(node)
        }
        fun addEdge(sourceId: String, targetId: String) {
            edges.add(ResourceGraphEdge(sourceId, targetId))
        }

        // Step 1: Ingresses
        // Map service name+namespace -> ingress id for later edge building
        val ingressServiceEdges = mutableListOf<Triple<String, String, String>>() // (ingId, svcName, svcNs)
        for (ing in ingresses) {
            val ingUid = ing.metadata?.uid ?: continue
            val ingId = "Ingress:$ingUid"
            val ingNs = ing.metadata?.namespace ?: ""
            addNode(ResourceGraphNode(ingId, ing.metadata?.name ?: "", "Ingress", null, ingNs))
            ing.spec?.rules?.forEach { rule ->
                rule.http?.paths?.forEach { path ->
                    val svcName = path.backend?.service?.name ?: return@forEach
                    ingressServiceEdges.add(Triple(ingId, svcName, ingNs))
                }
            }
        }

        // Step 2: Services + External nodes
        // Map svcName+ns -> svcId
        val svcIdByNameNs = mutableMapOf<String, String>()
        for (svc in services) {
            val svcUid = svc.metadata?.uid ?: continue
            val svcId = "Service:$svcUid"
            val svcNs = svc.metadata?.namespace ?: ""
            val svcName = svc.metadata?.name ?: ""
            svcIdByNameNs["$svcName/$svcNs"] = svcId
            addNode(ResourceGraphNode(svcId, svcName, "Service", svc.spec?.type, svcNs))

            // External nodes
            val lbIngresses = svc.status?.loadBalancer?.ingress ?: emptyList()
            for (lbIng in lbIngresses) {
                val key = lbIng.ip?.takeIf { it.isNotBlank() } ?: lbIng.hostname?.takeIf { it.isNotBlank() } ?: continue
                val extId = "External:$key"
                addNode(ResourceGraphNode(extId, key, "External", null))
                addEdge(extId, svcId)
            }
            val externalIPs = svc.spec?.externalIPs ?: emptyList()
            for (ip in externalIPs) {
                val extId = "External:$ip"
                addNode(ResourceGraphNode(extId, ip, "External", null))
                addEdge(extId, svcId)
            }
        }
        // Connect ingress -> service edges
        for ((ingId, svcName, svcNs) in ingressServiceEdges) {
            val svcId = svcIdByNameNs["$svcName/$svcNs"] ?: continue
            addEdge(ingId, svcId)
        }

        // Build lookup maps by UID
        val rsByUid = allRS.associateBy { it.metadata?.uid }
        val jobsByUid = allJobs.associateBy { it.metadata?.uid }
        val deploymentsByUid = allDeployments.associateBy { it.metadata?.uid }
        val statefulSetsByUid = allStatefulSets.associateBy { it.metadata?.uid }
        val daemonSetsByUid = allDaemonSets.associateBy { it.metadata?.uid }
        val cronJobsByUid = allCronJobs.associateBy { it.metadata?.uid }
        val podsByUid = allPods.associateBy { it.metadata?.uid }

        data class RootWorkload(val uid: String, val kind: String, val name: String, val groupKey: String)

        fun findRoot(pod: Pod): RootWorkload? {
            // Walk ownerReferences upward. Pods owned by other pods (Spark
            // Operator's executor → driver topology) climb until we hit a
            // controller kind or a CRD that the cluster actually carries.
            // Bounded depth as a paranoia check against cycles in malformed
            // ownerReferences — 6 covers Spark's pod→pod→SparkApplication
            // chain with headroom.
            var ownerRef = pod.metadata?.ownerReferences?.firstOrNull() ?: return null
            var depth = 0
            while (ownerRef.kind == "Pod" && depth < 6) {
                val parentPod = podsByUid[ownerRef.uid] ?: return null
                ownerRef = parentPod.metadata?.ownerReferences?.firstOrNull() ?: return null
                depth++
            }
            return when (ownerRef.kind) {
                "ReplicaSet" -> {
                    val rs = rsByUid[ownerRef.uid]
                    val rsOwner = rs?.metadata?.ownerReferences?.firstOrNull()
                    if (rsOwner?.kind == "Deployment") {
                        val dep = deploymentsByUid[rsOwner.uid]
                        if (dep != null) {
                            RootWorkload(rsOwner.uid, "Deployment", dep.metadata?.name ?: rsOwner.name ?: "", "Deployment:${rsOwner.uid}")
                        } else {
                            val rsUid = ownerRef.uid ?: return null
                            RootWorkload(rsUid, "ReplicaSet", rs.metadata?.name ?: ownerRef.name ?: "", "ReplicaSet:$rsUid")
                        }
                    } else {
                        val rsUid = ownerRef.uid ?: return null
                        RootWorkload(rsUid, "ReplicaSet", rs?.metadata?.name ?: ownerRef.name ?: "", "ReplicaSet:$rsUid")
                    }
                }

                "Job" -> {
                    val job = jobsByUid[ownerRef.uid]
                    val jobOwner = job?.metadata?.ownerReferences?.firstOrNull()
                    if (jobOwner?.kind == "CronJob") {
                        val cj = cronJobsByUid[jobOwner.uid]
                        if (cj != null) {
                            RootWorkload(jobOwner.uid, "CronJob", cj.metadata?.name ?: jobOwner.name ?: "", "CronJob:${jobOwner.uid}")
                        } else {
                            val jobUid = ownerRef.uid ?: return null
                            RootWorkload(jobUid, "Job", job.metadata?.name ?: ownerRef.name ?: "", "Job:$jobUid")
                        }
                    } else {
                        val jobUid = ownerRef.uid ?: return null
                        RootWorkload(jobUid, "Job", job?.metadata?.name ?: ownerRef.name ?: "", "Job:$jobUid")
                    }
                }

                "StatefulSet" -> {
                    val uid = ownerRef.uid ?: return null
                    val ss = statefulSetsByUid[uid]
                    RootWorkload(uid, "StatefulSet", ss?.metadata?.name ?: ownerRef.name ?: "", "StatefulSet:$uid")
                }

                "DaemonSet" -> {
                    val uid = ownerRef.uid ?: return null
                    val ds = daemonSetsByUid[uid]
                    RootWorkload(uid, "DaemonSet", ds?.metadata?.name ?: ownerRef.name ?: "", "DaemonSet:$uid")
                }

                "Deployment" -> {
                    val uid = ownerRef.uid ?: return null
                    val dep = deploymentsByUid[uid]
                    RootWorkload(uid, "Deployment", dep?.metadata?.name ?: ownerRef.name ?: "", "Deployment:$uid")
                }

                "CronJob" -> {
                    val uid = ownerRef.uid ?: return null
                    val cj = cronJobsByUid[uid]
                    RootWorkload(uid, "CronJob", cj?.metadata?.name ?: ownerRef.name ?: "", "CronJob:$uid")
                }

                in crdKinds -> {
                    val uid = ownerRef.uid ?: return null
                    val name = ownerRef.name ?: ""
                    RootWorkload(uid, ownerRef.kind, name, "${ownerRef.kind}:$uid")
                }

                else -> null
            }
        }

        // Group pods by root workload
        // Key: groupId (e.g. "WorkloadGroup:Deployment:depUid" or "WorkloadGroup:Standalone:ns")
        data class PodGroup(val root: RootWorkload?, val pods: MutableList<Pod> = mutableListOf())
        val groupsByKey = mutableMapOf<String, PodGroup>()

        for (pod in allPods) {
            val root = findRoot(pod)
            val podNs = pod.metadata?.namespace ?: ""
            val groupKey = if (root != null) {
                "WorkloadGroup:${root.kind}:${root.uid}"
            } else {
                "WorkloadGroup:Standalone:$podNs"
            }
            groupsByKey.getOrPut(groupKey) { PodGroup(root) }.pods.add(pod)
        }

        // Add WorkloadGroup nodes (and one Pod child node per pod, edged from the group).
        // Pod nodes are emitted into the graph so the WorkloadGroupCard expander can list
        // them; they are filtered out of the column layout in the UI so they don't pollute
        // the topology lanes — they only appear when their parent group is expanded.
        val groupNodeId = mutableMapOf<String, String>() // groupKey -> nodeId
        for ((groupKey, group) in groupsByKey) {
            val pods = group.pods
            val root = group.root
            val readyCount = pods.count { pod ->
                pod.status?.phase == "Running" &&
                    (pod.status?.containerStatuses?.all { it.ready == true } ?: false)
            }
            val totalCount = pods.size
            // When everything is healthy, show the compact ready ratio. Otherwise, show a
            // breakdown of effective pod statuses (Running, Pending, CrashLoopBackOff, …)
            // so the user can tell *why* the group is unhealthy at a glance.
            val statusStr = if (readyCount == totalCount) {
                "$readyCount/$totalCount ready"
            } else {
                val byStatus = pods.groupingBy { ResourceMappers.effectivePodStatus(it) }.eachCount()
                byStatus.entries
                    .sortedByDescending { it.value }
                    .joinToString(" · ") { (state, count) -> "$count $state" }
            }
            val rootKind = root?.kind ?: "Pod"
            val rootUid = root?.uid ?: (pods.firstOrNull()?.metadata?.namespace ?: "unknown")
            val nodeId = "WorkloadGroup:$rootKind:$rootUid"
            val name = root?.name ?: "standalone"
            val groupNs = pods.firstOrNull()?.metadata?.namespace
            // Primary container image of the first pod — gives the user "what's running"
            // without an extra click. We pick the first container for simplicity; if a pod
            // has sidecars they're not shown here (would bloat the card).
            val primaryImage = pods.firstOrNull()?.spec?.containers?.firstOrNull()?.image
            groupNodeId[groupKey] = nodeId
            addNode(
                ResourceGraphNode(
                    id = nodeId,
                    name = name,
                    kind = "WorkloadGroup",
                    status = statusStr,
                    namespace = groupNs,
                    subKind = rootKind,
                    image = primaryImage,
                ),
            )

            // Emit per-pod child nodes + edges. Pod IDs use UID so collisions are
            // impossible across namespaces.
            for (pod in pods) {
                val podUid = pod.metadata?.uid ?: continue
                val podId = "Pod:$podUid"
                val podRestarts = pod.status?.containerStatuses?.sumOf { it.restartCount ?: 0 } ?: 0
                addNode(
                    ResourceGraphNode(
                        id = podId,
                        name = pod.metadata?.name ?: "",
                        kind = "Pod",
                        status = ResourceMappers.effectivePodStatus(pod),
                        namespace = pod.metadata?.namespace,
                        restartCount = podRestarts,
                    ),
                )
                addEdge(nodeId, podId)
            }
        }

        // Step 4: Service -> WorkloadGroup edges
        for (svc in services) {
            val selector = svc.spec?.selector ?: continue
            if (selector.isEmpty()) continue
            val svcNs = svc.metadata?.namespace ?: ""
            val svcUid = svc.metadata?.uid ?: continue
            val svcId = "Service:$svcUid"
            val connectedGroups = mutableSetOf<String>()
            for (pod in allPods) {
                val podNs = pod.metadata?.namespace ?: ""
                if (podNs != svcNs) continue
                val podLabels = pod.metadata?.labels ?: emptyMap()
                if (!selector.all { (k, v) -> podLabels[k] == v }) continue
                // Find which group this pod belongs to
                val root = findRoot(pod)
                val groupKey = if (root != null) {
                    "WorkloadGroup:${root.kind}:${root.uid}"
                } else {
                    "WorkloadGroup:Standalone:$podNs"
                }
                val groupId = groupNodeId[groupKey] ?: continue
                connectedGroups.add(groupId)
            }
            for (groupId in connectedGroups) {
                addEdge(svcId, groupId)
            }
        }

        // Step 5: Mounts (volumes and service accounts per pod -> WorkloadGroup)
        // Cluster-scoped helpers (ConfigMap/Secret/PVC/ServiceAccount) are namespace-scoped in
        // K8s but were keyed by name only here, which collapsed same-named objects across
        // namespaces into a single node with cross-namespace edges. Qualify by namespace.
        val addedMountEdges = mutableSetOf<String>()
        for ((groupKey, group) in groupsByKey) {
            val groupId = groupNodeId[groupKey] ?: continue
            for (pod in group.pods) {
                val spec = pod.spec ?: continue
                val podNs = pod.metadata?.namespace ?: ""
                spec.volumes?.forEach { vol ->
                    vol.configMap?.name?.let { cmName ->
                        val cmId = "ConfigMap:$podNs/$cmName"
                        addNode(ResourceGraphNode(cmId, cmName, "ConfigMap", null, podNs))
                        val edgeKey = "$groupId->$cmId"
                        if (addedMountEdges.add(edgeKey)) addEdge(groupId, cmId)
                    }
                    vol.secret?.secretName?.let { secretName ->
                        val secretId = "Secret:$podNs/$secretName"
                        addNode(ResourceGraphNode(secretId, secretName, "Secret", null, podNs))
                        val edgeKey = "$groupId->$secretId"
                        if (addedMountEdges.add(edgeKey)) addEdge(groupId, secretId)
                    }
                    vol.persistentVolumeClaim?.claimName?.let { claimName ->
                        val pvcId = "PersistentVolumeClaim:$podNs/$claimName"
                        addNode(ResourceGraphNode(pvcId, claimName, "PersistentVolumeClaim", null, podNs))
                        val edgeKey = "$groupId->$pvcId"
                        if (addedMountEdges.add(edgeKey)) addEdge(groupId, pvcId)
                    }
                }
                val saName = spec.serviceAccountName?.takeIf { it.isNotBlank() && it != "default" }
                if (saName != null) {
                    val saId = "ServiceAccount:$podNs/$saName"
                    addNode(ResourceGraphNode(saId, saName, "ServiceAccount", null, podNs))
                    val edgeKey = "$groupId->$saId"
                    if (addedMountEdges.add(edgeKey)) addEdge(groupId, saId)
                }
            }
        }

        // Step 6: Scale guard. The 200-node limit applies only to nodes that occupy a
        // column lane in the UI — Pods are emitted into the graph but rendered as hidden
        // children of their WorkloadGroup, so they don't compete for visual space.
        val visibleCount = graphNodes.count { it.kind != "Pod" }
        if (visibleCount > 200) {
            val visibleKept = mutableListOf<ResourceGraphNode>()
            val keptIds = mutableSetOf<String>()
            for (n in graphNodes) {
                if (n.kind == "Pod") continue
                if (visibleKept.size >= 200) break
                visibleKept.add(n)
                keptIds.add(n.id)
            }
            // Keep child pods of any retained WorkloadGroup so the expander still works.
            val truncated = visibleKept + graphNodes.filter { it.kind == "Pod" && edges.any { e -> e.targetId == it.id && e.sourceId in keptIds } }
            val withWarning = truncated.toMutableList()
            withWarning.add(
                ResourceGraphNode(
                    id = "__truncated__",
                    name = "Graph truncated at 200 nodes",
                    kind = "Warning",
                    status = "Use namespace filter to zoom in",
                ),
            )
            return ResourceGraph(withWarning, edges)
        }

        return ResourceGraph(graphNodes, edges)
    }
}
