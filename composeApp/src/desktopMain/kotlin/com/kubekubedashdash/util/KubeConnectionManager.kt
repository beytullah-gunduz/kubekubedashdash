package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class KubeConnectionManager : Closeable {

    private val log = LoggerFactory.getLogger(KubeConnectionManager::class.java)

    private var _client: KubernetesClient? = null
    val isConnected: Boolean get() = _client != null

    val client: KubernetesClient
        get() = _client ?: throw IllegalStateException("Not connected to a cluster")

    // ── Connection version (incremented on each connect, used by reactive flows) ─
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

    fun connect(context: String? = null): Result<String> = try {
        log.info("Connecting to cluster context={}", context ?: "<default>")
        close()
        val config = Config.autoConfigure(context)
        _client = KubernetesClientBuilder().withConfig(config).build()
        clearConnectionError()
        val v = _client!!.kubernetesVersion
        log.info("Connected to cluster version={}.{} server={}", v.major, v.minor, config.masterUrl)
        _connectionVersion.value++
        Result.success("${v.major}.${v.minor}")
    } catch (e: Exception) {
        log.error("Failed to connect to cluster context={}: {}", context, e.message)
        Result.failure(e)
    }

    fun connectWithClient(client: KubernetesClient, label: String): Result<String> = try {
        log.info("Connecting with pre-built client label={}", label)
        close()
        _client = client
        clearConnectionError()
        log.info("Connected via pre-built client label={}", label)
        _connectionVersion.value++
        Result.success("mock")
    } catch (e: Exception) {
        log.error("Failed to connect with pre-built client label={}: {}", label, e.message)
        Result.failure(e)
    }

    fun getContexts(): List<String> = try {
        val ctxs = Config.autoConfigure(null).contexts?.map { it.name } ?: emptyList()
        log.debug("Loaded {} kube contexts", ctxs.size)
        ctxs
    } catch (e: Exception) {
        log.warn("Failed to load kube contexts: {}", e.message)
        emptyList()
    }

    fun getCurrentContext(): String = try {
        Config.autoConfigure(null).currentContext?.name ?: ""
    } catch (e: Exception) {
        log.warn("Failed to get current context: {}", e.message)
        ""
    }

    fun getClusterServer(): String = _client?.configuration?.masterUrl ?: ""

    // ── Closeable ───────────────────────────────────────────────────────────────

    override fun close() {
        if (_client != null) {
            log.info("Closing Kubernetes client connection")
        }
        _client?.close()
        _client = null
    }
}
