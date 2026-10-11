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
