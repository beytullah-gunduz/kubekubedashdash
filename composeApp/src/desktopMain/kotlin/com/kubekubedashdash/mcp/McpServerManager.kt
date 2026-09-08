package com.kubekubedashdash.mcp

import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.util.isInterruption
import io.fabric8.kubernetes.client.KubernetesClientException
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

object McpServerManager {

    private val log = LoggerFactory.getLogger(McpServerManager::class.java)
    private val json = Json { prettyPrint = true }

    const val DEFAULT_PORT = 3001

    /**
     * `get_pod_logs` bounds. Without a cap, `tailLines: 2147483647` reaches
     * the API server as-is and a chatty pod's whole log lands in one String.
     */
    internal const val DEFAULT_TAIL_LINES = 100
    internal const val MAX_TAIL_LINES = 5_000

    /** Upper bound on concurrent blocking fabric8 calls made for MCP clients; see [blockingCall]. */
    internal const val MCP_WORKERS = 8

    private const val CONTEXT_ARG_DESC =
        "Target cluster kube-context. Optional when exactly one cluster is open; " +
            "when several are open, call the list_clusters tool and pass one of its contexts."

    internal val MCP_YAML_ALLOWLIST = setOf(
        "pod", "deployment", "service", "namespace",
        "statefulset", "daemonset", "replicaset",
        "job", "cronjob", "ingress",
        "persistentvolume", "persistentvolumeclaim", "storageclass",
        // Excluded: "secret" (data field is base64'd credentials),
        //           "configmap" (frequently misused for credentials),
        //           "node"      (kubelet bootstrap details).
    )

    private fun isYamlAllowed(kind: String): Boolean = kind.lowercase() in MCP_YAML_ALLOWLIST

    /** An all-digit request as a Long, `Long.MAX_VALUE` when it overflows one; anything else is null. */
    private fun requestedTail(raw: String?): Long? = raw?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { it.toLongOrNull() ?: Long.MAX_VALUE }

    /** Missing, non-numeric, zero or negative → the default; larger than [MAX_TAIL_LINES] (however large) → the cap. */
    internal fun clampTailLines(raw: String?): Int = requestedTail(raw)?.takeIf { it > 0 }?.coerceAtMost(MAX_TAIL_LINES.toLong())?.toInt() ?: DEFAULT_TAIL_LINES

    /** True when [raw] asked for more than [MAX_TAIL_LINES]: the result then carries [tailCapNotice] first. */
    internal fun tailLinesCapped(raw: String?): Boolean = (requestedTail(raw) ?: 0L) > MAX_TAIL_LINES

    internal fun tailCapNotice(raw: String): String = "Output limited to the last $MAX_TAIL_LINES lines (${raw.take(32)} requested)."

    // Ktor CIO runs the application pipeline — and so every tool and resource
    // handler — on Dispatchers.IO already. A blocking fabric8 call here never
    // stalled an event loop; it held one of IO's 64 permits for as long as
    // fabric8 kept retrying (10 s request timeout × 11 attempts on its default
    // budget; the app now caps it at 5 retries, see withBoundedRetries),
    // and enough of them starved the app's own informers and polls, which
    // share those permits. Two guards. The calls run on a limitedParallelism
    // view of IO: such a view delegates to the UNLIMITED scheduler, so its
    // MCP_WORKERS permits are granted in addition to IO's cap and MCP load can
    // no longer take the informers' permits at all; beyond MCP_WORKERS, calls
    // queue (suspended, holding no thread) until a permit frees — the cost is
    // latency on a burst against a dead cluster, which the cap on fabric8's
    // retry budget (F10) now bounds at about a minute. And runInterruptible rather than
    // withContext, so when the call is cancelled — Ktor cancels a connection's
    // calls on an abrupt client disconnect, and stop()'s EmbeddedServer.stop
    // cancels every call once its 1 s grace expires, on an enable/disable
    // toggle or the shutdown hook — the worker is freed at once instead of
    // parking for the fabric8 timeout. (The HTTP request itself is not
    // aborted: fabric8's OperationSupport.waitForResult never cancels the
    // future; the response arrives and is discarded.)
    private val mcpWorkers = Dispatchers.IO.limitedParallelism(MCP_WORKERS, "mcp-workers")

    internal suspend fun <T> blockingCall(block: () -> T): T = runInterruptible(mcpWorkers, block)

    /** `{"error": message}` with `isError`, the shape [toolResolutionError] uses. */
    private fun toolError(message: String) = CallToolResult(content = listOf(TextContent(text = buildJsonObject { put("error", message) }.toString())), isError = true)

    /** The API server's own sentence when fabric8 carried a `Status`; fabric8's own message, which embeds the request URL, only when it did not. */
    internal fun errorText(e: Throwable): String = (e as? KubernetesClientException)?.status?.message?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "Unknown error")

    /**
     * Every tool body runs in here (F9). A failure becomes an `isError` result
     * carrying the API's message, logged once at WARN without a trace — a 403
     * is not an app bug. An interrupted fabric8 call is a cancellation, not an
     * error: `blockingCall` interrupts the worker when the request is cancelled
     * (stop()'s grace period), fabric8 launders the InterruptedException into a
     * KubernetesClientException, and runInterruptible passes that through
     * unchanged even though the job is cancelled — so the cancelled job is
     * checked first, then the cause chain.
     */
    internal suspend fun toolCall(tool: String, block: suspend () -> CallToolResult): CallToolResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        if (isInterruption(e)) throw CancellationException("MCP tool $tool interrupted", e)
        log.warn("MCP tool {} failed ({}): {}", tool, e::class.simpleName, e.message)
        toolError(errorText(e))
    }

    // @Volatile + @Synchronized start/stop: these are mutated from start/stop
    // (invoked on Dispatchers.IO from SettingsScreenViewModel and from the JVM
    // shutdown hook) and read from other threads via the getters below. Without
    // this, rapid enable/disable/port-change toggles could interleave two
    // start()/stop() calls — binding two servers to one port, leaking a server
    // + its thread pool, or showing a bearerToken that doesn't match the
    // running server.
    @Volatile private var server: EmbeddedServer<*, *>? = null

    @Volatile private var _bearerToken: String? = null
    val bearerToken: String? get() = _bearerToken

    @Volatile private var _requireAuth: Boolean = true
    val requireAuth: Boolean get() = _requireAuth

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun startIfNotRunning(port: Int = DEFAULT_PORT) {
        if (server != null) return
        start(port)
    }

    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (server != null) {
            log.info("MCP server already running, stopping first")
            stop()
        }

        val localhostOnly = PreferenceRepository.mcpLocalhostOnly.value
        val requestedAuth = PreferenceRepository.mcpRequireAuth.value
        // Security hardening (audit S2): a non-localhost bind exposes the
        // connected cluster to the LAN. Authentication is then NOT optional —
        // force it on even if the user turned it off, so the "0.0.0.0 + no
        // token" combination is unreachable at runtime. localhost-only + no
        // auth is still permitted (loopback, low risk). The Settings UI still
        // shows its inline warning; this is the non-bypassable backstop.
        _requireAuth = requestedAuth || !localhostOnly
        val bindHost = if (localhostOnly) "127.0.0.1" else "0.0.0.0"

        _bearerToken = if (_requireAuth) {
            java.security.SecureRandom.getInstanceStrong().let { sr ->
                ByteArray(32).also { sr.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            }
        } else {
            null
        }

        if (!localhostOnly && !requestedAuth) {
            log.warn(
                "MCP server bound to {} (LAN-exposed); authentication was force-enabled " +
                    "despite the 'require auth' preference being off (audit S2).",
                bindHost,
            )
        }

        log.info("Starting MCP server host={} port={} auth={} localhostOnly={}", bindHost, port, _requireAuth, localhostOnly)

        try {
            val mcpServer = createMcpServer()
            val capturedToken = _bearerToken
            val capturedRequireAuth = _requireAuth
            val ktorServer = embeddedServer(CIO, host = bindHost, port = port) {
                installMcpAuth(localhostOnly, capturedRequireAuth, port, capturedToken)
                mcp { mcpServer }
            }
            ktorServer.start(wait = false)
            server = ktorServer
            log.info("MCP server started on http://{}:{}/sse", bindHost, port)
        } catch (e: Exception) {
            log.error("Failed to start MCP server: {}", e.message, e)
            server = null
            _bearerToken = null
        }
    }

    @Synchronized
    fun stop() {
        server?.let {
            log.info("Stopping MCP server")
            try {
                it.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
            } catch (e: Exception) {
                log.warn("Error stopping MCP server: {}", e.message)
            }
            server = null
            _bearerToken = null
            _requireAuth = true
            log.info("MCP server stopped")
        }
    }

    // Audit A3: MCP is a single endpoint but the app is multi-window, so it
    // cannot infer which open cluster a call means. Callers discover clusters
    // via `list_clusters` and pass `context`; resolution is explicit.
    private fun resolutionErrorJson(r: McpClusterResolver.ClusterResolution<*>): String = when (r) {
        McpClusterResolver.ClusterResolution.NoneConnected ->
            """{"error":"No cluster connected. Open a cluster tab first."}"""

        is McpClusterResolver.ClusterResolution.Ambiguous ->
            buildJsonObject {
                put("error", "Multiple clusters are open — pass a 'context' argument (call the list_clusters tool to discover them).")
                putJsonArray("availableContexts") { r.available.forEach { add(it) } }
            }.toString()

        is McpClusterResolver.ClusterResolution.UnknownContext ->
            buildJsonObject {
                put("error", "No open cluster matches context '${r.requested}'. Pass one of availableContexts (call list_clusters).")
                putJsonArray("availableContexts") { r.available.forEach { add(it) } }
            }.toString()

        is McpClusterResolver.ClusterResolution.Resolved<*> -> "" // not an error; unused
    }

    private fun toolResolutionError(r: McpClusterResolver.ClusterResolution<*>) = CallToolResult(content = listOf(TextContent(text = resolutionErrorJson(r))), isError = true)

    internal fun createMcpServer(): Server {
        val mcpServer = Server(
            serverInfo = Implementation(
                name = "kubedash",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        // ── Resources ───────────────────────────────────────────────────────────

        registerResource(mcpServer, "kubedash://cluster/overview", "Cluster Overview", "Cluster overview information including node/pod counts and status") {
            // Resources take no arguments, so they can only serve the single
            // open cluster. With several open the caller must use the
            // context-addressable tools instead (audit A3).
            val kubeClient = when (val r = McpClusterResolver.resolve(null)) {
                is McpClusterResolver.ClusterResolution.Resolved -> r.client
                else -> return@registerResource resolutionErrorJson(r)
            }
            blockingCall { json.encodeToString(kubeClient.getClusterInfo(namespace = null)) }
        }

        registerResource(mcpServer, "kubedash://resource-usage", "Resource Usage", "Cluster CPU and memory usage summary") {
            val kubeClient = when (val r = McpClusterResolver.resolve(null)) {
                is McpClusterResolver.ClusterResolution.Resolved -> r.client
                else -> return@registerResource resolutionErrorJson(r)
            }
            blockingCall { json.encodeToString(kubeClient.getResourceUsage(namespace = null)) }
        }

        // ── Tools ───────────────────────────────────────────────────────────────

        mcpServer.addTool(
            name = "get_resource_yaml",
            description = "Get the raw YAML definition of a specific Kubernetes resource",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "kind",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Resource kind. Allowed: " + MCP_YAML_ALLOWLIST.sorted().joinToString(", "))
                        },
                    )
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Resource name")
                        },
                    )
                    put(
                        "namespace",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Resource namespace (optional for cluster-scoped resources)")
                        },
                    )
                    put(
                        "context",
                        buildJsonObject {
                            put("type", "string")
                            put("description", CONTEXT_ARG_DESC)
                        },
                    )
                },
                required = listOf("kind", "name"),
            ),
        ) { request ->
            toolCall("get_resource_yaml") {
                val ctx = request.arguments?.get("context")?.jsonPrimitive?.content
                val kubeClient = when (val r = McpClusterResolver.resolve(ctx)) {
                    is McpClusterResolver.ClusterResolution.Resolved -> r.client
                    else -> return@toolCall toolResolutionError(r)
                }
                val kind = request.arguments?.get("kind")?.jsonPrimitive?.content ?: ""
                if (!isYamlAllowed(kind)) {
                    return@toolCall toolError("Kind '$kind' is not exposed via MCP. Allowed kinds: ${MCP_YAML_ALLOWLIST.sorted().joinToString(", ")}")
                }
                val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: ""
                val namespace = request.arguments?.get("namespace")?.jsonPrimitive?.content
                val yaml = blockingCall { kubeClient.fetchResourceYaml(kind, name, namespace) }
                    ?: return@toolCall toolError("Resource not found: $kind '$name'" + (namespace?.let { " in namespace '$it'" } ?: ""))
                CallToolResult(content = listOf(TextContent(text = yaml)))
            }
        }

        mcpServer.addTool(
            name = "get_pod_logs",
            description = "Get recent logs from a specific pod",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Pod name")
                        },
                    )
                    put(
                        "namespace",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Pod namespace")
                        },
                    )
                    put(
                        "container",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Container name (optional, defaults to first container)")
                        },
                    )
                    put(
                        "tailLines",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "Number of lines to fetch from the end (default $DEFAULT_TAIL_LINES, at most $MAX_TAIL_LINES; a notice precedes the logs when the request was capped)")
                        },
                    )
                    put(
                        "context",
                        buildJsonObject {
                            put("type", "string")
                            put("description", CONTEXT_ARG_DESC)
                        },
                    )
                },
                required = listOf("name", "namespace"),
            ),
        ) { request ->
            toolCall("get_pod_logs") {
                val ctx = request.arguments?.get("context")?.jsonPrimitive?.content
                val kubeClient = when (val r = McpClusterResolver.resolve(ctx)) {
                    is McpClusterResolver.ClusterResolution.Resolved -> r.client
                    else -> return@toolCall toolResolutionError(r)
                }
                val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: ""
                val namespace = request.arguments?.get("namespace")?.jsonPrimitive?.content ?: ""
                val container = request.arguments?.get("container")?.jsonPrimitive?.content
                val rawTail = request.arguments?.get("tailLines")?.jsonPrimitive?.content
                val tailLines = clampTailLines(rawTail)
                val logs = blockingCall { kubeClient.fetchPodLogs(name, namespace, container, tailLines) }
                // The notice is its own block, first: the log text is raw pod output.
                val content = buildList {
                    if (tailLinesCapped(rawTail)) add(TextContent(text = tailCapNotice(rawTail ?: "")))
                    add(TextContent(text = logs))
                }
                CallToolResult(content = content)
            }
        }

        mcpServer.addTool(
            name = "list_resources",
            description = "List Kubernetes resources of a given kind, optionally filtered by namespace",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "kind",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Resource kind: Pod, Deployment, Service, Event, Namespace, " +
                                    "StatefulSet, DaemonSet, ReplicaSet, Job, CronJob, " +
                                    "Ingress, Endpoint, NetworkPolicy, PersistentVolume, PersistentVolumeClaim, StorageClass",
                            )
                        },
                    )
                    put(
                        "namespace",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Namespace to filter by (optional, omit for all namespaces or cluster-scoped resources)")
                        },
                    )
                    put(
                        "context",
                        buildJsonObject {
                            put("type", "string")
                            put("description", CONTEXT_ARG_DESC)
                        },
                    )
                },
                required = listOf("kind"),
            ),
        ) { request ->
            toolCall("list_resources") {
                val ctx = request.arguments?.get("context")?.jsonPrimitive?.content
                val kubeClient = when (val r = McpClusterResolver.resolve(ctx)) {
                    is McpClusterResolver.ClusterResolution.Resolved -> r.client
                    else -> return@toolCall toolResolutionError(r)
                }
                val kind = request.arguments?.get("kind")?.jsonPrimitive?.content ?: ""
                val ns = request.arguments?.get("namespace")?.jsonPrimitive?.content
                val result = blockingCall {
                    when (kind.lowercase()) {
                        "pod" -> json.encodeToString(kubeClient.getPods(ns))
                        "deployment" -> json.encodeToString(kubeClient.getDeployments(ns))
                        "service" -> json.encodeToString(kubeClient.getServices(ns))
                        "event" -> json.encodeToString(kubeClient.getEvents(ns))
                        "namespace" -> json.encodeToString(kubeClient.getNamespacesGeneric())
                        "statefulset" -> json.encodeToString(kubeClient.getStatefulSets(ns))
                        "daemonset" -> json.encodeToString(kubeClient.getDaemonSets(ns))
                        "replicaset" -> json.encodeToString(kubeClient.getReplicaSets(ns))
                        "job" -> json.encodeToString(kubeClient.getJobs(ns))
                        "cronjob" -> json.encodeToString(kubeClient.getCronJobs(ns))
                        "ingress" -> json.encodeToString(kubeClient.getIngresses(ns))
                        "endpoint" -> json.encodeToString(kubeClient.getEndpoints(ns))
                        "networkpolicy" -> json.encodeToString(kubeClient.getNetworkPolicies(ns))
                        "persistentvolume" -> json.encodeToString(kubeClient.getPersistentVolumes())
                        "persistentvolumeclaim" -> json.encodeToString(kubeClient.getPersistentVolumeClaims(ns))
                        "storageclass" -> json.encodeToString(kubeClient.getStorageClasses())
                        else -> null
                    }
                } ?: return@toolCall toolError("Unknown resource kind: $kind")
                CallToolResult(content = listOf(TextContent(text = result)))
            }
        }

        mcpServer.addTool(
            name = "list_clusters",
            description = "List the Kubernetes clusters (kube contexts) currently open in KubeKubeDashDash. " +
                "When more than one is open, pass one of these as the 'context' argument to the other tools/resources.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { _ ->
            toolCall("list_clusters") {
                val ctxs = McpClusterResolver.listContexts()
                val payload = buildJsonObject {
                    putJsonArray("clusters") { ctxs.forEach { add(it) } }
                    // true ⇒ a 'context' arg is required by the other tools.
                    put("ambiguous", ctxs.size > 1)
                }
                CallToolResult(content = listOf(TextContent(text = payload.toString())))
            }
        }

        return mcpServer
    }

    private fun registerResource(
        mcpServer: Server,
        uri: String,
        name: String,
        description: String,
        fetchContent: suspend () -> String,
    ) {
        mcpServer.addResource(
            uri = uri,
            name = name,
            description = description,
            mimeType = "application/json",
        ) { request ->
            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(
                        text = fetchContent(),
                        uri = request.uri,
                        mimeType = "application/json",
                    ),
                ),
            )
        }
    }
}
