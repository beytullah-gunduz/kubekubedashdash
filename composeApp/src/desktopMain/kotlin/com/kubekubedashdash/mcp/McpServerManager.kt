package com.kubekubedashdash.mcp

import com.kubekubedashdash.data.repository.PreferenceRepository
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

    private const val CONTEXT_ARG_DESC =
        "Target cluster kube-context. Optional when exactly one cluster is open; " +
            "when several are open, call the list_clusters tool and pass one of its contexts."

    private val MCP_YAML_ALLOWLIST = setOf(
        "pod", "deployment", "service", "namespace",
        "statefulset", "daemonset", "replicaset",
        "job", "cronjob", "ingress",
        "persistentvolume", "persistentvolumeclaim", "storageclass",
        // Excluded: "secret" (data field is base64'd credentials),
        //           "configmap" (frequently misused for credentials),
        //           "node"      (kubelet bootstrap details).
    )

    private var server: EmbeddedServer<*, *>? = null

    private var _bearerToken: String? = null
    val bearerToken: String? get() = _bearerToken

    private var _requireAuth: Boolean = true
    val requireAuth: Boolean get() = _requireAuth

    val isRunning: Boolean get() = server != null

    fun start(port: Int = DEFAULT_PORT) {
        if (server != null) {
            log.info("MCP server already running, stopping first")
            stop()
        }

        val localhostOnly = PreferenceRepository.mcpLocalhostOnly.value
        _requireAuth = PreferenceRepository.mcpRequireAuth.value
        val bindHost = if (localhostOnly) "127.0.0.1" else "0.0.0.0"

        _bearerToken = if (_requireAuth) {
            java.security.SecureRandom.getInstanceStrong().let { sr ->
                ByteArray(32).also { sr.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            }
        } else {
            null
        }

        if (!localhostOnly && !_requireAuth) {
            log.warn("MCP server starting with NEITHER localhost-only nor authentication. Open to LAN, no token required.")
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

    private fun createMcpServer(): Server {
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
            json.encodeToString(kubeClient.getClusterInfo(namespace = null))
        }

        registerResource(mcpServer, "kubedash://resource-usage", "Resource Usage", "Cluster CPU and memory usage summary") {
            val kubeClient = when (val r = McpClusterResolver.resolve(null)) {
                is McpClusterResolver.ClusterResolution.Resolved -> r.client
                else -> return@registerResource resolutionErrorJson(r)
            }
            json.encodeToString(kubeClient.getResourceUsage(namespace = null))
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
            val ctx = request.arguments?.get("context")?.jsonPrimitive?.content
            val kubeClient = when (val r = McpClusterResolver.resolve(ctx)) {
                is McpClusterResolver.ClusterResolution.Resolved -> r.client
                else -> return@addTool toolResolutionError(r)
            }
            val kind = request.arguments?.get("kind")?.jsonPrimitive?.content ?: ""
            if (kind.lowercase() !in MCP_YAML_ALLOWLIST) {
                return@addTool CallToolResult(
                    content = listOf(TextContent(text = """{"error":"Kind '$kind' is not exposed via MCP. Allowed kinds: ${MCP_YAML_ALLOWLIST.sorted().joinToString(", ")}"}""")),
                    isError = true,
                )
            }
            val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: ""
            val namespace = request.arguments?.get("namespace")?.jsonPrimitive?.content
            val yaml = kubeClient.getResourceYaml(kind, name, namespace)
            CallToolResult(content = listOf(TextContent(text = yaml)))
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
                            put("description", "Number of lines to fetch from the end (default: 100)")
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
            val ctx = request.arguments?.get("context")?.jsonPrimitive?.content
            val kubeClient = when (val r = McpClusterResolver.resolve(ctx)) {
                is McpClusterResolver.ClusterResolution.Resolved -> r.client
                else -> return@addTool toolResolutionError(r)
            }
            val name = request.arguments?.get("name")?.jsonPrimitive?.content ?: ""
            val namespace = request.arguments?.get("namespace")?.jsonPrimitive?.content ?: ""
            val container = request.arguments?.get("container")?.jsonPrimitive?.content
            val tailLines = request.arguments?.get("tailLines")?.jsonPrimitive?.content?.toIntOrNull() ?: 100
            val logs = kubeClient.getPodLogs(name, namespace, container, tailLines)
            CallToolResult(content = listOf(TextContent(text = logs)))
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
                                "Resource kind: Pod, Deployment, Service, Event, Node, Namespace, " +
                                    "ConfigMap, Secret, StatefulSet, DaemonSet, ReplicaSet, Job, CronJob, " +
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
            val ctx = request.arguments?.get("context")?.jsonPrimitive?.content
            val kubeClient = when (val r = McpClusterResolver.resolve(ctx)) {
                is McpClusterResolver.ClusterResolution.Resolved -> r.client
                else -> return@addTool toolResolutionError(r)
            }
            val kind = request.arguments?.get("kind")?.jsonPrimitive?.content ?: ""
            val ns = request.arguments?.get("namespace")?.jsonPrimitive?.content
            val result = when (kind.lowercase()) {
                "pod" -> json.encodeToString(kubeClient.getPods(ns))
                "deployment" -> json.encodeToString(kubeClient.getDeployments(ns))
                "service" -> json.encodeToString(kubeClient.getServices(ns))
                "event" -> json.encodeToString(kubeClient.getEvents(ns))
                "node" -> json.encodeToString(kubeClient.getNodes())
                "namespace" -> json.encodeToString(kubeClient.getNamespacesGeneric())
                "configmap" -> json.encodeToString(kubeClient.getConfigMaps(ns))
                "secret" -> json.encodeToString(kubeClient.getSecrets(ns))
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
                else -> """{ "error": "Unknown resource kind: $kind" }"""
            }
            CallToolResult(content = listOf(TextContent(text = result)))
        }

        mcpServer.addTool(
            name = "list_clusters",
            description = "List the Kubernetes clusters (kube contexts) currently open in KubeKubeDashDash. " +
                "When more than one is open, pass one of these as the 'context' argument to the other tools/resources.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { _ ->
            val ctxs = McpClusterResolver.listContexts()
            val payload = buildJsonObject {
                putJsonArray("clusters") { ctxs.forEach { add(it) } }
                // true ⇒ a 'context' arg is required by the other tools.
                put("ambiguous", ctxs.size > 1)
            }
            CallToolResult(content = listOf(TextContent(text = payload.toString())))
        }

        return mcpServer
    }

    private fun registerResource(
        mcpServer: Server,
        uri: String,
        name: String,
        description: String,
        fetchContent: () -> String,
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
