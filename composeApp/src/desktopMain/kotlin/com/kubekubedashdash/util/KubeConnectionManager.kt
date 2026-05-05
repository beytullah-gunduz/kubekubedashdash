package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.internal.KubeConfigUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class ContextBinding(
    val name: String,
    val awsProfile: String?,
)

class KubeConnectionManager : Closeable {

    private val log = LoggerFactory.getLogger(KubeConnectionManager::class.java)

    private var _client: KubernetesClient? = null
    private var _mockHandle: MockClusterHandle? = null
    val isConnected: Boolean get() = _client != null

    val client: KubernetesClient
        get() = _client ?: throw IllegalStateException("Not connected to a cluster")

    // Remembers which kube context this manager is connected to. Required for
    // multi-window: each session has its own KubeConnectionManager and must
    // report ITS context, not the kubeconfig file's `current-context` default
    // (which is the same for every session in the process).
    private var _connectedContext: String? = null

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

    private val connectLock = Any()

    fun connect(context: String? = null): Result<String> = synchronized(connectLock) {
        try {
            log.info("Connecting to cluster context={}", context ?: "<default>")
            close()
            log.debug("connect step 1/4: Config.autoConfigure")
            val config = Config.autoConfigure(context)
            log.debug("connect step 2/4: KubernetesClientBuilder.build (masterUrl={})", config.masterUrl)
            val c = KubernetesClientBuilder().withConfig(config).build()
            _client = c
            _connectedContext = context ?: config.currentContext?.name
            log.debug("connect step 3/4: clearConnectionError")
            clearConnectionError()
            log.debug("connect step 4/4: fetch /version")
            val v = c.kubernetesVersion
            log.info("Connected to cluster version={}.{} server={}", v.major, v.minor, config.masterUrl)
            _connectionVersion.update { it + 1 }
            Result.success("${v.major}.${v.minor}")
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception, so that NoClassDefFoundError /
            // LinkageError / OutOfMemoryError surface in the log + UI instead of
            // disappearing into the void and leaving the app stuck on the spinner.
            log.error("Failed to connect to cluster context={}", context, t)
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    fun connectWithClient(client: KubernetesClient, label: String): Result<String> = synchronized(connectLock) {
        try {
            log.info("Connecting with pre-built client label={}", label)
            close()
            _client = client
            _connectedContext = label
            clearConnectionError()
            log.info("Connected via pre-built client label={}", label)
            _connectionVersion.update { it + 1 }
            Result.success("mock")
        } catch (t: Throwable) {
            log.error("Failed to connect with pre-built client label={}", label, t)
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    fun connectWithMockHandle(handle: MockClusterHandle): Result<String> = synchronized(connectLock) {
        try {
            log.info("Connecting via mock handle '{}'", handle.label)
            close()
            _client = handle.client
            _mockHandle = handle
            _connectedContext = handle.label
            clearConnectionError()
            _connectionVersion.update { it + 1 }
            Result.success("mock")
        } catch (t: Throwable) {
            log.error("Failed to connect via mock handle", t)
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    fun getContexts(): List<String> = try {
        val ctxs = Config.autoConfigure(null).contexts?.map { it.name } ?: emptyList()
        log.debug("Loaded {} kube contexts", ctxs.size)
        ctxs
    } catch (e: Exception) {
        log.warn("Failed to load kube contexts: {}", e.message)
        emptyList()
    }

    fun getContextBindings(): List<ContextBinding> {
        return try {
            val path = KubeconfigLocator.activePath()
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                return emptyList()
            }
            val raw = KubeConfigUtils.parseConfig(file)
            val users = raw.users.orEmpty().associateBy { it.name }
            raw.contexts.orEmpty().map { namedCtx ->
                val userName = namedCtx.context?.user
                val authInfo = users[userName]?.user
                val awsProfile = authInfo?.exec?.env.orEmpty()
                    .firstOrNull { it.name == "AWS_PROFILE" }?.value
                ContextBinding(name = namedCtx.name, awsProfile = awsProfile)
            }
        } catch (e: Exception) {
            log.warn("Failed to read context bindings: {}", e.message)
            emptyList()
        }
    }

    /**
     * The context THIS session is connected to. Returns the session's connected
     * context if any, falling back to the kubeconfig file's `current-context`
     * (the legacy behavior, used before any session has connected). This is what
     * the cluster overview header / breadcrumbs read — different sessions must
     * see different values, otherwise multi-window all looks like one cluster.
     */
    fun getCurrentContext(): String = _connectedContext ?: try {
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
        _mockHandle?.close() // releases ref-count; also closes the client for mock connections
        _mockHandle = null
        _client?.close() // no-op for mock (already closed above), real cleanup for non-mock
        _client = null
        _connectedContext = null
    }
}
