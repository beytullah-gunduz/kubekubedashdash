package com.kubekubedashdash.mcp

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A client that drops its SSE connection while a tool call is still blocking in
 * [McpServerManager.blockingCall] must have that worker interrupted within seconds,
 * over the real CIO + SSE transport ([mcpModule]), with the server left running. The
 * tool handler lives in the SDK's per-connection handler scope, which the SDK cancels
 * only when the SSE call ends; Ktor ends a call on a closed connection only with
 * HttpRequestLifecycle's cancelCallOnClose, so without it the call would hold its
 * MCP worker until fabric8 gave up. The SSE stream has its own client so closing it
 * leaves the POST connections alone. Binds an ephemeral loopback port only; no real
 * user state.
 */
class McpDisconnectInterruptsToolTest {

    @Test
    fun `a client dropping its SSE connection interrupts a tool call that is still blocking`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val mcpServer = Server(
            serverInfo = Implementation(name = "test", version = "0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        mcpServer.addTool(
            name = "hang",
            description = "Blocks until interrupted",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { _ ->
            McpServerManager.toolCall("hang") {
                McpServerManager.blockingCall {
                    started.countDown()
                    try {
                        Thread.sleep(60_000)
                    } catch (e: InterruptedException) {
                        interrupted.countDown()
                        throw e
                    }
                    error("the blocking call was never interrupted")
                }
            }
        }
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val ktor = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            mcpModule(localhostOnly = true, requireAuth = false, port = port, expectedToken = null, mcpServer = mcpServer)
        }.start(wait = false)
        val sseClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
        try {
            val base = URI.create("http://127.0.0.1:$port/")
            val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
            val sse = sseClient.send(
                HttpRequest.newBuilder(base).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines(),
            )
            val lines = LinkedBlockingQueue<String>()
            Thread({ runCatching { sse.body().forEach(lines::add) } }, "test-sse").apply { isDaemon = true }.start()
            fun awaitLine(predicate: (String) -> Boolean): String {
                while (true) {
                    val line = lines.poll(10, TimeUnit.SECONDS) ?: error("SSE stream went quiet")
                    if (predicate(line)) return line
                }
            }
            val endpoint = awaitLine { it.startsWith("data:") && "sessionId=" in it }.removePrefix("data:").trim()
            val post = base.resolve(endpoint)
            fun send(body: String) {
                val r = http.send(
                    HttpRequest.newBuilder(post).header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                check(r.statusCode() / 100 == 2) { "POST answered ${r.statusCode()}" }
            }
            send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""")
            awaitLine { it.startsWith("data:") && "\"id\":1" in it }
            send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hang","arguments":{}}}""")
            assertTrue(started.await(10, TimeUnit.SECONDS), "the tool never started")
            assertEquals(1, mcpServer.sessions.size)

            sseClient.shutdownNow()

            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "dropping the SSE connection did not interrupt the blocking tool call")
            // The SDK drops the session right after cancelling its handlers; poll rather than race it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (mcpServer.sessions.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(0, mcpServer.sessions.size, "the abandoned session was never released")
        } finally {
            sseClient.shutdownNow()
            ktor.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        }
    }
}
