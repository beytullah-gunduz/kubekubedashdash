package com.kubekubedashdash.services.metricshistory

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.RequestConfigBuilder
import io.fabric8.kubernetes.client.http.HttpClient
import io.fabric8.kubernetes.client.http.HttpRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * GETs a Prometheus API through the Kubernetes API server's service proxy, with the tab's own
 * credentials. Built over fabric8's HTTP client rather than `raw()`, which returns only a body
 * and throws on most non-2xx codes.
 *
 * Bound to one [client] instance: closing that client (a reconnect, a closed tab) fails every
 * later call, so callers build a new one for the new client. An exception's message can name
 * the host, so callers report its class and never its message.
 */
internal class ServiceProxyHttp(private val client: KubernetesClient) {
    data class Response(val status: Int, val body: String)

    // Derived once. The tag zeroes fabric8's retry budget, which would otherwise repeat a proxy
    // 503 (a Prometheus with no ready endpoint) for seconds. Never closed: the derived client
    // shares the parent's connection pool and closed flag, so closing it closes the tab's client.
    private val http: HttpClient by lazy {
        client.httpClient.newBuilder()
            .tag(RequestConfigBuilder(client.configuration.requestConfig).withRequestRetryBackoffLimit(0).build())
            .build()
    }

    /**
     * One GET of `/api/v1/[apiPath]` on [target] with [params] form-encoded. A non-2xx answer is
     * a [Response], not an exception. Transport failures throw as fabric8 reports them:
     * IOException, TimeoutException, or Vert.x's IllegalStateException ("Client is closed") once the
     * client is closed.
     * Cancelling the caller returns at once and discards a response that lands later.
     */
    suspend fun get(target: InClusterTarget, apiPath: String, params: List<Pair<String, String>>, timeoutSec: Long): Response {
        val url = proxyUrl(client.masterUrl.toString(), target, apiPath, params)
        val request = http.newHttpRequestBuilder()
            .uri(url)
            .header("Accept", "application/json")
            .timeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        // fabric8 pads its own timeout by up to 5 s and the request timeout only fires on idle,
        // so the wait is bounded here too. orTimeout fails the future with a plain
        // TimeoutException; a coroutine timeout would be mistaken for the caller's own.
        val future = http.sendAsync(request, String::class.java).orTimeout(timeoutSec + AWAIT_GRACE_SEC, TimeUnit.SECONDS)
        // await() hands back the future's own failure (it unwraps CompletionException).
        val response = try {
            future.await()
        } catch (e: CancellationException) {
            // The caller's cancellation propagates as it is; any other means the future was
            // cancelled from outside, which is a failed request, not a cancelled caller.
            currentCoroutineContext().ensureActive()
            throw IOException("request cancelled", e)
        }
        return Response(response.code(), response.body().orEmpty())
    }

    companion object {
        /** The same bound as the GMP client's. */
        const val QUERY_TIMEOUT_SEC = 15L

        /** The detection probe only has to tell a live Prometheus from a dead end. */
        const val PROBE_TIMEOUT_SEC = 5L

        private const val AWAIT_GRACE_SEC = 2L
        private val API_PATHS = setOf("query", "query_range")

        /**
         * `<master>/api/v1/namespaces/<ns>/services/[https:]<service>:<port>/proxy<prefix>/api/v1/<apiPath>?<params>`.
         * The master URL keeps its own path, as Rancher-style servers have one. [target] is
         * validated again here, so a hand-built one cannot put free text into the URL. The
         * exceptions never carry a value.
         */
        internal fun proxyUrl(masterUrl: String, target: InClusterTarget, apiPath: String, params: List<Pair<String, String>>): String {
            require(apiPath in API_PATHS) { "unsupported API path" }
            val valid = validInClusterTarget(target.namespace, target.service, target.port, target.https, target.pathPrefix)
            require(valid == target) { "invalid in-cluster target" }
            val scheme = if (target.https) "https:" else ""
            val query = params.joinToString("&") { (name, value) -> "${HttpRequest.formURLEncode(name)}=${HttpRequest.formURLEncode(value)}" }
            return masterUrl.trimEnd('/') +
                "/api/v1/namespaces/${target.namespace}/services/$scheme${target.service}:${target.port}" +
                "/proxy${target.pathPrefix}/api/v1/$apiPath" +
                if (query.isEmpty()) "" else "?$query"
        }
    }
}
