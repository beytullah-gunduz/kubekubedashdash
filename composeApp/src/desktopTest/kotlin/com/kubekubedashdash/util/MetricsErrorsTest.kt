package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which failed metrics reads mean "no metrics here" and which are a blip the
 * polls ride out, and what of a failure reaches the log. The message below
 * stands in for fabric8's, which names the request URL.
 */
class MetricsErrorsTest {

    private val url = "Failure executing: GET at: https://cluster.invalid/apis/metrics.k8s.io/v1beta1/namespaces/ns-a/pods."

    private fun answered(code: Int) = KubernetesClientException(url, code, StatusBuilder().withCode(code).build())

    @Test
    fun `a missing, unreachable or refused metrics API is unavailable`() {
        for (code in listOf(403, 404, 503)) {
            assertTrue(isMetricsUnavailable(answered(code)), "HTTP $code")
            // fabric8 rethrows the failure as the cause of a copy.
            assertTrue(isMetricsUnavailable(KubernetesClientException("wrapped", answered(code))), "HTTP $code as a cause")
        }
    }

    @Test
    fun `a server error or a request that got no answer is transient`() {
        for (code in listOf(401, 429, 500, 504)) assertFalse(isMetricsUnavailable(answered(code)), "HTTP $code")
        assertFalse(isMetricsUnavailable(KubernetesClientException(url, SocketTimeoutException("timed out"))))
    }

    @Test
    fun `the logged shape is a status code or an exception class, never the message`() {
        assertEquals("HTTP 500", describeFailureShape(KubernetesClientException("wrapped", answered(500))))
        assertEquals("SocketTimeoutException", describeFailureShape(KubernetesClientException(url, SocketTimeoutException(url))))
    }

    @Test
    fun `the logged stack names the classes and frames of the chain, never a message`() {
        val failure = Leaky("message-with-team-a", RuntimeException("inner-team-b"))

        val stack = describeStack(failure)

        assertTrue(stack.startsWith("${Leaky::class.java.name}\n\tat "), stack)
        assertTrue("\nCaused by: java.lang.RuntimeException\n\tat " in stack, stack)
        assertTrue(Regex("""\tat com\.kubekubedashdash\.util\.MetricsErrorsTest\.[^(\n]+\(MetricsErrorsTest\.kt:\d+\)""").containsMatchIn(stack), stack)
        for (text in listOf("message-with-team-a", "inner-team-b", "localized-team-a", "to-string-team-a")) {
            assertFalse(text in stack, "the stack must not carry $text")
        }
    }

    @Test
    fun `a frame is built as class, method, file and line without module or loader names`() {
        val failure = IllegalStateException()
        failure.stackTrace = arrayOf(
            StackTraceElement("app-loader", "app.module", "1.0", "a.B", "run", "B.kt", 7),
            StackTraceElement("a.C", "native", null, -2),
            StackTraceElement("a.D", "gen", "D.kt", -1),
        )

        assertEquals(
            "java.lang.IllegalStateException\n\tat a.B.run(B.kt:7)\n\tat a.C.native(Unknown Source)\n\tat a.D.gen(D.kt)",
            describeStack(failure),
        )
    }

    @Test
    fun `a deep stack stops at 40 frames and counts the rest`() {
        val failure = runCatching { recurse(100) }.exceptionOrNull()!!
        val total = failure.stackTrace.size
        assertTrue(total > 100, "the probe must be deeper than the cap")

        val lines = describeStack(failure).lines()

        assertEquals(40, lines.count { it.startsWith("\tat ") })
        assertEquals("\t... ${total - 40} more", lines.last())
        assertEquals(1, lines.count { it.endsWith(" more") })
    }

    @Test
    fun `a short stack has no more-line`() {
        val failure = IllegalStateException()
        failure.stackTrace = Array(40) { StackTraceElement("a.B", "m$it", "B.kt", it) }

        assertFalse("more" in describeStack(failure))
    }

    @Test
    fun `a long cause chain stops at 16 throwables`() {
        var failure: Throwable = IllegalStateException("level-0")
        repeat(19) { failure = RuntimeException("level-${it + 1}", failure) }

        val stack = describeStack(failure)

        assertEquals(15, stack.lines().count { it.startsWith("Caused by: ") })
        assertFalse("level-" in stack)
    }

    @Test
    fun `a cause cycle ends`() {
        val first = IllegalStateException()
        val second = RuntimeException(first)
        first.initCause(second)

        assertEquals(15, describeStack(first).lines().count { it.startsWith("Caused by: ") })
    }

    private fun recurse(depth: Int): Nothing = if (depth == 0) throw IllegalStateException("bottom") else recurse(depth - 1)

    /** Carries text in every channel a log could print: the message, the localized message and `toString`. */
    private class Leaky(message: String, cause: Throwable) : RuntimeException(message, cause) {
        override fun getLocalizedMessage(): String = "localized-team-a"

        override fun toString(): String = "to-string-team-a"
    }
}
