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
import kotlin.test.assertTrue

/**
 * A tool call that is still blocking in [McpServerManager.blockingCall] when the
 * server stops must have its worker interrupted once the stop grace period expires,
 * over the real CIO + SSE transport ([mcpModule]). Since MCP SDK 0.15 a tool handler
 * is a job in the SDK's per-connection handler scope, not a child of any Ktor call,
 * so stop() reaches it only by ending the SSE call, which closes the transport and
 * cancels that scope (a client's notifications/cancelled is the only other path);
 * this pins that it still does. Binds an ephemeral loopback port only; no real user
 * state.
 */
class McpStopInterruptsToolTest {

    @Test
    fun `stopping the server interrupts a tool call that is still blocking`() {
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
        try {
            val base = URI.create("http://127.0.0.1:$port/")
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val sse = http.send(
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
        } finally {
            ktor.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "stop() did not interrupt the blocking tool call")
    }
}
