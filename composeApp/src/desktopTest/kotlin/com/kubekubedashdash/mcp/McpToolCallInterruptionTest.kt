package com.kubekubedashdash.mcp

import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InterruptedIOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [McpServerManager.toolCall] is the one place a tool's outcome is shaped
 * (review follow-up F9). A failure becomes an `isError` result with the API
 * server's sentence when fabric8 carried one. An interrupted blocking call —
 * `blockingCall` interrupts its worker when the request is cancelled, and
 * fabric8 launders the InterruptedException into a KubernetesClientException
 * that `runInterruptible` passes through unchanged — must surface as a
 * cancellation, never as an error result or an ERROR log with a useless
 * message. No sockets, no real user state.
 */
class McpToolCallInterruptionTest {

    private fun ok() = CallToolResult(content = listOf(TextContent(text = "ok")))

    private fun errorOf(result: CallToolResult): String {
        assertEquals(true, result.isError)
        return Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject.getValue("error").jsonPrimitive.content
    }

    @Test
    fun `a successful body is returned as is`() = runBlocking {
        val result = McpServerManager.toolCall("t") { ok() }

        assertNull(result.isError)
        assertEquals("ok", (result.content.single() as TextContent).text)
    }

    @Test
    fun `a fabric8 failure becomes an error result carrying the API's message, not the request URL`() = runBlocking {
        val status = StatusBuilder().withCode(403).withMessage("""pods "p" is forbidden""").build()
        val failure = KubernetesClientException("Failure executing: GET at: http://127.0.0.1:1/api/v1/namespaces/ns/pods/p/log. Message: forbidden.", 403, status)

        val result = McpServerManager.toolCall("t") { throw failure }

        assertEquals("""pods "p" is forbidden""", errorOf(result))
    }

    @Test
    fun `any other failure becomes an error result carrying its message`() = runBlocking {
        val result = McpServerManager.toolCall("t") { throw IllegalStateException("mapping failed") }

        assertEquals("mapping failed", errorOf(result))
    }

    @Test
    fun `an interrupted blocking call surfaces as cancellation, within the grace period`() = runBlocking<Unit> {
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                McpServerManager.toolCall("t") {
                    McpServerManager.blockingCall {
                        try {
                            Thread.sleep(10_000)
                        } catch (e: InterruptedException) {
                            // fabric8's shape: flag restored, exception laundered.
                            Thread.currentThread().interrupt()
                            throw KubernetesClientException("An error has occurred.", InterruptedIOException("interrupted").initCause(e))
                        }
                    }
                    ok()
                }
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
            }
        }
        delay(200)
        job.cancel()

        val thrown = withTimeout(2_000) { outcome.await() }
        assertIs<CancellationException>(thrown, "an interrupted call is a cancellation, not an error result and not the laundered exception")
    }

    @Test
    fun `a cancellation thrown on a live job is rethrown as is, never turned into an error result`() = runBlocking<Unit> {
        val boom = CancellationException("inner timeout")

        val thrown = runCatching { McpServerManager.toolCall("t") { throw boom } }.exceptionOrNull()

        assertSame(boom, thrown, "toolCall must rethrow a cancellation untouched")
    }

    @Test
    fun `a cancelled job whose call fails for another reason still surfaces as cancellation, not an error result`() = runBlocking<Unit> {
        // No interruption in the chain and no restored flag: only the cancelled
        // job itself (ensureActive) can tell this apart from a plain failure.
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                McpServerManager.toolCall("t") {
                    McpServerManager.blockingCall {
                        try {
                            Thread.sleep(10_000)
                        } catch (_: InterruptedException) {
                            throw IllegalStateException("post-cancel failure")
                        }
                    }
                    ok()
                }
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
            }
        }
        delay(200)
        job.cancel()

        val thrown = withTimeout(2_000) { outcome.await() }
        assertIs<CancellationException>(thrown, "a dead job must not receive an error result; got ${thrown?.let { it::class.simpleName } ?: "a normal return"}")
    }

    @Test
    fun `an interrupted body on a live job is still a cancellation, not an error result`() = runBlocking {
        val laundered = KubernetesClientException("An error has occurred.", InterruptedIOException("interrupted").initCause(InterruptedException()))

        val thrown = runCatching { McpServerManager.toolCall("t") { throw laundered } }.exceptionOrNull()

        assertIs<CancellationException>(thrown)
        assertEquals(laundered, thrown.cause, "the laundered exception is kept as the cause")
    }
}
