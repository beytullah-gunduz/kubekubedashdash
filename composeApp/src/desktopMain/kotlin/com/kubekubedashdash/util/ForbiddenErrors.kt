package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.KubernetesClientException

/**
 * True when [message] reads as an RBAC refusal (HTTP 403): the Status fabric8
 * embeds in a KubernetesClientException's text (`code=403`, `reason=Forbidden`)
 * or the API server's own wording (`pods is forbidden: User … cannot list …`).
 * Works on the text a `ResourceState.Error` carries, where the exception is gone.
 */
fun isForbidden(message: String?): Boolean = message != null && ("code=403" in message || "reason=Forbidden" in message || " is forbidden" in message)

/** True when [e] or one of its causes is a 403, by status code or by [isForbidden]'s reading of its message. */
fun isForbidden(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { (it as? KubernetesClientException)?.code == 403 || isForbidden(it.message) }
