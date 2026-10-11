package com.kubekubedashdash.services.metricshistory

import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pod history from a Prometheus-compatible API behind a Service, reached through the API server's
 * service proxy with the tab's own credentials.
 *
 * [clientProvider] hands out the tab's client for the cluster this source is bound to (null: not
 * connected). It is asked on every query, and the transport is built anew when the client is a
 * different instance, because a reconnect closes the old one. [auto] says the target came from
 * detection, so a vanished Service sends the cluster's search back to the start. [accessReview]
 * tells a role that cannot use services/proxy from a Prometheus that refused (null: unknown).
 *
 * Details carried by a failure are the fixed phrases below, an HTTP status or a Prometheus
 * errorType; never a URL, a query, a name or a response body.
 */
class InClusterMetricsSource internal constructor(
    private val target: InClusterTarget,
    private val auto: Boolean,
    private val clientProvider: suspend () -> KubernetesClient?,
    private val detection: InClusterDetection,
    private val key: String,
    private val accessReview: suspend (namespace: String) -> Boolean?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val queryTimeoutSec: Long = ServiceProxyHttp.QUERY_TIMEOUT_SEC,
) : MetricsHistorySource {

    override val label = "Prometheus · ${target.namespace}/${target.service}"

    private class Bound(val client: KubernetesClient, val http: ServiceProxyHttp)

    private val boundLock = Any()
    private var bound: Bound? = null // guarded by boundLock

    // When this source last sent the cluster's search back to the start; see send's 404.
    @Volatile
    private var invalidatedAtMs: Long? = null

    private val probeMutex = Mutex()
    private var probeNoData: Boolean? = null
    private var probedAtMs = 0L

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult {
        val params = listOf(
            "query" to PromQl.inClusterPodQuery(kind, pod, window.stepSec),
            "start" to window.startEpochSec.toString(),
            "end" to window.endEpochSec.toString(),
            "step" to window.stepSec.toString(),
        )
        val body = when (val sent = send("query_range", params)) {
            is Sent.Failed -> return sent.result
            is Sent.Ok -> sent.body
        }
        val nameLabel = if (kind == PodSeriesKind.NetworkRx || kind == PodSeriesKind.NetworkTx) null else "container"
        return when (val parsed = PrometheusResponse.parseMatrix(body, nameLabel)) {
            is PrometheusResponse.Parsed.Matrix ->
                if (parsed.series.isEmpty() && (kind == PodSeriesKind.Cpu || kind == PodSeriesKind.Memory) && probeSaysNoData()) {
                    failed(HistoryFailure.NoContainerMetrics)
                } else {
                    SeriesResult.Ok(parsed.series)
                }

            is PrometheusResponse.Parsed.Error -> failed(HistoryFailure.BadQuery, safeErrorType(parsed.errorType) ?: "unknown")

            is PrometheusResponse.Parsed.Scalar, is PrometheusResponse.Parsed.Malformed -> failed(HistoryFailure.Unavailable, "unreadable response")
        }
    }

    private sealed interface Sent {
        data class Ok(val body: String) : Sent

        data class Failed(val result: SeriesResult.Failed) : Sent
    }

    private fun failed(failure: HistoryFailure, detail: String = ""): SeriesResult.Failed {
        log.debug("In-cluster Prometheus query failed: {} {}", failure, detail)
        return SeriesResult.Failed(failure, detail)
    }

    private fun failedSent(failure: HistoryFailure, detail: String = ""): Sent.Failed = Sent.Failed(failed(failure, detail))

    // The transport for the client the tab holds at the time of the call; a different instance means the old one was closed.
    private suspend fun transport(): ServiceProxyHttp? {
        val client = clientProvider() ?: return null
        return synchronized(boundLock) {
            bound?.takeIf { it.client === client }?.http
                ?: Bound(client, ServiceProxyHttp(client)).also { bound = it }.http
        }
    }

    private suspend fun send(apiPath: String, params: List<Pair<String, String>>): Sent {
        val response = try {
            val http = transport() ?: return failedSent(HistoryFailure.Unavailable, "not connected")
            http.get(target, apiPath, params, queryTimeoutSec)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TimeoutException) {
            return failedSent(HistoryFailure.Unavailable, "timeout")
        } catch (_: HttpTimeoutException) {
            return failedSent(HistoryFailure.Unavailable, "timeout")
        } catch (_: SocketTimeoutException) {
            return failedSent(HistoryFailure.Unavailable, "timeout")
        } catch (_: IOException) {
            return failedSent(HistoryFailure.Unavailable, "network error")
        } catch (_: IllegalArgumentException) {
            // A malformed URL or header is reported with the offending value in the message: dropped, never logged.
            return failedSent(HistoryFailure.Unavailable, "bad request")
        } catch (_: Exception) {
            // Vert.x failures after a close are RuntimeExceptions, not IOExceptions.
            return failedSent(HistoryFailure.Unavailable, "error")
        }
        val status = response.status
        return when (status) {
            200 -> Sent.Ok(response.body)

            400, 422 -> failedSent(HistoryFailure.BadQuery, safeErrorType(PrometheusResponse.errorTypeOf(response.body)) ?: "HTTP $status")

            // fabric8 has already refreshed the token and retried once by the time a 401 gets here.
            401 -> failedSent(HistoryFailure.Forbidden, "HTTP 401")

            403 -> proxyForbidden()

            404 -> if (isKubernetesStatus(response.body)) {
                // The API server itself does not know the Service. A detected target is stale then.
                if (auto) invalidateDetection()
                failedSent(HistoryFailure.ServiceGone)
            } else {
                failedSent(HistoryFailure.WrongPath)
            }

            503 -> failedSent(HistoryFailure.Unavailable, if (isKubernetesStatus(response.body)) "no ready endpoints" else "HTTP 503")

            else -> failedSent(HistoryFailure.Unavailable, "HTTP $status")
        }
    }

    // At most once per FAILED_TTL_MS: a front end that answers this 404 to range queries only would
    // otherwise send the panel from a fresh search straight back to this answer in a tight loop.
    private fun invalidateDetection() {
        val now = nowMs()
        val last = invalidatedAtMs
        if (last != null && now - last < InClusterDetection.FAILED_TTL_MS) return
        invalidatedAtMs = now
        detection.invalidate(key)
    }

    private suspend fun proxyForbidden(): Sent.Failed {
        val allowed = try {
            accessReview(target.namespace)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return if (allowed == false) failedSent(HistoryFailure.NoProxyAccess) else failedSent(HistoryFailure.Forbidden, "HTTP 403")
    }

    // An empty CPU or memory answer means either a pod with no series yet or a Prometheus that does
    // not scrape cAdvisor; one count tells them apart and is kept for PROBE_TTL_MS. Any probe
    // failure says "unknown" (false) and is not kept.
    private suspend fun probeSaysNoData(): Boolean = probeMutex.withLock {
        val cached = probeNoData
        if (cached != null && nowMs() - probedAtMs < PROBE_TTL_MS) return@withLock cached
        val sent = send("query", listOf("query" to PromQl.inClusterProbeQuery()))
        val parsed = (sent as? Sent.Ok)?.let { PrometheusResponse.parseVectorFirstValue(it.body) }
        if (parsed !is PrometheusResponse.Parsed.Scalar) return@withLock false
        val noData = parsed.value == null || parsed.value == 0.0
        probeNoData = noData
        probedAtMs = nowMs()
        noData
    }

    companion object {
        private const val PROBE_TTL_MS = 5 * 60 * 1000L

        // Prometheus' errorType values are short snake_case words; anything else came from something that is not Prometheus.
        private val SAFE_ERROR_TYPE = Regex("[A-Za-z_]{1,32}")

        private val log = LoggerFactory.getLogger(InClusterMetricsSource::class.java)

        private fun safeErrorType(errorType: String?): String? = errorType?.takeIf { SAFE_ERROR_TYPE.matches(it) }

        // The API server's own answers (a missing Service, no endpoints) are Status objects; Prometheus', a proxy's
        // or a web server's are not. A malformed body is not one.
        internal fun isKubernetesStatus(body: String): Boolean = try {
            Json.parseToJsonElement(body).jsonObject["kind"]?.jsonPrimitive?.contentOrNull == "Status"
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
