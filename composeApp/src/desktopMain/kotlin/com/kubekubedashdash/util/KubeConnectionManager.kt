package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class KubeConnectionManager(
    /** Test seam: how a kube context name becomes a fabric8 [Config]. Production = `Config.autoConfigure`. */
    private val loadConfig: (context: String?) -> Config = { Config.autoConfigure(it) },
    /** Test seam: how a [Config] becomes a client. Production = `KubernetesClientBuilder`. */
    private val buildClient: (Config) -> KubernetesClient = { KubernetesClientBuilder().withConfig(it).build() },
) : Closeable {

    private val log = LoggerFactory.getLogger(KubeConnectionManager::class.java)

    companion object {
        /**
         * Grace period before a connect-time replaced client is closed
         * (audit C1). Must comfortably exceed the time for a
         * `_connectionVersion` bump to propagate through ReactiveKubeClient's
         * `flatMapLatest` and run the old `informer.close()`. ~1s; the cost
         * is one discarded OkHttp client living slightly longer on a manual
         * cluster switch.
         */
        private const val RETIRE_GRACE_MS = 1_000L
    }

    // @Volatile: written under `connectLock` but read unsynchronized from every
    // informer/polling flow on Dispatchers.IO (client getter, isConnected,
    // getClusterServer). Without it a reader can observe a stale/torn client
    // right after a cluster switch.
    @Volatile private var _client: KubernetesClient? = null

    @Volatile private var _mockHandle: MockClusterHandle? = null
    val isConnected: Boolean get() = _client != null

    val client: KubernetesClient
        get() = _client ?: throw IllegalStateException("Not connected to a cluster")

    // Remembers which kube context this manager is connected to. Required for
    // multi-window: each session has its own KubeConnectionManager and must
    // report ITS context, not the kubeconfig file's `current-context` default
    // (which is the same for every session in the process).
    @Volatile private var _connectedContext: String? = null

    // ── Connection version ──────────────────────────────────────────────────────
    // Bumped on every transition that replaces or tears down a live connection:
    // a successful connect (the reactive flows restart against the new client)
    // and a failed connect that had a previous live connection (the flows
    // restart, observe isConnected == false, and park — see
    // ReactiveInformerFactory.parkUnlessConnected). A failed connect with no
    // previous connection bumps nothing: there is nothing to tear down.
    private val _connectionVersion = MutableStateFlow(0L)
    val connectionVersion: StateFlow<Long> = _connectionVersion.asStateFlow()

    // ── Connection error tracking ───────────────────────────────────────────────
    private val _consecutiveFailures = AtomicInteger(0)
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    fun reportSuccess() {
        if (_consecutiveFailures.getAndSet(0) > 0) {
            _connectionError.value = null
        }
    }

    fun reportError(message: String) {
        val count = _consecutiveFailures.incrementAndGet()
        if (count >= 3) {
            _connectionError.value = message
            log.warn("Connection error after {} consecutive failures: {}", count, message)
        }
    }

    private fun clearConnectionError() {
        _consecutiveFailures.set(0)
        _connectionError.value = null
        log.debug("Connection error state cleared")
    }

    // ── Connection lifecycle ────────────────────────────────────────────────────

    private val connectLock = Any()

    // Set by close() under connectLock. A connect* that starts afterwards must
    // fail without building or keeping anything — the session that owned this
    // manager is gone, so a client published now would never be closed.
    private var closed = false

    /**
     * Retire the connection a `connect*` call is replacing — WITHOUT closing
     * the old client synchronously (audit C1).
     *
     * The old fix order was: `close()` (→ old OkHttp client closed) THEN
     * `_connectionVersion++`. But ReactiveKubeClient's informers only tear
     * down (running `informer.close()` in their `flatMapLatest` `finally`)
     * *after* the version bump propagates through the flow. So between the
     * synchronous `close()` and that async cancellation, fabric8 watch
     * threads kept hitting a dead client, threw, and spammed
     * `reportError`/`ResourceState.Error` — a "Unable to connect" flash on
     * every cluster switch (and the reconnect loop the team already fought).
     *
     * Now every `connect*` bumps `_connectionVersion` FIRST (cancels the old
     * informers) and hands the previous client/mock here. We close it on a
     * daemon thread after a short grace period, by which time the informer
     * cancellation has run `informer.close()` against a still-open client.
     * The old client lingering ~1s is harmless — it is being discarded and
     * no new work is routed to it (`_client` already points at the new one).
     *
     * The `Closeable.close()` path (session/window teardown) is deliberately
     * left synchronous: there the session scope is cancelled around it, and
     * making teardown async has far wider blast radius.
     *
     * The failure path uses the same order (see [tearDownAfterFailure]): fields
     * nulled, version bumped so the old informers cancel and park, previous
     * connection retired after the grace period. Before that, a failed switch
     * left the old informers running against a client closed 1 s later.
     */
    private fun retirePrevious(prevClient: KubernetesClient?, prevMock: MockClusterHandle?) {
        if (prevClient == null && prevMock == null) return
        Thread {
            try {
                Thread.sleep(RETIRE_GRACE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // Same teardown order as close(): mock handle first (ref-count
            // release, also closes its client), then the client (a no-op for
            // mock, real cleanup otherwise).
            runCatching { prevMock?.close() }
                .onFailure { log.warn("Error retiring previous mock handle: {}", it.message) }
            runCatching { prevClient?.close() }
                .onFailure { log.warn("Error retiring previous client: {}", it.message) }
            log.debug("Retired previous Kubernetes connection")
        }.apply {
            name = "kube-conn-retire"
            isDaemon = true
            start()
        }
    }

    fun connect(context: String? = null): Result<String> = synchronized(connectLock) {
        if (closed) return closedFailure()
        val prevClient = _client
        val prevMock = _mockHandle
        // The client under construction. Nothing is published until the
        // version probe succeeds, and on any failure the client is closed —
        // otherwise a 10 s retry loop leaks one client (and, with the Vert.x
        // HTTP client, one event loop) per attempt.
        var built: KubernetesClient? = null
        try {
            log.info("Connecting to cluster context={}", context ?: "<default>")
            log.debug("connect step 1/4: load config")
            val config = loadConfig(context)
            log.debug("connect step 2/4: build client (masterUrl={})", redactUrl(config.masterUrl))
            val c = buildClient(config)
            built = c
            log.debug("connect step 3/4: fetch /version")
            val v = c.kubernetesVersion ?: throw IllegalStateException("Cluster did not answer /version")
            log.debug("connect step 4/4: publish")
            _client = c
            _mockHandle = null
            _connectedContext = context ?: config.currentContext?.name
            clearConnectionError()
            _cachedFallbackContext = null
            log.info("Connected to cluster version={}.{} server={}", v.major, v.minor, redactUrl(config.masterUrl))
            // Bump version FIRST so the old informers cancel, THEN retire the
            // previous client off-thread after a grace period (audit C1).
            _connectionVersion.update { it + 1 }
            retirePrevious(prevClient, prevMock)
            Result.success("${v.major}.${v.minor}")
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception, so that NoClassDefFoundError /
            // LinkageError / OutOfMemoryError surface in the log + UI instead of
            // disappearing into the void and leaving the app stuck on the spinner.
            log.error("Failed to connect to cluster context={}", context, t)
            runCatching { built?.close() }
                .onFailure { log.warn("Error closing the client of a failed connect: {}", it.message) }
            tearDownAfterFailure(prevClient, prevMock)
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    private fun closedFailure(): Result<String> = Result.failure(IllegalStateException("Connection manager is closed"))

    /**
     * Failure path shared by every `connect*`. A failed connect means
     * "disconnected", not "still on the old cluster": the fields are nulled
     * first, then — only if there WAS a previous live connection — the version
     * is bumped so that connection's flows restart, observe `isConnected ==
     * false` and park (ReactiveInformerFactory.parkUnlessConnected), and only
     * then is the previous connection retired after the grace period. With no
     * previous connection nothing is bumped: the flows are dormant already.
     */
    private fun tearDownAfterFailure(prevClient: KubernetesClient?, prevMock: MockClusterHandle?) {
        _client = null
        _mockHandle = null
        _connectedContext = null
        if (prevClient != null || prevMock != null) {
            _connectionVersion.update { it + 1 }
        }
        retirePrevious(prevClient, prevMock)
    }

    fun connectWithClient(client: KubernetesClient, label: String): Result<String> = synchronized(connectLock) {
        if (closed) {
            // Ownership of [client] was handed to us; a closed manager can only
            // release it, never publish it.
            runCatching { client.close() }
                .onFailure { log.warn("Error closing a client handed to a closed manager: {}", it.message) }
            return closedFailure()
        }
        val prevClient = _client
        val prevMock = _mockHandle
        try {
            log.info("Connecting with pre-built client label={}", label)
            _client = client
            _mockHandle = null
            _connectedContext = label
            clearConnectionError()
            log.info("Connected via pre-built client label={}", label)
            _connectionVersion.update { it + 1 }
            retirePrevious(prevClient, prevMock)
            Result.success("mock")
        } catch (t: Throwable) {
            log.error("Failed to connect with pre-built client label={}", label, t)
            tearDownAfterFailure(prevClient, prevMock)
            // Ownership of [client] was handed to us; it was never published for
            // good, so release it — the same rule connect() applies to `built`.
            runCatching { client.close() }
                .onFailure { log.warn("Error closing the client of a failed pre-built connect: {}", it.message) }
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    fun connectWithMockHandle(handle: MockClusterHandle): Result<String> = synchronized(connectLock) {
        if (closed) {
            // Releases the provider ref-count (and the handle's client).
            runCatching { handle.close() }
                .onFailure { log.warn("Error releasing a mock handle handed to a closed manager: {}", it.message) }
            return closedFailure()
        }
        val prevClient = _client
        val prevMock = _mockHandle
        try {
            log.info("Connecting via mock handle '{}'", handle.label)
            _client = handle.client
            _mockHandle = handle
            _connectedContext = handle.label
            clearConnectionError()
            _connectionVersion.update { it + 1 }
            retirePrevious(prevClient, prevMock)
            Result.success("mock")
        } catch (t: Throwable) {
            log.error("Failed to connect via mock handle", t)
            tearDownAfterFailure(prevClient, prevMock)
            // Same rule as connectWithClient: release the handle we were given.
            runCatching { handle.close() }
                .onFailure { log.warn("Error releasing the mock handle of a failed connect: {}", it.message) }
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    /**
     * The context THIS session is connected to. Returns the session's connected
     * context if any, falling back to the kubeconfig's `current-context` read
     * through [KubeconfigReader] (no exec plugin, every `$KUBECONFIG` entry;
     * used before any session has connected). This is what
     * the cluster overview header / breadcrumbs read — different sessions must
     * see different values, otherwise multi-window all looks like one cluster.
     */
    @Volatile private var _cachedFallbackContext: String? = null

    // Its own monitor, NOT connectLock: connect() now holds connectLock for the
    // whole /version probe (verify-before-publish), and this fallback is read
    // during composition (palette entries) — sharing the lock would freeze the
    // UI thread for the length of a failing connect attempt.
    private val fallbackLock = Any()

    fun getCurrentContext(): String = _connectedContext ?: run {
        _cachedFallbackContext ?: synchronized(fallbackLock) {
            _cachedFallbackContext ?: KubeconfigReader.Default.currentContext()
                .also { _cachedFallbackContext = it }
        }
    }

    fun getClusterServer(): String = _client?.configuration?.masterUrl ?: ""

    // ── Closeable ───────────────────────────────────────────────────────────────

    /**
     * Takes [connectLock]: a `connect()` already in flight finishes and
     * publishes first, and this then closes what it published; a `connect*`
     * that starts after this returns fails at once (see [closed]). Before the
     * lock, a connect that outlived the session's 3 s close-join published a
     * client nobody ever closed.
     */
    override fun close() {
        synchronized(connectLock) {
            closed = true
            if (_client != null) {
                log.info("Closing Kubernetes client connection")
            }
            _mockHandle?.close() // releases ref-count; also closes the client for mock connections
            _mockHandle = null
            _client?.close() // no-op for mock (already closed above), real cleanup for non-mock
            _client = null
            _connectedContext = null
        }
    }

    private fun redactUrl(url: String?): String {
        if (url.isNullOrBlank()) return "<none>"
        return try {
            val u = java.net.URI(url)
            "${u.scheme}://<redacted>:${if (u.port > 0) u.port else "default"}"
        } catch (e: Exception) {
            "<unparseable>"
        }
    }
}
