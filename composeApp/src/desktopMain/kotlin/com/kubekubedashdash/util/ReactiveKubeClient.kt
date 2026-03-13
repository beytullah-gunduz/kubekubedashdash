package com.kubekubedashdash.util

import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.ContainerInfo
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceGraphEdge
import com.kubekubedashdash.models.ResourceGraphNode
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.models.ServiceInfo
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveKubeClient(
    private val scope: CoroutineScope,
    private val connectionManager: KubeConnectionManager,
) : Closeable {

    private val log = LoggerFactory.getLogger(ReactiveKubeClient::class.java)

    // ── Connection management (delegated to KubeConnectionManager) ──────────────

    val isConnected: Boolean get() = connectionManager.isConnected

    private val k8s: KubernetesClient
        get() = connectionManager.client

    val connectionError: StateFlow<String?> = connectionManager.connectionError

    fun reportSuccess() = connectionManager.reportSuccess()
    fun reportError(message: String) = connectionManager.reportError(message)

    private val _connectionVersion: StateFlow<Long> = connectionManager.connectionVersion

    fun connect(context: String? = null): Result<String> = connectionManager.connect(context)

    fun connectMock(): Result<String> = try {
        val client = MockClusterProvider.start()
        connectionManager.connectWithClient(client, MockClusterProvider.MOCK_CONTEXT_NAME)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getContexts(): List<String> = connectionManager.getContexts()
    fun getCurrentContext(): String = connectionManager.getCurrentContext()
    fun getClusterServer(): String = connectionManager.getClusterServer()

    // ── Namespace selector ──────────────────────────────────────────────────────

    private val _selectedNamespace = MutableStateFlow<String?>(null)
    val selectedNamespace: StateFlow<String?> = _selectedNamespace.asStateFlow()

    fun setSelectedNamespace(namespace: String?) {
        log.info("Namespace selection changed to: {}", namespace ?: "<all namespaces>")
        _selectedNamespace.value = namespace
    }

    // ── Informer-based flow helpers ────────────────────────────────────────────

    private fun <R : HasMetadata, T> informerFlow(
        inform: (KubernetesClient, ResourceEventHandler<R>) -> SharedIndexInformer<R>,
        mapper: (R) -> T,
    ): StateFlow<ResourceState<List<T>>> = _connectionVersion
        .flatMapLatest {
            channelFlow {
                send(ResourceState.Loading)
                try {
                    val emitSignal = Channel<Unit>(Channel.CONFLATED)
                    log.debug("Starting cluster-scoped informer")
                    val informer = inform(
                        k8s,
                        object : ResourceEventHandler<R> {
                            override fun onAdd(obj: R) {
                                log.trace("Informer event: ADD {}/{}", obj.kind, obj.metadata?.name)
                                emitSignal.trySend(Unit)
                            }
                            override fun onUpdate(oldObj: R, newObj: R) {
                                log.trace("Informer event: UPDATE {}/{}", newObj.kind, newObj.metadata?.name)
                                emitSignal.trySend(Unit)
                            }
                            override fun onDelete(obj: R, deletedFinalStateUnknown: Boolean) {
                                log.trace("Informer event: DELETE {}/{} (finalStateUnknown={})", obj.kind, obj.metadata?.name, deletedFinalStateUnknown)
                                emitSignal.trySend(Unit)
                            }
                        },
                    )
                    launch {
                        for (signal in emitSignal) {
                            delay(100)
                            try {
                                val items = informer.store.list()
                                log.debug("Informer emitting {} items from store", items.size)
                                send(ResourceState.Success(items.map(mapper)))
                                reportSuccess()
                            } catch (e: Exception) {
                                log.warn("Informer failed to map store contents: {}", e.message)
                                reportError(e.message ?: "Unknown error")
                            }
                        }
                    }
                    while (!informer.hasSynced()) delay(50)
                    val items = informer.store.list()
                    log.info("Cluster-scoped informer synced with {} items", items.size)
                    send(ResourceState.Success(items.map(mapper)))
                    reportSuccess()
                    try {
                        awaitCancellation()
                    } finally {
                        log.debug("Closing cluster-scoped informer")
                        informer.close()
                    }
                } catch (e: Exception) {
                    log.error("Cluster-scoped informer failed: {}", e.message)
                    reportError(e.message ?: "Unknown error")
                    send(ResourceState.Error(e.message ?: "Unknown error"))
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    private fun <R : HasMetadata, T> namespacedInformerFlow(
        inform: (KubernetesClient, String?, ResourceEventHandler<R>) -> SharedIndexInformer<R>,
        mapper: (R) -> T,
    ): StateFlow<ResourceState<List<T>>> = combine(_selectedNamespace, _connectionVersion) { ns, _ -> ns }
        .flatMapLatest { ns ->
            channelFlow {
                send(ResourceState.Loading)
                try {
                    val emitSignal = Channel<Unit>(Channel.CONFLATED)
                    val nsLabel = ns ?: "<all namespaces>"
                    log.debug("Starting namespaced informer for namespace={}", nsLabel)
                    val informer = inform(
                        k8s,
                        ns,
                        object : ResourceEventHandler<R> {
                            override fun onAdd(obj: R) {
                                log.trace("Informer event: ADD {}/{} in namespace={}", obj.kind, obj.metadata?.name, nsLabel)
                                emitSignal.trySend(Unit)
                            }
                            override fun onUpdate(oldObj: R, newObj: R) {
                                log.trace("Informer event: UPDATE {}/{} in namespace={}", newObj.kind, newObj.metadata?.name, nsLabel)
                                emitSignal.trySend(Unit)
                            }
                            override fun onDelete(obj: R, deletedFinalStateUnknown: Boolean) {
                                log.trace("Informer event: DELETE {}/{} in namespace={} (finalStateUnknown={})", obj.kind, obj.metadata?.name, nsLabel, deletedFinalStateUnknown)
                                emitSignal.trySend(Unit)
                            }
                        },
                    )
                    launch {
                        for (signal in emitSignal) {
                            delay(100)
                            try {
                                val items = informer.store.list()
                                log.debug("Namespaced informer emitting {} items for namespace={}", items.size, nsLabel)
                                send(ResourceState.Success(items.map(mapper)))
                                reportSuccess()
                            } catch (e: Exception) {
                                log.warn("Namespaced informer failed to map store contents for namespace={}: {}", nsLabel, e.message)
                                reportError(e.message ?: "Unknown error")
                            }
                        }
                    }
                    while (!informer.hasSynced()) delay(50)
                    val items = informer.store.list()
                    log.info("Namespaced informer synced with {} items for namespace={}", items.size, nsLabel)
                    send(ResourceState.Success(items.map(mapper)))
                    reportSuccess()
                    try {
                        awaitCancellation()
                    } finally {
                        log.debug("Closing namespaced informer for namespace={}", nsLabel)
                        informer.close()
                    }
                } catch (e: Exception) {
                    log.error("Namespaced informer failed for namespace={}: {}", ns ?: "<all>", e.message)
                    reportError(e.message ?: "Unknown error")
                    send(ResourceState.Error(e.message ?: "Unknown error"))
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    // ── Polling helpers (for non-watchable resources) ────────────────────────────

    private fun <T> namespacedPollingStateFlow(
        intervalMs: Long = 5_000,
        fetch: (namespace: String?) -> T,
    ): StateFlow<ResourceState<T>> = combine(_selectedNamespace, _connectionVersion) { ns, _ -> ns }
        .flatMapLatest { ns ->
            flow {
                emit(ResourceState.Loading)
                var loaded = false
                while (true) {
                    try {
                        val data = fetch(ns)
                        reportSuccess()
                        emit(ResourceState.Success(data))
                        loaded = true
                        log.trace("Polling fetch succeeded for namespace={}", ns ?: "<all>")
                    } catch (e: Exception) {
                        log.warn("Polling fetch failed for namespace={}: {}", ns ?: "<all>", e.message)
                        reportError(e.message ?: "Unknown error")
                        if (!loaded) emit(ResourceState.Error(e.message ?: "Unknown error"))
                    }
                    delay(intervalMs)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ResourceState.Loading)

    // ── Mapping: Pods ───────────────────────────────────────────────────────────

    private fun mapPod(pod: Pod): PodInfo {
        val containers = pod.spec?.containers?.map { c ->
            val cs = pod.status?.containerStatuses?.find { it.name == c.name }
            ContainerInfo(
                name = c.name,
                image = c.image ?: "",
                ready = cs?.ready ?: false,
                restartCount = cs?.restartCount ?: 0,
                state = when {
                    cs?.state?.running != null -> "Running"
                    cs?.state?.waiting != null -> cs.state.waiting.reason ?: "Waiting"
                    cs?.state?.terminated != null -> cs.state.terminated.reason ?: "Terminated"
                    else -> "Unknown"
                },
            )
        } ?: emptyList()
        return PodInfo(
            uid = pod.metadata.uid ?: "",
            name = pod.metadata.name,
            namespace = pod.metadata.namespace ?: "",
            status = effectivePodStatus(pod),
            ready = "${containers.count { it.ready }}/${containers.size}",
            restarts = containers.sumOf { it.restartCount },
            age = formatAge(pod.metadata.creationTimestamp),
            node = pod.spec?.nodeName ?: "<none>",
            ip = pod.status?.podIP ?: "<none>",
            labels = pod.metadata.labels ?: emptyMap(),
            containers = containers,
        )
    }

    private fun effectivePodStatus(pod: Pod): String {
        val phase = pod.status?.phase ?: return "Unknown"
        pod.status?.containerStatuses?.forEach { cs ->
            cs.state?.waiting?.reason?.let { return it }
            cs.state?.terminated?.reason?.let { if (phase != "Succeeded") return it }
        }
        return phase
    }

    // ── Mapping: Events ─────────────────────────────────────────────────────────

    private fun mapEvent(ev: io.fabric8.kubernetes.api.model.Event): EventInfo {
        val lastTs = ev.lastTimestamp ?: ev.metadata?.creationTimestamp ?: ""
        val objKind = ev.involvedObject?.kind ?: ""
        val objName = ev.involvedObject?.name ?: ""
        return EventInfo(
            uid = ev.metadata?.uid ?: "",
            type = ev.type ?: "Normal",
            reason = ev.reason ?: "",
            objectRef = "$objKind/$objName",
            objectKind = objKind,
            objectName = objName,
            message = ev.message ?: "",
            count = ev.count ?: 1,
            firstSeen = formatAge(ev.firstTimestamp ?: ev.metadata?.creationTimestamp),
            lastSeen = formatAge(lastTs),
            lastSeenTimestamp = lastTs,
            namespace = ev.metadata?.namespace ?: "",
            node = ev.source?.host ?: "",
        )
    }

    // ── Contexts (on-demand) ────────────────────────────────────────────────────

    private val _contexts = MutableStateFlow<ResourceState<List<String>>>(ResourceState.Loading)
    val contexts: StateFlow<ResourceState<List<String>>> = _contexts.asStateFlow()

    private val _currentContext = MutableStateFlow<ResourceState<String>>(ResourceState.Loading)
    val currentContext: StateFlow<ResourceState<String>> = _currentContext.asStateFlow()

    fun refreshContexts() {
        log.debug("Refreshing kube contexts")
        try {
            _contexts.value = ResourceState.Success(getContexts())
            _currentContext.value = ResourceState.Success(getCurrentContext())
            log.debug("Contexts refreshed successfully")
        } catch (e: Exception) {
            log.error("Failed to refresh contexts: {}", e.message)
            _contexts.value = ResourceState.Error(e.message ?: "Unknown error")
            _currentContext.value = ResourceState.Error(e.message ?: "Unknown error")
        }
    }

    // ── Namespaces ──────────────────────────────────────────────────────────────

    val namespaceNames: StateFlow<ResourceState<List<String>>> = informerFlow(
        inform = { k, h -> k.namespaces().inform(h) },
        mapper = { ns -> ns.metadata.name },
    )

    val namespaces: StateFlow<ResourceState<List<GenericResourceInfo>>> = informerFlow(
        inform = { k, h -> k.namespaces().inform(h) },
        mapper = { ns ->
            GenericResourceInfo(
                uid = ns.metadata.uid ?: "",
                name = ns.metadata.name,
                namespace = null,
                status = ns.status?.phase ?: "Active",
                age = formatAge(ns.metadata.creationTimestamp),
                labels = ns.metadata.labels ?: emptyMap(),
            )
        },
    )

    // ── Pods ────────────────────────────────────────────────────────────────────

    val pods: StateFlow<ResourceState<List<PodInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.pods().inNamespace(ns).inform(h)
            } else {
                k.pods().inAnyNamespace().inform(h)
            }
        },
        mapper = ::mapPod,
    )

    // ── Deployments ─────────────────────────────────────────────────────────────

    val deployments: StateFlow<ResourceState<List<DeploymentInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.apps().deployments().inNamespace(ns).inform(h)
            } else {
                k.apps().deployments().inAnyNamespace().inform(h)
            }
        },
        mapper = { dep ->
            val ready = dep.status?.readyReplicas ?: 0
            val desired = dep.spec?.replicas ?: 0
            DeploymentInfo(
                uid = dep.metadata.uid ?: "", name = dep.metadata.name,
                namespace = dep.metadata.namespace ?: "",
                ready = "$ready/$desired",
                upToDate = dep.status?.updatedReplicas ?: 0,
                available = dep.status?.availableReplicas ?: 0,
                age = formatAge(dep.metadata.creationTimestamp),
                strategy = dep.spec?.strategy?.type ?: "",
                labels = dep.metadata.labels ?: emptyMap(),
                conditions = dep.status?.conditions?.map { "${it.type}=${it.status}" } ?: emptyList(),
            )
        },
    )

    // ── Services ────────────────────────────────────────────────────────────────

    val services: StateFlow<ResourceState<List<ServiceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.services().inNamespace(ns).inform(h)
            } else {
                k.services().inAnyNamespace().inform(h)
            }
        },
        mapper = { svc ->
            val ports = svc.spec?.ports?.joinToString(", ") { p ->
                val np = if (p.nodePort != null && p.nodePort > 0) ":${p.nodePort}" else ""
                "${p.port}$np/${p.protocol}"
            } ?: ""
            ServiceInfo(
                uid = svc.metadata.uid ?: "", name = svc.metadata.name,
                namespace = svc.metadata.namespace ?: "",
                type = svc.spec?.type ?: "", clusterIP = svc.spec?.clusterIP ?: "",
                ports = ports, age = formatAge(svc.metadata.creationTimestamp),
                selector = svc.spec?.selector ?: emptyMap(),
                labels = svc.metadata.labels ?: emptyMap(),
            )
        },
    )

    // ── Nodes ───────────────────────────────────────────────────────────────────

    val nodes: StateFlow<ResourceState<List<NodeInfo>>> = informerFlow(
        inform = { k, h -> k.nodes().inform(h) },
        mapper = { node ->
            val readyCond = node.status?.conditions?.find { it.type == "Ready" }
            val roles = node.metadata.labels
                ?.filterKeys { it.startsWith("node-role.kubernetes.io/") }
                ?.keys?.map { it.removePrefix("node-role.kubernetes.io/") }
                ?.joinToString(", ")?.ifEmpty { "<none>" } ?: "<none>"
            val alloc = node.status?.allocatable
            NodeInfo(
                uid = node.metadata.uid ?: "", name = node.metadata.name,
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
                labels = node.metadata.labels ?: emptyMap(),
            )
        },
    )

    // ── Events ──────────────────────────────────────────────────────────────────

    val events: StateFlow<ResourceState<List<EventInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.v1().events().inNamespace(ns).inform(h)
            } else {
                k.v1().events().inAnyNamespace().inform(h)
            }
        },
        mapper = ::mapEvent,
    )

    // ── ConfigMaps ──────────────────────────────────────────────────────────────

    val configMaps: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.configMaps().inNamespace(ns).inform(h)
            } else {
                k.configMaps().inAnyNamespace().inform(h)
            }
        },
        mapper = { cm ->
            GenericResourceInfo(
                uid = cm.metadata.uid ?: "",
                name = cm.metadata.name,
                namespace = cm.metadata.namespace,
                status = null,
                age = formatAge(cm.metadata.creationTimestamp),
                labels = cm.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Data" to "${(cm.data?.size ?: 0) + (cm.binaryData?.size ?: 0)}"),
            )
        },
    )

    // ── Secrets ─────────────────────────────────────────────────────────────────

    val secrets: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.secrets().inNamespace(ns).inform(h)
            } else {
                k.secrets().inAnyNamespace().inform(h)
            }
        },
        mapper = { s ->
            GenericResourceInfo(
                uid = s.metadata.uid ?: "",
                name = s.metadata.name,
                namespace = s.metadata.namespace,
                status = null,
                age = formatAge(s.metadata.creationTimestamp),
                labels = s.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Type" to (s.type ?: ""), "Data" to "${s.data?.size ?: 0}"),
            )
        },
    )

    // ── StatefulSets ────────────────────────────────────────────────────────────

    val statefulSets: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.apps().statefulSets().inNamespace(ns).inform(h)
            } else {
                k.apps().statefulSets().inAnyNamespace().inform(h)
            }
        },
        mapper = { ss ->
            val r = ss.status?.readyReplicas ?: 0
            val d = ss.spec?.replicas ?: 0
            GenericResourceInfo(
                uid = ss.metadata.uid ?: "",
                name = ss.metadata.name,
                namespace = ss.metadata.namespace,
                status = "$r/$d",
                age = formatAge(ss.metadata.creationTimestamp),
                labels = ss.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Ready" to "$r/$d"),
            )
        },
    )

    // ── DaemonSets ──────────────────────────────────────────────────────────────

    val daemonSets: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.apps().daemonSets().inNamespace(ns).inform(h)
            } else {
                k.apps().daemonSets().inAnyNamespace().inform(h)
            }
        },
        mapper = { ds ->
            val desired = ds.status?.desiredNumberScheduled ?: 0
            val ready = ds.status?.numberReady ?: 0
            GenericResourceInfo(
                uid = ds.metadata.uid ?: "",
                name = ds.metadata.name,
                namespace = ds.metadata.namespace,
                status = "$ready/$desired",
                age = formatAge(ds.metadata.creationTimestamp),
                labels = ds.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Desired" to "$desired", "Ready" to "$ready"),
            )
        },
    )

    // ── ReplicaSets ─────────────────────────────────────────────────────────────

    val replicaSets: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.apps().replicaSets().inNamespace(ns).inform(h)
            } else {
                k.apps().replicaSets().inAnyNamespace().inform(h)
            }
        },
        mapper = { rs ->
            val r = rs.status?.readyReplicas ?: 0
            val d = rs.spec?.replicas ?: 0
            GenericResourceInfo(
                uid = rs.metadata.uid ?: "",
                name = rs.metadata.name,
                namespace = rs.metadata.namespace,
                status = "$r/$d",
                age = formatAge(rs.metadata.creationTimestamp),
                labels = rs.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Ready" to "$r/$d"),
            )
        },
    )

    // ── Jobs ────────────────────────────────────────────────────────────────────

    val jobs: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.batch().v1().jobs().inNamespace(ns).inform(h)
            } else {
                k.batch().v1().jobs().inAnyNamespace().inform(h)
            }
        },
        mapper = { job ->
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
                extraColumns = mapOf("Completions" to "$succ/$comp", "Status" to status),
            )
        },
    )

    // ── CronJobs ────────────────────────────────────────────────────────────────

    val cronJobs: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.batch().v1().cronjobs().inNamespace(ns).inform(h)
            } else {
                k.batch().v1().cronjobs().inAnyNamespace().inform(h)
            }
        },
        mapper = { cj ->
            GenericResourceInfo(
                uid = cj.metadata.uid ?: "",
                name = cj.metadata.name,
                namespace = cj.metadata.namespace,
                status = if (cj.spec?.suspend == true) "Suspended" else "Active",
                age = formatAge(cj.metadata.creationTimestamp),
                labels = cj.metadata.labels ?: emptyMap(),
                extraColumns = mapOf(
                    "Schedule" to (cj.spec?.schedule ?: ""),
                    "Active" to "${cj.status?.active?.size ?: 0}",
                ),
            )
        },
    )

    // ── Ingresses ───────────────────────────────────────────────────────────────

    val ingresses: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.network().v1().ingresses().inNamespace(ns).inform(h)
            } else {
                k.network().v1().ingresses().inAnyNamespace().inform(h)
            }
        },
        mapper = { ing ->
            val hosts = ing.spec?.rules?.mapNotNull { it.host }?.joinToString(", ") ?: ""
            GenericResourceInfo(
                uid = ing.metadata.uid ?: "",
                name = ing.metadata.name,
                namespace = ing.metadata.namespace,
                status = null,
                age = formatAge(ing.metadata.creationTimestamp),
                labels = ing.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Hosts" to hosts),
            )
        },
    )

    // ── Endpoints ───────────────────────────────────────────────────────────────

    val endpoints: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.endpoints().inNamespace(ns).inform(h)
            } else {
                k.endpoints().inAnyNamespace().inform(h)
            }
        },
        mapper = { ep ->
            val count = ep.subsets?.flatMap { it.addresses ?: emptyList() }?.size ?: 0
            GenericResourceInfo(
                uid = ep.metadata.uid ?: "",
                name = ep.metadata.name,
                namespace = ep.metadata.namespace,
                status = null,
                age = formatAge(ep.metadata.creationTimestamp),
                labels = ep.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Endpoints" to "$count"),
            )
        },
    )

    // ── NetworkPolicies ─────────────────────────────────────────────────────────

    val networkPolicies: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.network().v1().networkPolicies().inNamespace(ns).inform(h)
            } else {
                k.network().v1().networkPolicies().inAnyNamespace().inform(h)
            }
        },
        mapper = { np ->
            GenericResourceInfo(
                uid = np.metadata.uid ?: "",
                name = np.metadata.name,
                namespace = np.metadata.namespace,
                status = null,
                age = formatAge(np.metadata.creationTimestamp),
                labels = np.metadata.labels ?: emptyMap(),
                extraColumns = mapOf("Policy Types" to (np.spec?.policyTypes?.joinToString(", ") ?: "")),
            )
        },
    )

    // ── PersistentVolumes ───────────────────────────────────────────────────────

    val persistentVolumes: StateFlow<ResourceState<List<GenericResourceInfo>>> = informerFlow(
        inform = { k, h -> k.persistentVolumes().inform(h) },
        mapper = { pv ->
            GenericResourceInfo(
                uid = pv.metadata.uid ?: "",
                name = pv.metadata.name,
                namespace = null,
                status = pv.status?.phase,
                age = formatAge(pv.metadata.creationTimestamp),
                labels = pv.metadata.labels ?: emptyMap(),
                extraColumns = mapOf(
                    "Capacity" to (pv.spec?.capacity?.get("storage")?.toString() ?: ""),
                    "Access Modes" to (pv.spec?.accessModes?.joinToString(", ") ?: ""),
                    "Reclaim" to (pv.spec?.persistentVolumeReclaimPolicy ?: ""),
                    "Claim" to (pv.spec?.claimRef?.let { "${it.namespace}/${it.name}" } ?: ""),
                ),
            )
        },
    )

    // ── PersistentVolumeClaims ──────────────────────────────────────────────────

    val persistentVolumeClaims: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.persistentVolumeClaims().inNamespace(ns).inform(h)
            } else {
                k.persistentVolumeClaims().inAnyNamespace().inform(h)
            }
        },
        mapper = { pvc ->
            GenericResourceInfo(
                uid = pvc.metadata.uid ?: "",
                name = pvc.metadata.name,
                namespace = pvc.metadata.namespace,
                status = pvc.status?.phase,
                age = formatAge(pvc.metadata.creationTimestamp),
                labels = pvc.metadata.labels ?: emptyMap(),
                extraColumns = mapOf(
                    "Capacity" to (pvc.status?.capacity?.get("storage")?.toString() ?: ""),
                    "Access Modes" to (pvc.status?.accessModes?.joinToString(", ") ?: ""),
                    "Storage Class" to (pvc.spec?.storageClassName ?: ""),
                    "Volume" to (pvc.spec?.volumeName ?: ""),
                ),
            )
        },
    )

    // ── StorageClasses ──────────────────────────────────────────────────────────

    val storageClasses: StateFlow<ResourceState<List<GenericResourceInfo>>> = informerFlow(
        inform = { k, h -> k.storage().v1().storageClasses().inform(h) },
        mapper = { sc ->
            val isDefault = sc.metadata.annotations
                ?.containsKey("storageclass.kubernetes.io/is-default-class") == true
            GenericResourceInfo(
                uid = sc.metadata.uid ?: "",
                name = sc.metadata.name,
                namespace = null,
                status = if (isDefault) "Default" else null,
                age = formatAge(sc.metadata.creationTimestamp),
                labels = sc.metadata.labels ?: emptyMap(),
                extraColumns = mapOf(
                    "Provisioner" to (sc.provisioner ?: ""),
                    "Reclaim Policy" to (sc.reclaimPolicy ?: ""),
                    "Binding Mode" to (sc.volumeBindingMode ?: ""),
                ),
            )
        },
    )

    // ── Cluster Info (polling — composite) ──────────────────────────────────────

    val clusterInfo: StateFlow<ResourceState<ClusterInfo>> = namespacedPollingStateFlow(
        intervalMs = 10_000,
        fetch = { ns ->
            val version = try {
                val v = k8s.kubernetesVersion
                "${v.major}.${v.minor}"
            } catch (_: Exception) {
                "mock"
            }
            val nodeItems = k8s.nodes().list().items
            val nsItems = k8s.namespaces().list().items
            val podItems = if (ns != null) {
                k8s.pods().inNamespace(ns).list().items
            } else {
                k8s.pods().inAnyNamespace().list().items
            }
            val deps = if (ns != null) {
                k8s.apps().deployments().inNamespace(ns).list().items
            } else {
                k8s.apps().deployments().inAnyNamespace().list().items
            }
            val svcs = if (ns != null) {
                k8s.services().inNamespace(ns).list().items
            } else {
                k8s.services().inAnyNamespace().list().items
            }
            ClusterInfo(
                name = getCurrentContext(), server = getClusterServer(),
                version = version,
                nodesCount = nodeItems.size, namespacesCount = nsItems.size,
                podsCount = podItems.size, deploymentsCount = deps.size, servicesCount = svcs.size,
                runningPods = podItems.count { it.status?.phase == "Running" },
                pendingPods = podItems.count { it.status?.phase == "Pending" },
                failedPods = podItems.count { it.status?.phase == "Failed" },
                succeededPods = podItems.count { it.status?.phase == "Succeeded" },
            )
        },
    )

    // ── Resource Usage (polling — metrics server has no watch API) ───────────────

    val resourceUsage: StateFlow<ResourceState<ResourceUsageSummary>> = namespacedPollingStateFlow(
        intervalMs = 10_000,
        fetch = { ns ->
            val podMetricItems = try {
                if (ns != null) {
                    k8s.top().pods().inNamespace(ns).metrics().items ?: emptyList()
                } else {
                    k8s.top().pods().metrics().items ?: emptyList()
                }
            } catch (_: Exception) {
                return@namespacedPollingStateFlow ResourceUsageSummary(0, 0, 0, 0, metricsAvailable = false)
            }
            var cpuUsed = 0L
            var memUsed = 0L
            for (pm in podMetricItems) {
                for (c in pm.containers ?: emptyList()) {
                    cpuUsed += parseCpuToMillis(c.usage?.get("cpu")?.toString() ?: "0")
                    memUsed += parseMemoryToBytes(c.usage?.get("memory")?.toString() ?: "0")
                }
            }
            val nodeItems = try {
                k8s.nodes().list().items ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            var cpuCap = 0L
            var memCap = 0L
            for (node in nodeItems) {
                val alloc = node.status?.allocatable ?: continue
                cpuCap += parseCpuToMillis(alloc["cpu"]?.toString() ?: "0")
                memCap += parseMemoryToBytes(alloc["memory"]?.toString() ?: "0")
            }
            ResourceUsageSummary(cpuUsed, cpuCap, memUsed, memCap, metricsAvailable = true)
        },
    )

    // ── On-demand: YAML ─────────────────────────────────────────────────────────

    fun getResourceYaml(kind: String, name: String, namespace: String?): String = try {
        log.debug("Fetching YAML kind={} name={} namespace={}", kind, name, namespace)
        val res: Any? = when (kind.lowercase()) {
            "pod" -> namespace?.let { k8s.pods().inNamespace(it).withName(name).get() }
            "deployment" -> namespace?.let { k8s.apps().deployments().inNamespace(it).withName(name).get() }
            "service" -> namespace?.let { k8s.services().inNamespace(it).withName(name).get() }
            "node" -> k8s.nodes().withName(name).get()
            "namespace" -> k8s.namespaces().withName(name).get()
            "configmap" -> namespace?.let { k8s.configMaps().inNamespace(it).withName(name).get() }
            "secret" -> namespace?.let { k8s.secrets().inNamespace(it).withName(name).get() }
            "statefulset" -> namespace?.let { k8s.apps().statefulSets().inNamespace(it).withName(name).get() }
            "daemonset" -> namespace?.let { k8s.apps().daemonSets().inNamespace(it).withName(name).get() }
            "replicaset" -> namespace?.let { k8s.apps().replicaSets().inNamespace(it).withName(name).get() }
            "job" -> namespace?.let { k8s.batch().v1().jobs().inNamespace(it).withName(name).get() }
            "cronjob" -> namespace?.let { k8s.batch().v1().cronjobs().inNamespace(it).withName(name).get() }
            "ingress" -> namespace?.let { k8s.network().v1().ingresses().inNamespace(it).withName(name).get() }
            "persistentvolume" -> k8s.persistentVolumes().withName(name).get()
            "persistentvolumeclaim" -> namespace?.let { k8s.persistentVolumeClaims().inNamespace(it).withName(name).get() }
            "storageclass" -> k8s.storage().v1().storageClasses().withName(name).get()
            else -> null
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

    // ── On-demand: Pod Logs ─────────────────────────────────────────────────────

    fun getPodLogs(name: String, namespace: String, container: String?, tailLines: Int = 1000): String = try {
        log.debug("Fetching pod logs pod={} namespace={} container={} tailLines={}", name, namespace, container, tailLines)
        val op = k8s.pods().inNamespace(namespace).withName(name)
        val withC = if (container != null) op.inContainer(container) else op
        withC.tailingLines(tailLines).log ?: ""
    } catch (e: Exception) {
        log.error("Failed to fetch pod logs pod={} namespace={}: {}", name, namespace, e.message)
        "Error fetching logs: ${e.message}"
    }

    fun streamPodLogs(name: String, namespace: String, container: String?): Flow<String> = callbackFlow {
        log.info("Starting log stream pod={} namespace={} container={}", name, namespace, container)
        val watch = try {
            val op = k8s.pods().inNamespace(namespace).withName(name)
            val withC = if (container != null) op.inContainer(container) else op
            withC.tailingLines(100).watchLog()
        } catch (e: Exception) {
            log.error("Failed to start log stream pod={} namespace={}: {}", name, namespace, e.message)
            trySend("Error: ${e.message}")
            close()
            return@callbackFlow
        }
        launch(Dispatchers.IO) {
            try {
                watch.output.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { trySend(it) }
                }
            } catch (e: Exception) {
                log.debug("Log stream ended pod={} namespace={}: {}", name, namespace, e.message)
            }
        }
        awaitClose {
            log.debug("Closing log stream pod={} namespace={}", name, namespace)
            watch.close()
        }
    }

    // ── On-demand: Delete ───────────────────────────────────────────────────────

    fun deleteResource(kind: String, name: String, namespace: String?): Result<Unit> = try {
        log.info("Deleting resource kind={} name={} namespace={}", kind, name, namespace)
        when (kind.lowercase()) {
            "pod" -> namespace?.let { k8s.pods().inNamespace(it).withName(name).delete() }
            "deployment" -> namespace?.let { k8s.apps().deployments().inNamespace(it).withName(name).delete() }
            "service" -> namespace?.let { k8s.services().inNamespace(it).withName(name).delete() }
            "configmap" -> namespace?.let { k8s.configMaps().inNamespace(it).withName(name).delete() }
            "secret" -> namespace?.let { k8s.secrets().inNamespace(it).withName(name).delete() }
            "job" -> namespace?.let { k8s.batch().v1().jobs().inNamespace(it).withName(name).delete() }
            "cronjob" -> namespace?.let { k8s.batch().v1().cronjobs().inNamespace(it).withName(name).delete() }
            else -> throw IllegalArgumentException("Delete not supported for $kind")
        }
        log.info("Deleted resource kind={} name={} namespace={}", kind, name, namespace)
        Result.success(Unit)
    } catch (e: Exception) {
        log.error("Failed to delete resource kind={} name={} namespace={}: {}", kind, name, namespace, e.message)
        Result.failure(e)
    }

    // ── On-demand: Pod Metrics ──────────────────────────────────────────────────

    fun getPodMetrics(name: String, namespace: String): PodMetricsSnapshot? = try {
        log.debug("Fetching metrics for pod={} namespace={}", name, namespace)
        val podMetricItems = k8s.top().pods().inNamespace(namespace).metrics().items
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
            null
        }
    } catch (e: Exception) {
        log.debug("Metrics server unavailable for pod={}: {}", name, e.message)
        null
    }

    // ── On-demand: Pods by Node / Events for Node ───────────────────────────────

    fun getPodsByNode(nodeName: String): List<PodInfo> {
        log.debug("Fetching pods for node={}", nodeName)
        return k8s.pods().inAnyNamespace().list().items
            .filter { it.spec?.nodeName == nodeName || it.status?.nominatedNodeName == nodeName }
            .map(::mapPod)
            .also { log.debug("Found {} pods on node={}", it.size, nodeName) }
    }

    fun getEventsForNode(nodeName: String): List<EventInfo> {
        log.debug("Fetching events for node={}", nodeName)
        return k8s.v1().events().inAnyNamespace().list().items
            .filter { it.involvedObject?.kind == "Node" && it.involvedObject?.name == nodeName }
            .sortedByDescending { it.metadata?.creationTimestamp }
            .map(::mapEvent)
            .also { log.debug("Found {} events for node={}", it.size, nodeName) }
    }

    fun getEventsOnNode(nodeName: String): List<EventInfo> {
        log.debug("Fetching events on node={}", nodeName)
        return k8s.v1().events().inAnyNamespace().list().items
            .filter { it.source?.host == nodeName }
            .sortedByDescending { it.metadata?.creationTimestamp }
            .map(::mapEvent)
            .also { log.debug("Found {} events on node={}", it.size, nodeName) }
    }

    fun getEventsForObject(kind: String, name: String, namespace: String?): List<EventInfo> {
        log.debug("Fetching events for object kind={} name={} namespace={}", kind, name, namespace)
        return k8s.v1().events().inAnyNamespace().list().items
            .filter { it.involvedObject?.kind == kind && it.involvedObject?.name == name }
            .sortedByDescending { it.metadata?.creationTimestamp }
            .map(::mapEvent)
            .also { log.debug("Found {} events for {}/{}", it.size, kind, name) }
    }

    fun getPodByName(name: String, namespace: String): PodInfo? {
        log.debug("Fetching pod name={} namespace={}", name, namespace)
        return try {
            k8s.pods().inNamespace(namespace).withName(name).get()?.let(::mapPod)
        } catch (e: Exception) {
            log.warn("Failed to fetch pod name={} namespace={}: {}", name, namespace, e.message)
            null
        }
    }

    // ── On-demand: Deployment Resource Graph ────────────────────────────────────

    fun getDeploymentResourceGraph(name: String, namespace: String): ResourceGraph {
        log.debug("Building resource graph for deployment={} namespace={}", name, namespace)
        val deployment = k8s.apps().deployments().inNamespace(namespace).withName(name).get()
            ?: run {
                log.warn("Deployment not found name={} namespace={}", name, namespace)
                return ResourceGraph(emptyList(), emptyList())
            }

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

        try {
            val allRS = k8s.apps().replicaSets().inNamespace(namespace).list().items ?: emptyList()
            val ownedRS = allRS.filter { rs -> rs.metadata?.ownerReferences?.any { it.uid == depUid } == true }
            val allPods = k8s.pods().inNamespace(namespace).list().items ?: emptyList()
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
                    addNode(ResourceGraphNode(podId, pod.metadata.name, "Pod", effectivePodStatus(pod)))
                    edges.add(ResourceGraphEdge(rsId, podId))
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch ReplicaSets/Pods for deployment graph: {}", e.message)
        }

        try {
            val allSvcs = k8s.services().inNamespace(namespace).list().items ?: emptyList()
            for (svc in allSvcs) {
                val selector = svc.spec?.selector ?: continue
                if (selector.isEmpty()) continue
                if (!selector.all { (k, v) -> matchLabels[k] == v }) continue
                val svcUid = svc.metadata?.uid ?: continue
                val svcId = "Service:$svcUid"
                addNode(ResourceGraphNode(svcId, svc.metadata.name, "Service", svc.spec?.type))
                edges.add(ResourceGraphEdge(svcId, depId))
                try {
                    val allIngresses = k8s.network().v1().ingresses().inNamespace(namespace).list().items ?: emptyList()
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
                } catch (e: Exception) {
                    log.warn("Failed to fetch Ingresses for service graph: {}", e.message)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch Services for deployment graph: {}", e.message)
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

        try {
            val hpas = k8s.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).list().items ?: emptyList()
            for (hpa in hpas) {
                if (hpa.spec?.scaleTargetRef?.kind == "Deployment" && hpa.spec?.scaleTargetRef?.name == name) {
                    val hpaUid = hpa.metadata?.uid ?: continue
                    val hpaId = "HPA:$hpaUid"
                    addNode(ResourceGraphNode(hpaId, hpa.metadata.name, "HPA", null))
                    edges.add(ResourceGraphEdge(hpaId, depId))
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch HPAs for deployment graph: {}", e.message)
        }

        log.debug("Resource graph built: {} nodes, {} edges", graphNodes.size, edges.size)
        return ResourceGraph(graphNodes, edges)
    }

    // ── Closeable ───────────────────────────────────────────────────────────────

    override fun close() {
        // Connection lifecycle is managed by KubeConnectionManager
    }
}
