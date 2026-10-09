package com.kubekubedashdash.uismoke

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** The MCP server answered with an error, timed out, or exited. */
internal class McpError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The Compose Hot Reload MCP server (`hotMcpServerDesktop`, stdio) with an MCP SDK client on it.
 * The server follows the worktree's hot-run pid file, so one server serves every scenario.
 */
internal class HotReloadMcp(private val repo: File, private val env: Map<String, String>, private val errLog: File) {
    private var process: Process? = null
    private var client: Client? = null
    private var transport: StdioClientTransport? = null

    @Volatile
    private var transportClosed = false

    /** True once the server answered `initialize`. */
    @Volatile
    var ready = false
        private set

    /** True once the server process exited or its stdio transport closed. */
    val dead: Boolean get() = transportClosed || process?.isAlive == false

    /**
     * Spawns the server. The handshake waits for [awaitInitialized]: the SDK gives `initialize`
     * 60 s whatever the client options say, and Gradle may need longer than that to bring the
     * server up.
     */
    fun start() {
        val builder = ProcessBuilder("./gradlew", "--no-daemon", "--quiet", "--console=plain", MCP_TASK)
            .directory(repo)
            .redirectError(errLog)
        builder.environment().apply {
            clear()
            putAll(env)
        }
        val started = builder.start()
        process = started
        transport = StdioClientTransport(started.inputStream.asSource().buffered(), started.outputStream.asSink().buffered())
        client = object : Client(Implementation("kkdd-ui-smoke", "1")) {
            override fun onClose() {
                transportClosed = true
            }
        }
    }

    /** Waits for the server's JVM to appear under the Gradle client, then runs the handshake, once. */
    fun awaitInitialized(timeout: Duration = 10.minutes) {
        if (ready) return
        val started = process ?: throw McpError("the MCP server was never started")
        val mcpClient = client ?: throw McpError("the MCP server was never started")
        val mcpTransport = transport ?: throw McpError("the MCP server was never started")
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (started.descendants().noneMatch { it.info().commandLine().orElse("").contains(SERVER_MAIN_CLASS) }) {
            if (dead) throw McpError("the MCP server exited before it started (see mcp.err in the run directory)")
            if (System.nanoTime() >= deadline) throw McpError("the MCP server JVM did not start within ${timeout.inWholeSeconds} s (see mcp.err in the run directory)")
            Thread.sleep(500)
        }
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L).nanoseconds
        try {
            runBlocking { withTimeout(remaining) { mcpClient.connect(mcpTransport) } }
        } catch (e: Exception) {
            throw McpError("the MCP handshake failed (see mcp.err in the run directory): ${e.message}", e)
        }
        ready = true
    }

    /** tools/call; returns the first text content ("" when none). An error result raises [McpError]. */
    fun call(name: String, arguments: Map<String, Any?> = emptyMap(), timeout: Duration = 120.seconds): String {
        val mcpClient = client ?: throw McpError("the MCP server was never started")
        if (dead) throw McpError("the MCP server exited (see mcp.err in the run directory)")
        val result = try {
            runBlocking { mcpClient.callTool(name, arguments, options = RequestOptions(timeout = timeout)) }
        } catch (e: Exception) {
            throw McpError("$name: ${e.message ?: e::class.simpleName}", e)
        }
        val text = result.content.filterIsInstance<TextContent>().firstOrNull()?.text.orEmpty()
        if (result.isError == true) throw McpError("$name returned an error: ${text.take(300)}")
        return text
    }

    /**
     * Stops the server: closes the client, then SIGTERMs the Gradle client and every process under
     * it, SIGKILL after 10 s. Closing the client is not enough on its own: the end of stdin does
     * not reach the server through Gradle, which keeps running. Only processes this run started
     * are signalled.
     */
    fun stop() {
        client?.let { mcpClient -> runCatching { runBlocking { withTimeoutOrNull(10.seconds) { mcpClient.close() } } } }
        val started = process ?: return
        // Taken before the parent goes: once it exits its children are reparented and out of reach.
        val below = started.descendants().toList()
        started.destroy()
        below.forEach { it.destroy() }
        if (!started.waitFor(10, TimeUnit.SECONDS)) started.destroyForcibly()
        below.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }

    companion object {
        const val MCP_TASK = ":composeApp:hotMcpServerDesktop"

        /** The server's main class (hot-reload-mcp), as its JVM's command line names it. */
        const val SERVER_MAIN_CLASS = "org.jetbrains.compose.reload.mcp.ComposeHotReloadMcp"
    }
}
