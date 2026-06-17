package com.kubekubedashdash.util

import com.kubekubedashdash.models.ClusterInfo
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.NodeResourceUsage
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.PodMetricsSnapshot
import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ResourceUsageSummary
import com.kubekubedashdash.models.ServiceInfo
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.fabric8.kubernetes.client.dsl.ExecWatch
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.utils.Serialization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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

    /**
     * Connection-gated trigger for the reactive flows below. The underlying
     * version StateFlow starts at 0 and increments on each successful connect,
     * so filtering for `>0` keeps every informer / polling loop dormant until
     * a cluster has actually been wired up. Without this gate, SessionViewModel
     * subscribing in its `init` block immediately starts informers / polling
     * against a null client, producing repeated "Not connected to a cluster"
     * errors and a noisy log every ~10 seconds.
     */
    private val connectedTrigger: Flow<Long> = _connectionVersion.filter { it > 0L }

    fun connect(context: String? = null): Result<String> = connectionManager.connect(context)

    /**
     * Connect to a mock cluster instance. `label = null` mints a fresh instance
     * (the picker path — every "Demo Cluster" pick creates an independent mock).
     * A non-null label reattaches to an existing instance, or recreates one with
     * that label if the previous server has already been torn down.
     */
    fun connectMock(label: String? = null): Result<String> = try {
        val handle = if (label == null) MockClusterProvider.acquireNewInstance() else MockClusterProvider.acquire(label)
        connectionManager.connectWithMockHandle(handle)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun getContexts(): List<String> = connectionManager.getContexts()
    fun getContextBindings(): List<ContextBinding> = connectionManager.getContextBindings()
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
    ): StateFlow<ResourceState<List<T>>> = connectedTrigger
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
                        // Debounce, not periodic emit. fabric8 fires onAdd for
                        // every item during initial list-and-watch — without
                        // debounce a CONFLATED channel + delay(100) becomes a
                        // fixed 10 Hz cadence of full-store re-emits. debounce
                        // collapses the burst into one emission once events
                        // settle.
                        emitSignal.consumeAsFlow()
                            .debounce(100)
                            .collect {
                                // Pre-sync emissions are dropped — the post-sync
                                // send below covers the first paint.
                                if (!informer.hasSynced()) return@collect
                                try {
                                    val items = informer.store.list()
                                    log.trace("Informer emitting {} items from store", items.size)
                                    send(ResourceState.Success(items.map(mapper)))
                                    reportSuccess()
                                } catch (e: CancellationException) {
                                    throw e
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
                } catch (e: CancellationException) {
                    // flatMapLatest cancels the previous inner flow on every
                    // namespace / connection-version change. This is normal
                    // lifecycle, NOT a connection failure — never count it as
                    // such or the shared failure counter trips and the UI
                    // bounces to "Unable to connect" → retry → reconnect →
                    // more cancellations → infinite loop.
                    throw e
                } catch (e: Exception) {
                    log.error("Cluster-scoped informer failed: {}", e.message)
                    reportError(e.message ?: "Unknown error")
                    send(ResourceState.Error(e.message ?: "Unknown error"))
                }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), ResourceState.Loading)

    private fun <R : HasMetadata, T> namespacedInformerFlow(
        inform: (KubernetesClient, String?, ResourceEventHandler<R>) -> SharedIndexInformer<R>,
        mapper: (R) -> T?,
    ): StateFlow<ResourceState<List<T>>> = combine(_selectedNamespace, connectedTrigger) { ns, _ -> ns }
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
                        emitSignal.consumeAsFlow()
                            .debounce(100)
                            .collect {
                                if (!informer.hasSynced()) return@collect
                                try {
                                    val items = informer.store.list()
                                    log.trace("Namespaced informer emitting {} items for namespace={}", items.size, nsLabel)
                                    send(ResourceState.Success(items.mapNotNull(mapper)))
                                    reportSuccess()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    log.warn("Namespaced informer failed to map store contents for namespace={}: {}", nsLabel, e.message)
                                    reportError(e.message ?: "Unknown error")
                                }
                            }
                    }
                    while (!informer.hasSynced()) delay(50)
                    val items = informer.store.list()
                    log.info("Namespaced informer synced with {} items for namespace={}", items.size, nsLabel)
                    send(ResourceState.Success(items.mapNotNull(mapper)))
                    reportSuccess()
                    try {
                        awaitCancellation()
                    } finally {
                        log.debug("Closing namespaced informer for namespace={}", nsLabel)
                        informer.close()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Namespaced informer failed for namespace={}: {}", ns ?: "<all>", e.message)
                    reportError(e.message ?: "Unknown error")
                    send(ResourceState.Error(e.message ?: "Unknown error"))
                }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), ResourceState.Loading)

    // ── Polling helpers (for non-watchable resources) ────────────────────────────

    private fun <T> namespacedPollingStateFlow(
        intervalMs: Long = 5_000,
        fetch: (namespace: String?) -> T,
    ): StateFlow<ResourceState<T>> = combine(_selectedNamespace, connectedTrigger) { ns, _ -> ns }
        .flatMapLatest { ns ->
            flow {
                emit(ResourceState.Loading)
                var loaded = false
                while (true) {
                    try {
                        // runInterruptible: fetch is a blocking fabric8 call with no
                        // suspension point, so flatMapLatest cancellation (namespace /
                        // connection change) can't preempt it otherwise — it would pin
                        // an IO thread until fabric8's own retry budget exhausts.
                        val data = runInterruptible(Dispatchers.IO) { fetch(ns) }
                        reportSuccess()
                        emit(ResourceState.Success(data))
                        loaded = true
                        log.trace("Polling fetch succeeded for namespace={}", ns ?: "<all>")
                    } catch (e: CancellationException) {
                        throw e
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
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), ResourceState.Loading)

    private fun <T> pollingStateFlow(
        intervalMs: Long = 5_000,
        fetch: () -> T,
    ): StateFlow<ResourceState<T>> = connectedTrigger
        .flatMapLatest {
            flow {
                emit(ResourceState.Loading)
                var loaded = false
                while (true) {
                    try {
                        val data = runInterruptible(Dispatchers.IO) { fetch() }
                        reportSuccess()
                        emit(ResourceState.Success(data))
                        loaded = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportError(e.message ?: "Unknown error")
                        if (!loaded) emit(ResourceState.Error(e.message ?: "Unknown error"))
                    }
                    delay(intervalMs)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), ResourceState.Loading)

    private fun <T> directPollingStateFlow(
        intervalMs: Long = 5_000,
        initial: T,
        fetch: () -> T,
    ): StateFlow<T> = connectedTrigger
        .flatMapLatest {
            flow {
                emit(initial)
                while (true) {
                    try {
                        emit(runInterruptible(Dispatchers.IO) { fetch() })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // keep previous value on error
                    }
                    delay(intervalMs)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), initial)

    // ── Mapping: Pods ───────────────────────────────────────────────────────────

    // Pure mapping logic lives in `ResourceMappers` (audit A1 extraction).
    // These remain thin private delegates so every existing call site
    // (`::mapPod`, `effectivePodStatus(pod)` in the graph builders, …)
    // keeps working unchanged with identical behaviour.
    private fun mapPod(pod: Pod): PodInfo = ResourceMappers.mapPod(pod)

    private fun effectivePodStatus(pod: Pod): String = ResourceMappers.effectivePodStatus(pod)

    // ── Mapping: Events ─────────────────────────────────────────────────────────

    private fun mapEvent(ev: io.fabric8.kubernetes.api.model.Event): EventInfo? = ResourceMappers.mapEvent(ev)

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
                annotations = ns.metadata.annotations ?: emptyMap(),
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
                annotations = dep.metadata.annotations ?: emptyMap(),
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
            val externalIPs = if (svc.spec?.type == "LoadBalancer") {
                svc.status?.loadBalancer?.ingress
                    ?.mapNotNull { it.ip?.ifBlank { null } ?: it.hostname }
                    ?.joinToString(", ").orEmpty()
            } else {
                svc.spec?.externalIPs?.joinToString(", ").orEmpty()
            }
            ServiceInfo(
                uid = svc.metadata.uid ?: "", name = svc.metadata.name,
                namespace = svc.metadata.namespace ?: "",
                type = svc.spec?.type ?: "", clusterIP = svc.spec?.clusterIP ?: "",
                externalIPs = externalIPs,
                ports = ports, age = formatAge(svc.metadata.creationTimestamp),
                selector = svc.spec?.selector ?: emptyMap(),
                labels = svc.metadata.labels ?: emptyMap(),
                annotations = svc.metadata.annotations ?: emptyMap(),
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
                creationTimestamp = node.metadata.creationTimestamp ?: "",
                labels = node.metadata.labels ?: emptyMap(),
                annotations = node.metadata.annotations ?: emptyMap(),
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
                annotations = cm.metadata.annotations ?: emptyMap(),
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
                annotations = s.metadata.annotations ?: emptyMap(),
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
                annotations = ss.metadata.annotations ?: emptyMap(),
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
                annotations = ds.metadata.annotations ?: emptyMap(),
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
                annotations = rs.metadata.annotations ?: emptyMap(),
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
                annotations = job.metadata.annotations ?: emptyMap(),
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
                annotations = cj.metadata.annotations ?: emptyMap(),
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
                annotations = ing.metadata.annotations ?: emptyMap(),
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
                annotations = ep.metadata.annotations ?: emptyMap(),
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
                annotations = np.metadata.annotations ?: emptyMap(),
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
                annotations = pv.metadata.annotations ?: emptyMap(),
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
                annotations = pvc.metadata.annotations ?: emptyMap(),
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
                annotations = sc.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Provisioner" to (sc.provisioner ?: ""),
                    "Reclaim Policy" to (sc.reclaimPolicy ?: ""),
                    "Binding Mode" to (sc.volumeBindingMode ?: ""),
                ),
            )
        },
    )

    // ── RBAC / Access Control ───────────────────────────────────────────────────

    val serviceAccounts: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.serviceAccounts().inNamespace(ns).inform(h)
            } else {
                k.serviceAccounts().inAnyNamespace().inform(h)
            }
        },
        mapper = { sa ->
            GenericResourceInfo(
                uid = sa.metadata.uid ?: "",
                name = sa.metadata.name,
                namespace = sa.metadata.namespace,
                status = null,
                age = formatAge(sa.metadata.creationTimestamp),
                labels = sa.metadata.labels ?: emptyMap(),
                annotations = sa.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Secrets" to "${sa.secrets?.size ?: 0}",
                    "Automount" to (sa.automountServiceAccountToken?.toString() ?: "—"),
                ),
            )
        },
    )

    val roles: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.rbac().roles().inNamespace(ns).inform(h)
            } else {
                k.rbac().roles().inAnyNamespace().inform(h)
            }
        },
        mapper = { role ->
            GenericResourceInfo(
                uid = role.metadata.uid ?: "",
                name = role.metadata.name,
                namespace = role.metadata.namespace,
                status = null,
                age = formatAge(role.metadata.creationTimestamp),
                labels = role.metadata.labels ?: emptyMap(),
                annotations = role.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf("Rules" to "${role.rules?.size ?: 0}"),
            )
        },
    )

    val clusterRoles: StateFlow<ResourceState<List<GenericResourceInfo>>> = informerFlow(
        inform = { k, h -> k.rbac().clusterRoles().inform(h) },
        mapper = { cr ->
            GenericResourceInfo(
                uid = cr.metadata.uid ?: "",
                name = cr.metadata.name,
                namespace = null,
                status = null,
                age = formatAge(cr.metadata.creationTimestamp),
                labels = cr.metadata.labels ?: emptyMap(),
                annotations = cr.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Rules" to "${cr.rules?.size ?: 0}",
                    "Aggregated" to if (cr.aggregationRule != null) "yes" else "—",
                ),
            )
        },
    )

    val roleBindings: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.rbac().roleBindings().inNamespace(ns).inform(h)
            } else {
                k.rbac().roleBindings().inAnyNamespace().inform(h)
            }
        },
        mapper = { rb ->
            GenericResourceInfo(
                uid = rb.metadata.uid ?: "",
                name = rb.metadata.name,
                namespace = rb.metadata.namespace,
                status = null,
                age = formatAge(rb.metadata.creationTimestamp),
                labels = rb.metadata.labels ?: emptyMap(),
                annotations = rb.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Role" to "${rb.roleRef?.kind ?: ""}/${rb.roleRef?.name ?: ""}",
                    "Subjects" to "${rb.subjects?.size ?: 0}",
                ),
            )
        },
    )

    val clusterRoleBindings: StateFlow<ResourceState<List<GenericResourceInfo>>> = informerFlow(
        inform = { k, h -> k.rbac().clusterRoleBindings().inform(h) },
        mapper = { crb ->
            GenericResourceInfo(
                uid = crb.metadata.uid ?: "",
                name = crb.metadata.name,
                namespace = null,
                status = null,
                age = formatAge(crb.metadata.creationTimestamp),
                labels = crb.metadata.labels ?: emptyMap(),
                annotations = crb.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Role" to "${crb.roleRef?.kind ?: ""}/${crb.roleRef?.name ?: ""}",
                    "Subjects" to "${crb.subjects?.size ?: 0}",
                ),
            )
        },
    )

    // ── Autoscaling & Disruption ────────────────────────────────────────────────

    val horizontalPodAutoscalers: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).inform(h)
            } else {
                k.autoscaling().v2().horizontalPodAutoscalers().inAnyNamespace().inform(h)
            }
        },
        mapper = { hpa ->
            GenericResourceInfo(
                uid = hpa.metadata.uid ?: "",
                name = hpa.metadata.name,
                namespace = hpa.metadata.namespace,
                status = null,
                age = formatAge(hpa.metadata.creationTimestamp),
                labels = hpa.metadata.labels ?: emptyMap(),
                annotations = hpa.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Reference" to "${hpa.spec?.scaleTargetRef?.kind ?: ""}/${hpa.spec?.scaleTargetRef?.name ?: ""}",
                    "Min" to "${hpa.spec?.minReplicas ?: "—"}",
                    "Max" to "${hpa.spec?.maxReplicas ?: "—"}",
                    "Replicas" to "${hpa.status?.currentReplicas ?: 0}",
                ),
            )
        },
    )

    val podDisruptionBudgets: StateFlow<ResourceState<List<GenericResourceInfo>>> = namespacedInformerFlow(
        inform = { k, ns, h ->
            if (ns != null) {
                k.policy().v1().podDisruptionBudget().inNamespace(ns).inform(h)
            } else {
                k.policy().v1().podDisruptionBudget().inAnyNamespace().inform(h)
            }
        },
        mapper = { pdb ->
            GenericResourceInfo(
                uid = pdb.metadata.uid ?: "",
                name = pdb.metadata.name,
                namespace = pdb.metadata.namespace,
                status = null,
                age = formatAge(pdb.metadata.creationTimestamp),
                labels = pdb.metadata.labels ?: emptyMap(),
                annotations = pdb.metadata.annotations ?: emptyMap(),
                extraColumns = mapOf(
                    "Min Available" to (pdb.spec?.minAvailable?.let { it.strVal ?: it.intVal?.toString() } ?: "—"),
                    "Max Unavailable" to (pdb.spec?.maxUnavailable?.let { it.strVal ?: it.intVal?.toString() } ?: "—"),
                    "Healthy" to "${pdb.status?.currentHealthy ?: 0}/${pdb.status?.desiredHealthy ?: 0}",
                    "Allowed" to "${pdb.status?.disruptionsAllowed ?: 0}",
                ),
            )
        },
    )

    // ── Custom Resource Definitions ─────────────────────────────────────────────

    /**
     * Discovered CRDs on the cluster. Emits an empty list (not an error) when
     * the caller's RBAC denies `customresourcedefinitions` — many service
     * accounts can't list CRDs, and the dashboard still works for built-in
     * resources, so failing this informer should silently hide the "Custom
     * Resources" section rather than tripping a connection error.
     */
    val crds: StateFlow<ResourceState<List<CrdInfo>>> = connectedTrigger
        .flatMapLatest {
            channelFlow {
                send(ResourceState.Loading)
                val informer = try {
                    val emitSignal = Channel<Unit>(Channel.CONFLATED)
                    val inf = k8s.apiextensions().v1().customResourceDefinitions().inform(
                        object : ResourceEventHandler<CustomResourceDefinition> {
                            override fun onAdd(obj: CustomResourceDefinition) {
                                emitSignal.trySend(Unit)
                            }
                            override fun onUpdate(o: CustomResourceDefinition, n: CustomResourceDefinition) {
                                emitSignal.trySend(Unit)
                            }
                            override fun onDelete(obj: CustomResourceDefinition, deletedFinalStateUnknown: Boolean) {
                                emitSignal.trySend(Unit)
                            }
                        },
                    )
                    launch {
                        for (signal in emitSignal) {
                            delay(100)
                            try {
                                send(ResourceState.Success(inf.store.list().mapNotNull(::mapCrd)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.debug("CRD informer mapping failed: {}", e.message)
                            }
                        }
                    }
                    inf
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.info("CRD discovery unavailable (likely RBAC): {}", e.message)
                    send(ResourceState.Success(emptyList()))
                    null
                }
                if (informer == null) {
                    awaitCancellation()
                } else {
                    while (!informer.hasSynced()) delay(50)
                    val items = informer.store.list().mapNotNull(::mapCrd)
                    log.info("CRD informer synced with {} CRDs", items.size)
                    send(ResourceState.Success(items))
                    try {
                        awaitCancellation()
                    } finally {
                        informer.close()
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), ResourceState.Loading)

    private fun mapCrd(crd: CustomResourceDefinition): CrdInfo? = ResourceMappers.mapCrd(crd)

    // ── Custom resource instances (lazy, cached per GVK) ────────────────────────

    private val crdInstanceFlows = ConcurrentHashMap<String, StateFlow<ResourceState<List<GenericResourceInfo>>>>()

    /**
     * Reactive list of instances for the given CRD. Created lazily on first
     * subscribe; the underlying informer stops 60s after the last subscriber
     * goes away (`WhileSubscribed`), and is recreated on resubscribe. The
     * StateFlow itself is cached for the lifetime of this client so multiple
     * UI surfaces (sidebar count, list screen, palette) share one informer.
     */
    fun customResourceInstances(crd: CrdInfo): StateFlow<ResourceState<List<GenericResourceInfo>>> = crdInstanceFlows.computeIfAbsent(crd.groupVersionKind) { buildCrInstanceFlow(crd) }

    private fun buildCrInstanceFlow(crd: CrdInfo): StateFlow<ResourceState<List<GenericResourceInfo>>> {
        val rdc = ResourceDefinitionContext.Builder()
            .withGroup(crd.group)
            .withVersion(crd.version)
            .withKind(crd.kind)
            .withPlural(crd.plural)
            .withNamespaced(crd.namespaced)
            .build()
        val mapper: (GenericKubernetesResource) -> GenericResourceInfo = { gkr ->
            mapCrInstance(gkr, crd)
        }
        return if (crd.namespaced) {
            namespacedInformerFlow(
                inform = { k, ns, h ->
                    if (ns != null) {
                        k.genericKubernetesResources(rdc).inNamespace(ns).inform(h)
                    } else {
                        k.genericKubernetesResources(rdc).inAnyNamespace().inform(h)
                    }
                },
                mapper = mapper,
            )
        } else {
            informerFlow(
                inform = { k, h -> k.genericKubernetesResources(rdc).inform(h) },
                mapper = mapper,
            )
        }
    }

    private fun mapCrInstance(gkr: GenericKubernetesResource, crd: CrdInfo): GenericResourceInfo = ResourceMappers.mapCrInstance(gkr, crd)

    // ── Cluster Info (derived from informers + version polling) ─────────────────

    /**
     * K8s server version as `"major.minor"` (e.g. `"1.34"`). Polled on a
     * slow cadence — it's the only piece of clusterInfo that has no watch
     * API. Empty string while the first poll is in flight or after an error.
     */
    private val versionFlow: StateFlow<String> = directPollingStateFlow(
        intervalMs = 60_000,
        initial = "",
        fetch = {
            val v = k8s.kubernetesVersion
            val major = v?.major.orEmpty()
            val minor = v?.minor.orEmpty()
            if (major.isNotBlank() && minor.isNotBlank()) "$major.$minor" else ""
        },
    )

    /**
     * Liveness probe — fixes the A6 silent-disconnect bug
     * (.docs/a6-connection-state-finding-2026-05-16.md). After their initial
     * sync, informers park in `awaitCancellation()` and do NOT surface a
     * watch-time connection loss, so once a session was connected
     * `connectionError` could stay stale-OK forever on a silent disconnect
     * (network drop, API-server restart, credential expiry) — the ring never
     * went red and no retry fired until the user forced a reconnect.
     *
     * This low-frequency `/version` GET keeps `reportSuccess`/`reportError`
     * honest while connected, so the existing
     * `SessionViewModel.observeConnectionHealth` path (→ ConnectionError
     * screen + `scheduleRetry`) now detects a dead cluster exactly like it
     * already does for an explicit connect failure — NO new connection-state
     * writer is introduced; the fix feeds the existing pipeline.
     * `reportError`'s ≥3-consecutive threshold debounces transient blips, so
     * worst-case detection is ~3× the interval.
     *
     * `Eagerly` (not `WhileSubscribed`): liveness must run whenever the
     * session is connected, independent of which screen is visible.
     * `connectedTrigger` gates it (idle until the first successful connect;
     * `flatMapLatest` restarts it on cluster switch / reconnect — the same
     * lifecycle every other flow here uses, and the namespace selector does
     * NOT feed it, so a namespace switch can't trip a spurious error).
     */
    val isReachable: StateFlow<Boolean> = connectedTrigger
        .flatMapLatest {
            flow {
                while (true) {
                    val ok = try {
                        // A real round-trip every tick: kubernetesVersion is
                        // cached by fabric8 after the first call (useless as a
                        // liveness signal); namespaces are few and always
                        // re-listed. MUST be time-bounded: fabric8 retries a
                        // failed request with its own backoff, so a call to a
                        // dead cluster can block far longer than the probe
                        // interval — without this timeout the probe never
                        // even reports the first failure.
                        val reached = withTimeoutOrNull(4_000) {
                            // runInterruptible: list() is a blocking JVM call
                            // with no suspension point, so withTimeoutOrNull
                            // alone can't preempt it (it would wait for
                            // fabric8's retry budget to exhaust). This makes
                            // the timeout interrupt the worker thread, which
                            // OkHttp honors by throwing.
                            runInterruptible(Dispatchers.IO) { k8s.namespaces().list() }
                            true
                        } ?: false
                        if (reached) reportSuccess() else reportError("Cluster liveness probe timed out")
                        reached
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportError(e.message ?: "Cluster unreachable")
                        false
                    }
                    emit(ok)
                    delay(4_000)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * Derived from the informer-backed `nodes`, `namespaceNames`, `pods`,
     * `deployments`, `services` flows plus `versionFlow`. No separate REST
     * polling — the informers already hold this data, and counts update on
     * every real cluster change instead of every 10 s.
     *
     * Error semantics (see [combineClusterInfo]): a single list the caller's
     * RBAC forbids — commonly `nodes` / `namespaces` for a namespaced
     * ServiceAccount — must not flip the whole connection to "Unable to
     * connect", so `SessionViewModel.observeClusterInfoHealth` only sees Error
     * when *every* source failed. Genuine silent disconnects are detected by
     * the liveness probe (isReachable), not here — informers park on a
     * watch-time loss rather than erroring.
     */
    val clusterInfo: StateFlow<ResourceState<ClusterInfo>> = combine(
        nodes,
        namespaceNames,
        pods,
        combine(deployments, services, versionFlow) { d, s, v -> Triple(d, s, v) },
    ) { nodesS, nsS, podsS, dsv ->
        val (depsS, svcsS, version) = dsv
        combineClusterInfo(getCurrentContext(), getClusterServer(), version, nodesS, nsS, podsS, depsS, svcsS)
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.WhileSubscribed(60_000), ResourceState.Loading)

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

    // ── Per-node resource usage (polling) ────────────────────────────────────────

    val nodeUsages: StateFlow<Map<String, NodeResourceUsage>> = directPollingStateFlow(
        intervalMs = 10_000,
        initial = emptyMap(),
        fetch = {
            val nodeMetrics = try {
                k8s.top().nodes().metrics().items ?: emptyList()
            } catch (_: Exception) {
                return@directPollingStateFlow emptyMap()
            }
            val nodeItems = try {
                k8s.nodes().list().items ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val capacityByName: Map<String, Pair<Long, Long>> = nodeItems
                .associate { node ->
                    val alloc = node.status?.allocatable
                    val cpuCap = parseCpuToMillis(alloc?.get("cpu")?.toString() ?: "0")
                    val memCap = parseMemoryToBytes(alloc?.get("memory")?.toString() ?: "0")
                    node.metadata.name to (cpuCap to memCap)
                }
            nodeMetrics.associate { nm ->
                val name = nm.metadata.name
                val cpu = parseCpuToMillis(nm.usage?.get("cpu")?.toString() ?: "0")
                val mem = parseMemoryToBytes(nm.usage?.get("memory")?.toString() ?: "0")
                val (cpuCap, memCap) = capacityByName[name] ?: (0L to 0L)
                name to NodeResourceUsage(name, cpu, cpuCap, mem, memCap)
            }
        },
    )

    // ── On-demand: YAML ─────────────────────────────────────────────────────────

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

            "serviceaccount" -> namespace?.let { k8s.serviceAccounts().inNamespace(it).withName(name).get() }

            "role" -> namespace?.let { k8s.rbac().roles().inNamespace(it).withName(name).get() }

            "clusterrole" -> k8s.rbac().clusterRoles().withName(name).get()

            "rolebinding" -> namespace?.let { k8s.rbac().roleBindings().inNamespace(it).withName(name).get() }

            "clusterrolebinding" -> k8s.rbac().clusterRoleBindings().withName(name).get()

            "horizontalpodautoscaler" -> namespace?.let { k8s.autoscaling().v2().horizontalPodAutoscalers().inNamespace(it).withName(name).get() }

            "poddisruptionbudget" -> namespace?.let { k8s.policy().v1().podDisruptionBudget().inNamespace(it).withName(name).get() }

            else -> if (!group.isNullOrBlank() && !version.isNullOrBlank()) {
                val effectivePlural = plural?.takeIf { it.isNotBlank() } ?: defaultPluralForKind(kind)
                val rdc = ResourceDefinitionContext.Builder()
                    .withGroup(group)
                    .withVersion(version)
                    .withKind(kind)
                    .withPlural(effectivePlural)
                    .withNamespaced(namespace != null)
                    .build()
                val op = k8s.genericKubernetesResources(rdc)
                if (namespace != null) op.inNamespace(namespace).withName(name).get() else op.withName(name).get()
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

    private fun defaultPluralForKind(kind: String): String {
        val lower = kind.lowercase()
        return when {
            lower.endsWith("y") -> lower.dropLast(1) + "ies"
            lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh") -> lower + "es"
            else -> lower + "s"
        }
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

    fun streamPodLogs(name: String, namespace: String, container: String?): Flow<String> = flow {
        // Probe phase once so terminal pods (Succeeded/Failed) get a one-shot
        // historical read instead of watchLog() with follow=true, which on a
        // terminated container can return immediately with no data.
        val phase = try {
            k8s.pods().inNamespace(namespace).withName(name).get()?.status?.phase
        } catch (e: Exception) {
            log.debug(
                "Pod phase probe failed pod={} namespace={}: {} — falling back to live stream",
                name,
                namespace,
                e.message,
            )
            null
        }
        if (phase == "Succeeded" || phase == "Failed") {
            log.debug("Reading historical logs pod={} namespace={} phase={}", name, namespace, phase)
            try {
                val op = k8s.pods().inNamespace(namespace).withName(name)
                val withC = if (container != null) op.inContainer(container) else op
                val text = withC.tailingLines(5_000).log
                if (!text.isNullOrEmpty()) {
                    text.lineSequence().forEach { emit(it) }
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch historical logs pod={} namespace={}: {}", name, namespace, e.message)
                emit("[fetch error: ${e.message}]")
            }
        } else {
            emitAll(streamLivePodLogs(name, namespace, container))
        }
    }.flowOn(Dispatchers.IO)

    private fun streamLivePodLogs(name: String, namespace: String, container: String?): Flow<String> = callbackFlow {
        log.debug("Starting log stream pod={} namespace={} container={}", name, namespace, container)
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
                    for (line in reader.lineSequence()) send(line)
                }
            } catch (e: CancellationException) {
                // The log pane was closed (LogStreamRegistry.close → job.cancel).
                // This is normal teardown — don't append a fake "[stream error]"
                // line to the buffer the user is about to throw away.
                throw e
            } catch (e: Exception) {
                log.warn("Log stream ended pod={} namespace={}: {}", name, namespace, e.message)
                trySend("[stream error: ${e.message}]")
                close(e)
            }
        }
        awaitClose {
            log.debug("Closing log stream pod={} namespace={}", name, namespace)
            watch.close()
        }
    }

    // ── On-demand: Exec (interactive shell) ─────────────────────────────────────

    /**
     * Open an interactive `kubectl exec`-equivalent against [container] in pod
     * [name]/[namespace]. Returns a live [ExecWatch] whose `input` (OutputStream)
     * carries keystrokes and whose `output` (InputStream) carries shell output.
     * Caller owns the watch and must call `close()` when done.
     *
     * [shellCmd] defaults to a sh/bash detection sequence so the call works on
     * minimal images (alpine, distroless-with-shell, ubuntu).
     *
     * stderr is intentionally NOT redirected: with `withTTY()` the kernel PTY
     * merges stderr into the single output stream, so a separate redirected
     * error channel would sit unread and risk blocking the WebSocket reader if
     * any frame did arrive. kubectl itself only redirects stdin/stdout in TTY
     * mode for the same reason.
     */
    fun openExec(
        name: String,
        namespace: String,
        container: String,
        shellCmd: List<String> = DEFAULT_SHELL_CMD,
        onClose: (Int, String?) -> Unit = { _, _ -> },
    ): ExecWatch {
        log.debug("Opening exec session pod={} namespace={} container={}", name, namespace, container)
        return k8s.pods()
            .inNamespace(namespace)
            .withName(name)
            .inContainer(container)
            .redirectingInput()
            .redirectingOutput()
            .withTTY()
            .usingListener(object : ExecListener {
                override fun onClose(code: Int, reason: String?) {
                    log.debug("Exec closed pod={} code={} reason={}", name, code, reason)
                    onClose(code, reason)
                }

                override fun onFailure(t: Throwable, failureResponse: ExecListener.Response?) {
                    log.warn("Exec failed pod={}: {}", name, t.message)
                    onClose(-1, t.message)
                }
            })
            .exec(*shellCmd.toTypedArray())
    }

    // ── On-demand: Delete ───────────────────────────────────────────────────────

    fun deleteResource(
        kind: String,
        name: String,
        namespace: String?,
        group: String? = null,
        version: String? = null,
        plural: String? = null,
        propagationPolicy: DeletionPropagation? = null,
    ): Result<Unit> = try {
        log.info("Deleting resource kind={} name={} namespace={}", kind, name, namespace)
        when (kind.lowercase()) {
            "pod" -> {
                val ns = requireNamespace("Pod", namespace)
                k8s.pods().inNamespace(ns).withName(name).delete()
            }

            "deployment" -> {
                val ns = requireNamespace("Deployment", namespace)
                // Foreground so dependent ReplicaSets/Pods are deleted before the
                // call returns, matching the "click Delete → it's gone" expectation.
                val prop = propagationPolicy ?: DeletionPropagation.FOREGROUND
                k8s.apps().deployments().inNamespace(ns).withName(name).withPropagationPolicy(prop).delete()
            }

            "service" -> {
                val ns = requireNamespace("Service", namespace)
                k8s.services().inNamespace(ns).withName(name).delete()
            }

            "configmap" -> {
                val ns = requireNamespace("ConfigMap", namespace)
                k8s.configMaps().inNamespace(ns).withName(name).delete()
            }

            "secret" -> {
                val ns = requireNamespace("Secret", namespace)
                k8s.secrets().inNamespace(ns).withName(name).delete()
            }

            "job" -> {
                val ns = requireNamespace("Job", namespace)
                // kubectl's default orphans Pods; users hitting "Delete" in a
                // dashboard expect the Pods to go too.
                val prop = propagationPolicy ?: DeletionPropagation.FOREGROUND
                k8s.batch().v1().jobs().inNamespace(ns).withName(name).withPropagationPolicy(prop).delete()
            }

            "cronjob" -> {
                val ns = requireNamespace("CronJob", namespace)
                val prop = propagationPolicy ?: DeletionPropagation.FOREGROUND
                k8s.batch().v1().cronjobs().inNamespace(ns).withName(name).withPropagationPolicy(prop).delete()
            }

            "namespace" -> k8s.namespaces().withName(name).delete()

            "serviceaccount" -> {
                val ns = requireNamespace("ServiceAccount", namespace)
                k8s.serviceAccounts().inNamespace(ns).withName(name).delete()
            }

            "role" -> {
                val ns = requireNamespace("Role", namespace)
                k8s.rbac().roles().inNamespace(ns).withName(name).delete()
            }

            "clusterrole" -> k8s.rbac().clusterRoles().withName(name).delete()

            "rolebinding" -> {
                val ns = requireNamespace("RoleBinding", namespace)
                k8s.rbac().roleBindings().inNamespace(ns).withName(name).delete()
            }

            "clusterrolebinding" -> k8s.rbac().clusterRoleBindings().withName(name).delete()

            "horizontalpodautoscaler" -> {
                val ns = requireNamespace("HorizontalPodAutoscaler", namespace)
                k8s.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name).delete()
            }

            "poddisruptionbudget" -> {
                val ns = requireNamespace("PodDisruptionBudget", namespace)
                k8s.policy().v1().podDisruptionBudget().inNamespace(ns).withName(name).delete()
            }

            else -> if (!group.isNullOrBlank() && !version.isNullOrBlank()) {
                val effectivePlural = plural?.takeIf { it.isNotBlank() } ?: defaultPluralForKind(kind)
                val rdc = ResourceDefinitionContext.Builder()
                    .withGroup(group)
                    .withVersion(version)
                    .withKind(kind)
                    .withPlural(effectivePlural)
                    .withNamespaced(namespace != null)
                    .build()
                val op = k8s.genericKubernetesResources(rdc)
                if (namespace != null) op.inNamespace(namespace).withName(name).delete() else op.withName(name).delete()
            } else {
                throw IllegalArgumentException("Delete not supported for $kind")
            }
        }
        log.info("Deleted resource kind={} name={} namespace={}", kind, name, namespace)
        Result.success(Unit)
    } catch (e: Exception) {
        log.error("Failed to delete resource kind={} name={} namespace={}: {}", kind, name, namespace, e.message)
        Result.failure(e)
    }

    private fun requireNamespace(kind: String, namespace: String?): String = namespace ?: throw IllegalArgumentException("$kind requires a namespace")

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
        log.trace("Fetching pods for node={}", nodeName)
        return k8s.pods().inAnyNamespace().list().items
            .filter { it.spec?.nodeName == nodeName || it.status?.nominatedNodeName == nodeName }
            .map(::mapPod)
            .also { log.trace("Found {} pods on node={}", it.size, nodeName) }
    }

    // Pod-per-node tallies for the Nodes usage panel: one cluster-wide list
    // instead of getPodsByNode() per node. A pod counts toward both its
    // assigned and nominated node (when they differ), so summing this map
    // over the node set matches looping getPodsByNode().size per node.
    fun getPodCountsByNode(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        k8s.pods().inAnyNamespace().list().items.forEach { pod ->
            val assigned = pod.spec?.nodeName
            val nominated = pod.status?.nominatedNodeName
            if (assigned != null) counts[assigned] = (counts[assigned] ?: 0) + 1
            if (nominated != null && nominated != assigned) {
                counts[nominated] = (counts[nominated] ?: 0) + 1
            }
        }
        log.trace("Pod counts computed across {} node buckets", counts.size)
        return counts
    }

    fun getEventsForNode(nodeName: String): List<EventInfo> {
        log.trace("Fetching events for node={}", nodeName)
        return k8s.v1().events().inAnyNamespace().list().items
            .filter { it.involvedObject?.kind == "Node" && it.involvedObject?.name == nodeName }
            .sortedByDescending { it.metadata?.creationTimestamp }
            .mapNotNull(::mapEvent)
            .also { log.trace("Found {} events for node={}", it.size, nodeName) }
    }

    fun getEventsOnNode(nodeName: String): List<EventInfo> {
        log.trace("Fetching events on node={}", nodeName)
        return k8s.v1().events().inAnyNamespace().list().items
            .filter { it.source?.host == nodeName }
            .sortedByDescending { it.metadata?.creationTimestamp }
            .mapNotNull(::mapEvent)
            .also { log.trace("Found {} events on node={}", it.size, nodeName) }
    }

    fun getEventsForObject(kind: String, name: String, namespace: String?): List<EventInfo> {
        log.trace("Fetching events for object kind={} name={} namespace={}", kind, name, namespace)
        // Honor the namespace parameter when given — Events are namespaced
        // (an Event lives in the same namespace as its involvedObject for
        // namespaced kinds), so .inNamespace(ns) scopes the LIST server-side
        // instead of dragging every cluster event over the wire to be
        // post-filtered. namespace == null is reserved for cluster-scoped
        // involved objects (Node, PV, etc.) where there's no narrower scope
        // to ask for.
        val items = if (namespace != null) {
            k8s.v1().events().inNamespace(namespace).list().items
        } else {
            k8s.v1().events().inAnyNamespace().list().items
        }
        return items
            .filter { it.involvedObject?.kind == kind && it.involvedObject?.name == name }
            .sortedByDescending { it.metadata?.creationTimestamp }
            .mapNotNull(::mapEvent)
            .also { log.trace("Found {} events for {}/{}", it.size, kind, name) }
    }

    fun getPodByName(name: String, namespace: String): PodInfo? {
        log.trace("Fetching pod name={} namespace={}", name, namespace)
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

        // Fetch only — pure node/edge assembly lives in ResourceGraphBuilder
        // (audit A1). Each group keeps its previous independent partial-
        // failure semantics: a failed list call yields emptyList, which the
        // builder turns into exactly the graph the old per-section catch
        // produced. Ingresses are now fetched once (namespace-scoped, same
        // query) instead of lazily per matching Service — output-identical;
        // only failure-log cardinality changes.
        var allReplicaSets = emptyList<io.fabric8.kubernetes.api.model.apps.ReplicaSet>()
        var allPods = emptyList<Pod>()
        try {
            allReplicaSets = k8s.apps().replicaSets().inNamespace(namespace).list().items ?: emptyList()
            allPods = k8s.pods().inNamespace(namespace).list().items ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to fetch ReplicaSets/Pods for deployment graph: {}", e.message)
        }

        val allServices = try {
            k8s.services().inNamespace(namespace).list().items ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to fetch Services for deployment graph: {}", e.message)
            emptyList()
        }

        val allIngresses = try {
            k8s.network().v1().ingresses().inNamespace(namespace).list().items ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to fetch Ingresses for service graph: {}", e.message)
            emptyList()
        }

        val allHpas = try {
            k8s.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).list().items ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to fetch HPAs for deployment graph: {}", e.message)
            emptyList()
        }

        val graph = ResourceGraphBuilder.buildDeploymentGraph(
            name = name,
            deployment = deployment,
            allReplicaSets = allReplicaSets,
            allPods = allPods,
            allServices = allServices,
            allIngresses = allIngresses,
            allHpas = allHpas,
        )
        log.debug("Resource graph built: {} nodes, {} edges", graph.nodes.size, graph.edges.size)
        return graph
    }

    // ── On-demand: Cluster Topology Graph ───────────────────────────────────────

    fun getClusterTopologyGraph(namespace: String): ResourceGraph {
        log.debug("Building cluster topology graph for namespace={}", namespace)
        val isAllNamespaces = namespace == "All Namespaces"

        // Fetch only — every list call keeps its previous independent
        // try/catch→emptyList semantics; pure assembly (Ingress→Service→
        // WorkloadGroup, the findRoot owner-ref walk, scale guard) now lives
        // in ResourceGraphBuilder (audit A1).
        val ingresses = try {
            if (isAllNamespaces) {
                k8s.network().v1().ingresses().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.network().v1().ingresses().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch Ingresses for topology graph: {}", e.message)
            emptyList()
        }
        val services = try {
            if (isAllNamespaces) {
                k8s.services().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.services().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch Services for topology graph: {}", e.message)
            emptyList()
        }
        val allPods = try {
            if (isAllNamespaces) {
                k8s.pods().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.pods().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch Pods for topology graph: {}", e.message)
            emptyList()
        }
        val allRS = try {
            if (isAllNamespaces) {
                k8s.apps().replicaSets().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.apps().replicaSets().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch ReplicaSets for topology graph: {}", e.message)
            emptyList()
        }
        val allJobs = try {
            if (isAllNamespaces) {
                k8s.batch().v1().jobs().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.batch().v1().jobs().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch Jobs for topology graph: {}", e.message)
            emptyList()
        }
        val allDeployments = try {
            if (isAllNamespaces) {
                k8s.apps().deployments().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.apps().deployments().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch Deployments for topology graph: {}", e.message)
            emptyList()
        }
        val allStatefulSets = try {
            if (isAllNamespaces) {
                k8s.apps().statefulSets().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.apps().statefulSets().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch StatefulSets for topology graph: {}", e.message)
            emptyList()
        }
        val allDaemonSets = try {
            if (isAllNamespaces) {
                k8s.apps().daemonSets().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.apps().daemonSets().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch DaemonSets for topology graph: {}", e.message)
            emptyList()
        }
        val allCronJobs = try {
            if (isAllNamespaces) {
                k8s.batch().v1().cronjobs().inAnyNamespace().list().items ?: emptyList()
            } else {
                k8s.batch().v1().cronjobs().inNamespace(namespace).list().items ?: emptyList()
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch CronJobs for topology graph: {}", e.message)
            emptyList()
        }

        val crdKinds: Set<String> = (crds.value as? ResourceState.Success)?.data
            ?.map { it.kind }
            ?.toSet()
            .orEmpty()

        val graph = ResourceGraphBuilder.buildClusterTopology(
            ingresses = ingresses,
            services = services,
            allPods = allPods,
            allRS = allRS,
            allJobs = allJobs,
            allDeployments = allDeployments,
            allStatefulSets = allStatefulSets,
            allDaemonSets = allDaemonSets,
            allCronJobs = allCronJobs,
            crdKinds = crdKinds,
        )
        if (graph.nodes.any { it.id == "__truncated__" }) {
            log.warn("Cluster topology graph truncated at 200 visible nodes")
        } else {
            log.debug("Cluster topology graph built: {} nodes, {} edges", graph.nodes.size, graph.edges.size)
        }
        return graph
    }

    // ── Closeable ───────────────────────────────────────────────────────────────

    override fun close() {
        // Connection lifecycle is managed by KubeConnectionManager
    }
}

/**
 * Default shell-detection command for `openExec`. Tries bash first (better
 * line editing, history), falls back to sh on images that don't ship bash
 * (alpine, distroless-with-shell). Wrapped in `/bin/sh -c` so the `command -v`
 * check is portable.
 */
private val DEFAULT_SHELL_CMD = listOf(
    "/bin/sh",
    "-c",
    "if command -v bash >/dev/null 2>&1; then exec bash; else exec sh; fi",
)

/**
 * Assemble [ClusterInfo] for the cluster overview and the connection-health
 * signal from the informer-backed cluster lists (audit C5).
 *
 * RBAC tolerance: a single list the caller can't read — most commonly `nodes`
 * or `namespaces` for a namespaced ServiceAccount — must NOT bounce the whole
 * connection to "Unable to connect". So [ResourceState.Error] is returned only
 * when *every* source failed (a genuine total failure); otherwise counts are
 * built from whatever succeeded, and a forbidden/errored list contributes 0.
 * While at least one list is still doing its first sync the result is
 * [ResourceState.Loading] (a resolved-but-forbidden list does not hold this up).
 * A silent watch-time disconnect is handled separately by the liveness probe —
 * informers park rather than error, so it never reaches this combiner.
 */
internal fun combineClusterInfo(
    name: String,
    server: String,
    version: String,
    nodes: ResourceState<List<NodeInfo>>,
    namespaceNames: ResourceState<List<String>>,
    pods: ResourceState<List<PodInfo>>,
    deployments: ResourceState<List<DeploymentInfo>>,
    services: ResourceState<List<ServiceInfo>>,
): ResourceState<ClusterInfo> {
    val sources = listOf(nodes, namespaceNames, pods, deployments, services)
    if (sources.all { it is ResourceState.Error }) {
        return sources.firstNotNullOfOrNull { it as? ResourceState.Error }
            ?: ResourceState.Error("Cluster unreachable")
    }
    if (sources.any { it is ResourceState.Loading }) return ResourceState.Loading

    val podList = (pods as? ResourceState.Success)?.data.orEmpty()
    return ResourceState.Success(
        ClusterInfo(
            name = name,
            server = server,
            version = version,
            nodesCount = (nodes as? ResourceState.Success)?.data?.size ?: 0,
            namespacesCount = (namespaceNames as? ResourceState.Success)?.data?.size ?: 0,
            podsCount = podList.size,
            deploymentsCount = (deployments as? ResourceState.Success)?.data?.size ?: 0,
            servicesCount = (services as? ResourceState.Success)?.data?.size ?: 0,
            runningPods = podList.count { it.phase == "Running" },
            pendingPods = podList.count { it.phase == "Pending" },
            failedPods = podList.count { it.phase == "Failed" },
            succeededPods = podList.count { it.phase == "Succeeded" },
        ),
    )
}
