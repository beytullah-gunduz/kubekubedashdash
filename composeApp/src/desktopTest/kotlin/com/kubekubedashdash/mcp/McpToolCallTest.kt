package com.kubekubedashdash.mcp

import com.kubekubedashdash.util.KubeClient
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.dsl.HttpMethod
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What an MCP client receives from the tools when a fetch fails, when a
 * resource is missing, when a kind is unknown, and when `tailLines` was
 * capped (review follow-up F9). Failures are `isError` results carrying the
 * API server's own message — never a normal text result that looks like a
 * log or a YAML document — and a capped request is announced in a block of
 * its own, before the logs.
 *
 * The registered handlers are called directly through `Server.tools`; the
 * cluster comes from a loopback mock server through the resolver's test
 * seam. Nothing here opens a real connection or reads any real user state.
 */
class McpToolCallTest {

    /** `/log` is served (or refused) here; everything else goes to the CRUD dispatcher. */
    private class LogDispatcher(private val crud: KubernetesCrudDispatcher) : Dispatcher() {
        @Volatile var denyLogs = false

        @Volatile var lastLogPath: String? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.substringBefore('?')
            if (request.method() == HttpMethod.GET && path.endsWith("/log")) {
                lastLogPath = request.path
                return if (denyLogs) {
                    MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody(
                        """{"kind":"Status","apiVersion":"v1","status":"Failure","message":"pods \"p\" is forbidden: no access to logs","reason":"Forbidden","code":403}""",
                    )
                } else {
                    MockResponse().setResponseCode(200).setBody("alpha\nbravo\ncharlie\n")
                }
            }
            return crud.dispatch(request)
        }
    }

    private lateinit var dispatcher: LogDispatcher
    private lateinit var mock: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var kubeClient: KubeClient
    private lateinit var server: Server
    private lateinit var previousSource: () -> List<Pair<String, KubeClient>>

    @BeforeTest
    fun setUp() {
        dispatcher = LogDispatcher(KubernetesCrudDispatcher())
        mock = KubernetesMockServer(Context(), MockWebServer(), HashMap(), dispatcher, false)
        mock.init()
        seed = mock.createClient()
        manager = KubeConnectionManager()
        manager.connectWithClient(mock.createClient(), "cluster-a").getOrThrow()
        kubeClient = KubeClient(manager)
        previousSource = McpClusterResolver.clusterSource
        McpClusterResolver.clusterSource = { listOf("cluster-a" to kubeClient) }
        server = McpServerManager.createMcpServer()
    }

    @AfterTest
    fun tearDown() {
        McpClusterResolver.clusterSource = previousSource
        shutdownCleanly(label = "McpToolCallTest", manager = manager, client = seed, servers = listOf(mock))
    }

    private fun call(tool: String, arguments: JsonObject): CallToolResult = runBlocking {
        server.tools.getValue(tool).handler(CallToolRequest(CallToolRequestParams(name = tool, arguments = arguments)))
    }

    private fun texts(result: CallToolResult): List<String> = result.content.map { (it as TextContent).text }

    /** The `error` field of an `isError` result's single JSON block. */
    private fun errorOf(result: CallToolResult): String {
        assertEquals(true, result.isError, "expected an error result, got: ${texts(result)}")
        val block = texts(result).single()
        val json = Json.parseToJsonElement(block).jsonObject
        return assertNotNull(json["error"], "an error result is {\"error\": …}, got: $block").jsonPrimitive.content
    }

    @Test
    fun `get_pod_logs on a forbidden pod is an error result carrying the API's message`() {
        dispatcher.denyLogs = true

        val result = call(
            "get_pod_logs",
            buildJsonObject {
                put("name", "p")
                put("namespace", "ns")
            },
        )

        val error = errorOf(result)
        assertTrue(error.contains("forbidden"), "the API server's sentence must reach the client, got: $error")
        assertTrue(!error.contains("127.0.0.1"), "fabric8's request URL must not be echoed, got: $error")
    }

    @Test
    fun `get_pod_logs returns the logs as a normal result`() {
        val result = call(
            "get_pod_logs",
            buildJsonObject {
                put("name", "p")
                put("namespace", "ns")
            },
        )

        assertNotEquals(true, result.isError)
        assertEquals(listOf("alpha\nbravo\ncharlie\n"), texts(result))
    }

    @Test
    fun `get_pod_logs announces a capped tailLines in its own block before the logs`() {
        val result = call(
            "get_pod_logs",
            buildJsonObject {
                put("name", "p")
                put("namespace", "ns")
                put("tailLines", "99999999999")
            },
        )

        assertNotEquals(true, result.isError)
        val blocks = texts(result)
        assertEquals(2, blocks.size, "notice + logs, got: $blocks")
        assertEquals(McpServerManager.tailCapNotice("99999999999"), blocks[0])
        assertTrue(blocks[0].contains(McpServerManager.MAX_TAIL_LINES.toString()), "the notice names the cap, got: ${blocks[0]}")
        assertEquals("alpha\nbravo\ncharlie\n", blocks[1])
        val path = assertNotNull(dispatcher.lastLogPath)
        assertTrue(path.contains("tailLines=${McpServerManager.MAX_TAIL_LINES}"), "an overflowing request is capped, not defaulted, got: $path")
    }

    @Test
    fun `get_pod_logs within the cap carries no notice`() {
        val result = call(
            "get_pod_logs",
            buildJsonObject {
                put("name", "p")
                put("namespace", "ns")
                put("tailLines", "50")
            },
        )

        assertEquals(1, result.content.size)
        val path = assertNotNull(dispatcher.lastLogPath)
        assertTrue(path.contains("tailLines=50"), "got: $path")
    }

    @Test
    fun `get_resource_yaml on a missing resource is an error result`() {
        val result = call(
            "get_resource_yaml",
            buildJsonObject {
                put("kind", "pod")
                put("name", "ghost")
                put("namespace", "ns")
            },
        )

        val error = errorOf(result)
        assertTrue(error.contains("not found") && error.contains("ghost"), "got: $error")
    }

    @Test
    fun `get_resource_yaml returns the YAML of an existing resource`() {
        seed.pods().inNamespace("ns").resource(PodBuilder().withNewMetadata().withName("p").withNamespace("ns").endMetadata().build()).create()

        val result = call(
            "get_resource_yaml",
            buildJsonObject {
                put("kind", "pod")
                put("name", "p")
                put("namespace", "ns")
            },
        )

        assertNotEquals(true, result.isError)
        val yaml = texts(result).single()
        assertTrue(yaml.contains("kind:") && yaml.contains("Pod"), "got: $yaml")
    }

    @Test
    fun `get_resource_yaml on a kind outside the allowlist is an error result`() {
        val result = call(
            "get_resource_yaml",
            buildJsonObject {
                put("kind", "secret")
                put("name", "s")
                put("namespace", "ns")
            },
        )

        assertTrue(errorOf(result).contains("not exposed"))
    }

    @Test
    fun `list_resources with an unknown kind is an error result`() {
        val result = call("list_resources", buildJsonObject { put("kind", "widget") })

        assertTrue(errorOf(result).contains("Unknown resource kind"))
    }

    @Test
    fun `an unresolved cluster is still an error result`() {
        McpClusterResolver.clusterSource = { emptyList() }

        val result = call(
            "get_pod_logs",
            buildJsonObject {
                put("name", "p")
                put("namespace", "ns")
            },
        )

        assertTrue(errorOf(result).contains("No cluster connected"))
    }
}
