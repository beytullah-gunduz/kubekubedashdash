package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.KubernetesClientException

/**
 * True when a metrics.k8s.io read failed because metrics are not to be had
 * here rather than for a moment: 404 (no metrics-server registered the API
 * group), 503 (registered, but the aggregator cannot reach it) or an RBAC
 * refusal ([isForbidden]). Anything else — a timeout, a 500, a dropped
 * connection — is transient, and a poll keeps its last sample through it.
 */
internal fun isMetricsUnavailable(e: Throwable): Boolean = isForbidden(e) ||
    generateSequence(e) { it.cause }.take(16).any {
        when ((it as? KubernetesClientException)?.code) {
            404, 503 -> true
            else -> false
        }
    }

/**
 * A failure's shape without its text — `HTTP 500`, or the root cause's class
 * for a failure that never got an answer — for logs: fabric8's message names
 * the request URL, which holds the server and the namespace.
 */
internal fun describeFailureShape(e: Throwable): String {
    val code = generateSequence(e) { it.cause }.take(16).firstNotNullOfOrNull { (it as? KubernetesClientException)?.code?.takeIf { c -> c > 0 } }
    return if (code != null) "HTTP $code" else generateSequence(e) { it.cause }.take(16).last().javaClass.simpleName
}

/**
 * A crash's stack without its text, for app.log: each throwable's class and its frames (class,
 * method, file, line), never a message, [Throwable.localizedMessage] or [Throwable.toString] (fabric8's
 * message names the request URL). Frames are built by hand because [StackTraceElement.toString] can add
 * module and class loader names. Capped at 16 throwables in the chain and 40 frames each, the rest counted.
 */
internal fun describeStack(e: Throwable): String = buildString {
    generateSequence(e) { it.cause }.take(MAX_STACK_CAUSES).forEachIndexed { index, throwable ->
        if (index > 0) append("\nCaused by: ")
        append(throwable.javaClass.name)
        val frames = throwable.stackTrace
        for (frame in frames.take(MAX_STACK_FRAMES)) {
            append("\n\tat ").append(frame.className).append('.').append(frame.methodName).append('(')
            append(frame.fileName ?: "Unknown Source")
            if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
            append(')')
        }
        if (frames.size > MAX_STACK_FRAMES) append("\n\t... ").append(frames.size - MAX_STACK_FRAMES).append(" more")
    }
}

private const val MAX_STACK_CAUSES = 16
private const val MAX_STACK_FRAMES = 40
