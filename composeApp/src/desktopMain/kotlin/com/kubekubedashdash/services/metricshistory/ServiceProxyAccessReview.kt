package com.kubekubedashdash.services.metricshistory

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Asks the API server whether the user may GET `services/proxy` in a namespace (a
 * SelfSubjectAccessReview). Only asked after the proxy answered 403, to tell "your role cannot
 * reach Prometheus through the API server" from "Prometheus refused the request".
 *
 * Definite answers are kept per (cluster key, namespace) for [TTL_MS]; a failed review says
 * nothing and is asked again.
 */
internal class ServiceProxyAccessReview(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = REVIEW_TIMEOUT_MS,
) {
    private data class Entry(val allowed: Boolean, val atMs: Long)

    // One review at a time: two panels asking for the same namespace post once.
    private val mutex = Mutex()
    private val cache = HashMap<Pair<String, String>, Entry>() // guarded by mutex

    /** True or false when the API server said so; null when it could not be asked. */
    suspend fun allowed(key: String, namespace: String, clientProvider: suspend () -> KubernetesClient?): Boolean? = mutex.withLock {
        val cached = cache[key to namespace]
        if (cached != null && nowMs() - cached.atMs < TTL_MS) return@withLock cached.allowed
        val answer = ask(clientProvider, namespace)
        if (answer != null) cache[key to namespace] = Entry(answer, nowMs())
        answer
    }

    private suspend fun ask(clientProvider: suspend () -> KubernetesClient?, namespace: String): Boolean? {
        try {
            val client = clientProvider() ?: return null
            val review = SelfSubjectAccessReviewBuilder()
                .withNewSpec().withNewResourceAttributes()
                .withNamespace(namespace).withVerb("get").withResource("services").withSubresource("proxy")
                .endResourceAttributes().endSpec().build()
            return withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) { client.authorization().v1().selfSubjectAccessReview().create(review)?.status?.allowed }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val REVIEW_TIMEOUT_MS = 10_000L
    }
}
