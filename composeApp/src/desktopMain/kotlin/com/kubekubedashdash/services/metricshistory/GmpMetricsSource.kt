package com.kubekubedashdash.services.metricshistory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.ConcurrentHashMap

/** Pod history from Google Managed Prometheus's query API, authorised with gcloud's access token. */
class GmpMetricsSource internal constructor(
    private val target: GmpTarget,
    private val token: GcloudAccessToken = GcloudAccessToken.shared,
    private val http: PrometheusHttp = PrometheusHttp.shared,
    private val baseUrl: String = GMP_BASE_URL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MetricsHistorySource {

    override val label = "Google Managed Prometheus"

    private val apiBase get() = "$baseUrl/v1/projects/${target.project}/location/global/prometheus/api/v1"

    private val probeMutex = Mutex()
    private var probeNoData: Boolean? = null
    private var probedAtMs = 0L

    override suspend fun podSeries(kind: PodSeriesKind, pod: PodRef, window: QueryWindow): SeriesResult {
        val form = listOf(
            "query" to PromQl.gmpPodQuery(kind, target, pod, window.stepSec),
            "start" to window.startEpochSec.toString(),
            "end" to window.endEpochSec.toString(),
            "step" to window.stepSec.toString(),
        )
        val body = when (val sent = post("$apiBase/query_range", form)) {
            is PostResult.Failed -> return sent.result
            is PostResult.Ok -> sent.body
        }
        val nameLabel = if (kind == PodSeriesKind.NetworkRx || kind == PodSeriesKind.NetworkTx) null else "container"
        return when (val parsed = PrometheusResponse.parseMatrix(body, nameLabel)) {
            is PrometheusResponse.Parsed.Matrix ->
                if (parsed.series.isEmpty() && (kind == PodSeriesKind.Cpu || kind == PodSeriesKind.Memory) && probeSaysNoData()) {
                    failed(HistoryFailure.NoContainerMetrics)
                } else {
                    SeriesResult.Ok(parsed.series)
                }

            is PrometheusResponse.Parsed.Error -> failed(HistoryFailure.BadQuery, parsed.errorType)

            is PrometheusResponse.Parsed.Scalar, is PrometheusResponse.Parsed.Malformed -> failed(HistoryFailure.Unavailable, "unreadable response")
        }
    }

    private sealed interface PostResult {
        data class Ok(val body: String) : PostResult

        data class Failed(val result: SeriesResult.Failed) : PostResult
    }

    private fun failed(failure: HistoryFailure, detail: String = ""): SeriesResult.Failed {
        log.debug("Managed Prometheus query failed: {} {}", failure, detail)
        return SeriesResult.Failed(failure, detail)
    }

    private suspend fun post(url: String, form: List<Pair<String, String>>): PostResult {
        var bearer = token.get().getOrElse { return PostResult.Failed(failed(GcloudAccessToken.failureOf(it))) }
        var retried = false
        while (true) {
            val response = try {
                http.postForm(url, form, bearer)
            } catch (e: CancellationException) {
                throw e
            } catch (_: HttpTimeoutException) {
                return PostResult.Failed(failed(HistoryFailure.Unavailable, "timeout"))
            } catch (_: IOException) {
                return PostResult.Failed(failed(HistoryFailure.Unavailable, "network error"))
            }
            val status = response.status
            when (status) {
                200 -> return PostResult.Ok(response.body)

                401 -> {
                    if (retried) return PostResult.Failed(failed(HistoryFailure.NotSignedIn, "HTTP 401"))
                    retried = true
                    bearer = token.refresh(stale = bearer).getOrElse { return PostResult.Failed(failed(GcloudAccessToken.failureOf(it))) }
                }

                403, 404 -> return PostResult.Failed(failed(HistoryFailure.Forbidden, "HTTP $status"))

                400, 422 -> return PostResult.Failed(failed(HistoryFailure.BadQuery, PrometheusResponse.errorTypeOf(response.body) ?: "HTTP $status"))

                else -> return PostResult.Failed(failed(HistoryFailure.Unavailable, "HTTP $status"))
            }
        }
    }

    // An empty CPU or memory answer means either a pod with no series yet or a cluster whose
    // cAdvisor metrics are not collected; one count per target tells them apart, and is kept
    // for PROBE_TTL_MS. Any probe failure says "unknown" (false) and is not kept.
    private suspend fun probeSaysNoData(): Boolean = probeMutex.withLock {
        val cached = probeNoData
        if (cached != null && nowMs() - probedAtMs < PROBE_TTL_MS) return@withLock cached
        val sent = post("$apiBase/query", listOf("query" to PromQl.gmpProbeQuery(target)))
        val parsed = (sent as? PostResult.Ok)?.let { PrometheusResponse.parseVectorFirstValue(it.body) }
        if (parsed !is PrometheusResponse.Parsed.Scalar) return@withLock false
        val noData = parsed.value == null || parsed.value == 0.0
        probeNoData = noData
        probedAtMs = nowMs()
        noData
    }

    companion object {
        const val GMP_BASE_URL = "https://monitoring.googleapis.com"
        private const val PROBE_TTL_MS = 5 * 60 * 1000L

        private val log = LoggerFactory.getLogger(GmpMetricsSource::class.java)

        private val byTarget = ConcurrentHashMap<GmpTarget, GmpMetricsSource>()

        fun forTarget(target: GmpTarget): GmpMetricsSource = byTarget.computeIfAbsent(target) { GmpMetricsSource(it) }
    }
}
