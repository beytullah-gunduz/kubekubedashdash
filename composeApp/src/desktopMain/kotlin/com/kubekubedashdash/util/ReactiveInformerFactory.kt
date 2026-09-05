package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class ReactiveInformerFactory(
    private val scope: CoroutineScope,
    private val connectionManager: KubeConnectionManager,
    private val selectedNamespace: StateFlow<String?>,
) {
    private val log = LoggerFactory.getLogger(ReactiveInformerFactory::class.java)
    private val k8s: KubernetesClient get() = connectionManager.client
    private fun reportSuccess() = connectionManager.reportSuccess()
    private fun reportError(message: String) = connectionManager.reportError(message)
    private val connectedTrigger: Flow<Long> =
        connectionManager.connectionVersion.filter { it > 0L }

    /**
     * Parks the calling inner flow for good when the manager has no live
     * client. `connectionVersion` also bumps on a FAILED connect that tore a
     * previous connection down (KubeConnectionManager.tearDownAfterFailure),
     * and flatMapLatest then restarts every gated flow; without this check the
     * restart would hit the `client` getter's "Not connected" exception in
     * ~25 flows at once and trip the shared failure counter. Parking without
     * emitting keeps each StateFlow's last value, which is what the reconnect
     * overlay shows under its scrim. Called as the FIRST statement of every
     * gated builder, before any Loading emission.
     *
     * Residual window (accepted, not closed here): the check samples
     * `isConnected` once and each builder reads `k8s` a few lines later, so a
     * connect failing in that gap can still reach the getter. The failure path
     * nulls `_client` BEFORE the bump, so a flow restarted *by that bump*
     * always parks; only a restart from another source (a namespace change)
     * can race it.
     */
    internal suspend fun parkUnlessConnected() {
        if (!connectionManager.isConnected) awaitCancellation()
    }

    fun <R : HasMetadata, T> informer(
        inform: (KubernetesClient, ResourceEventHandler<R>) -> SharedIndexInformer<R>,
        mapper: (R) -> T,
    ): StateFlow<ResourceState<List<T>>> = connectedTrigger
        .flatMapLatest {
            channelFlow {
                parkUnlessConnected()
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
                    // Every exit from here on — a cancellation while still
                    // waiting for sync, a sync failure, a mapping failure, or
                    // the steady-state awaitCancellation() — must close the
                    // informer, or its watch, store and processor outlive the
                    // flow. Only the steady-state exit used to be covered.
                    try {
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
                        awaitInformerSync(informer, "Cluster-scoped informer")
                        val items = informer.store.list()
                        log.info("Cluster-scoped informer synced with {} items", items.size)
                        send(ResourceState.Success(items.map(mapper)))
                        reportSuccess()
                        awaitCancellation()
                    } finally {
                        log.debug("Closing cluster-scoped informer")
                        // A close() failure must never replace the exception
                        // already propagating: a CancellationException swapped
                        // for a plain one would miss the catch below and be
                        // reported as a connection failure.
                        runCatching { informer.close() }
                            .onFailure { log.warn("Failed to close cluster-scoped informer: {}", it.message) }
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

    fun <R : HasMetadata, T> namespacedInformer(
        inform: (KubernetesClient, String?, ResourceEventHandler<R>) -> SharedIndexInformer<R>,
        mapper: (R) -> T?,
    ): StateFlow<ResourceState<List<T>>> = combine(selectedNamespace, connectedTrigger) { ns, _ -> ns }
        .flatMapLatest { ns ->
            channelFlow {
                parkUnlessConnected()
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
                    // Same contract as the cluster-scoped builder: every exit
                    // after inform() returned closes the informer.
                    try {
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
                        awaitInformerSync(informer, "Namespaced informer for namespace=$nsLabel")
                        val items = informer.store.list()
                        log.info("Namespaced informer synced with {} items for namespace={}", items.size, nsLabel)
                        send(ResourceState.Success(items.mapNotNull(mapper)))
                        reportSuccess()
                        awaitCancellation()
                    } finally {
                        log.debug("Closing namespaced informer for namespace={}", nsLabel)
                        // Same guard as the cluster-scoped builder: never let a
                        // close() failure replace the propagating exception.
                        runCatching { informer.close() }
                            .onFailure { log.warn("Failed to close namespaced informer for namespace={}: {}", nsLabel, it.message) }
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

    fun <T> namespacedPolling(
        intervalMs: Long = 5_000,
        fetch: (namespace: String?) -> T,
    ): StateFlow<ResourceState<T>> = combine(selectedNamespace, connectedTrigger) { ns, _ -> ns }
        .flatMapLatest { ns ->
            flow {
                parkUnlessConnected()
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

    fun <T> directPolling(
        intervalMs: Long = 5_000,
        initial: T,
        fetch: () -> T,
    ): StateFlow<T> = connectedTrigger
        .flatMapLatest {
            flow {
                parkUnlessConnected()
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
}
