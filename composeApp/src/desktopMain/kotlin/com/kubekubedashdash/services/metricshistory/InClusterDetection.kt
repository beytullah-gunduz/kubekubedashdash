package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.isForbidden
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/** A Service that might be a Prometheus; [rank] is its finder's place in the search order (1 is tried first). */
internal data class PrometheusCandidate(val rank: Int, val target: InClusterTarget)

/**
 * Looks for a Prometheus API behind a Service: lists the Services the well-known deployments
 * label, then confirms candidates with a `query=1` through the service proxy. The finders run in
 * parallel under one budget, so a search never takes four timeouts in a row.
 *
 * A finder that failed for any reason but a 403 ends the whole search as [DetectionState.SearchFailed]:
 * a Prometheus that was not seen is not a Prometheus that is not there, and "nothing found" is
 * cached for minutes where a failure is retried within one. Nothing here logs a URL, a name or a
 * body; exceptions are reported by class only.
 */
internal class PrometheusDetector(
    private val finderBudgetMs: Long = FINDER_BUDGET_MS,
    private val probeTimeoutSec: Long = ServiceProxyHttp.PROBE_TIMEOUT_SEC,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Listing {
        data class Services(val items: List<Service>) : Listing

        data object Forbidden : Listing
    }

    private enum class Probe { Confirmed, Refused, Rejected, Inconclusive }

    suspend fun detect(client: KubernetesClient): DetectionState {
        val listings = try {
            withTimeoutOrNull(finderBudgetMs) { runFinders(client) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("In-cluster Prometheus search failed: {}", e.javaClass.simpleName)
            return DetectionState.SearchFailed(nowMs())
        }
        if (listings == null) {
            log.debug("In-cluster Prometheus search failed: finder budget exhausted")
            return DetectionState.SearchFailed(nowMs())
        }

        val proxy = ServiceProxyHttp(client)
        var inconclusive = false
        for (candidate in candidates(listings).take(MAX_PROBES)) {
            when (probe(proxy, candidate.target)) {
                Probe.Confirmed, Probe.Refused -> return DetectionState.Found(candidate.target)
                Probe.Rejected -> Unit
                Probe.Inconclusive -> inconclusive = true
            }
        }
        // A candidate that could not be judged (not ready, unreachable) may well be the Prometheus:
        // retry soon instead of caching "nothing found".
        return when {
            inconclusive -> DetectionState.SearchFailed(nowMs())
            listings.all { it is Listing.Forbidden } -> DetectionState.CannotSearch(nowMs())
            else -> DetectionState.NothingFound(nowMs())
        }
    }

    // Rank order: the lists of the three label finders, then Lens metrics' Service by name.
    private suspend fun runFinders(client: KubernetesClient): List<Listing> = coroutineScope {
        val labelled = LABEL_FINDERS.map { labels ->
            async { listing { client.services().inAnyNamespace().withLabels(labels).list().items.orEmpty() } }
        }
        val lens = async { listing { listOfNotNull(client.services().inNamespace(LENS_NAMESPACE).withName(LENS_SERVICE).get()) } }
        (labelled + lens).awaitAll()
    }

    // runInterruptible: the fabric8 call blocks without a suspension point, so the budget has to interrupt the thread.
    private suspend fun listing(block: () -> List<Service>): Listing = try {
        Listing.Services(runInterruptible(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isForbidden(e)) Listing.Forbidden else throw e
    }

    private suspend fun probe(proxy: ServiceProxyHttp, target: InClusterTarget): Probe {
        val response = try {
            proxy.get(target, "query", listOf("query" to "1"), probeTimeoutSec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("In-cluster Prometheus probe inconclusive: {}", e.javaClass.simpleName)
            return Probe.Inconclusive
        }
        return when {
            response.status == 200 -> if (PrometheusResponse.isQueryableProbe(response.body)) Probe.Confirmed else Probe.Rejected

            // The API server refused the user, not the Prometheus: the source reports the RBAC message.
            response.status == 403 -> Probe.Refused

            response.status >= 500 -> Probe.Inconclusive

            else -> Probe.Rejected
        }
    }

    companion object {
        const val FINDER_BUDGET_MS = 15_000L
        const val MAX_PROBES = 4
        const val LENS_NAMESPACE = "lens-metrics"
        const val LENS_SERVICE = "prometheus"

        // The governing Service of a Prometheus Operator agent: it answers 422 to queries.
        const val AGENT_SERVICE = "prometheus-agent-operated"

        private val PREFERRED_PORT_NAMES = setOf("web", "http-web", "http")

        private val LABEL_FINDERS: List<Map<String, String>> = listOf(
            mapOf("operated-prometheus" to "true"),
            mapOf("app.kubernetes.io/name" to "prometheus", "app.kubernetes.io/component" to "server"),
            mapOf("app" to "prometheus", "component" to "server"),
        )

        private val log = LoggerFactory.getLogger(PrometheusDetector::class.java)

        /** The first port named `web`, `http-web` or `http`, else the first port; its name, else its number. Null for a Service without ports. */
        internal fun choosePort(service: Service): String? {
            val ports = service.spec?.ports.orEmpty()
            val port = ports.firstOrNull { it.name in PREFERRED_PORT_NAMES } ?: ports.firstOrNull() ?: return null
            return port.name?.takeIf { it.isNotBlank() } ?: port.port?.toString()
        }

        // One candidate per (namespace, name) at its best rank, sorted by (rank, namespace, name). A Service whose
        // fields fail validation never gets near a URL.
        private fun candidates(listings: List<Listing>): List<PrometheusCandidate> {
            val best = LinkedHashMap<Pair<String, String>, PrometheusCandidate>()
            listings.forEachIndexed { index, listing ->
                val rank = index + 1
                for (service in (listing as? Listing.Services)?.items.orEmpty()) {
                    val namespace = service.metadata?.namespace ?: continue
                    val name = service.metadata?.name ?: continue
                    if (rank == 1 && name == AGENT_SERVICE) continue
                    val port = choosePort(service) ?: continue
                    val target = validInClusterTarget(namespace, name, port, https = false, pathPrefix = "") ?: continue
                    val known = best[namespace to name]
                    if (known == null || rank < known.rank) best[namespace to name] = PrometheusCandidate(rank, target)
                }
            }
            return best.values.sortedWith(compareBy({ it.rank }, { it.target.namespace }, { it.target.service }))
        }
    }
}

/**
 * What the search for an in-cluster Prometheus has found, per cluster ([com.kubekubedashdash.util.DemoContext.preferenceKey]).
 *
 * The searches run in this object's own scope: a caller that stops waiting (a closed panel) never
 * cancels one, so `Searching` cannot be left behind, and a search that ends by exception records
 * [DetectionState.SearchFailed] rather than anything cacheable.
 */
class InClusterDetection internal constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val detect: suspend (KubernetesClient) -> DetectionState = PrometheusDetector(nowMs = nowMs)::detect,
) {
    private class Flight {
        lateinit var job: Job
    }

    private val lock = Any()
    private val flights = HashMap<String, Flight>() // guarded by lock
    private val _state = MutableStateFlow<Map<String, DetectionState>>(emptyMap())

    /** The raw states, for recomposition; [current] is what decides, because it applies the clock. */
    val state: StateFlow<Map<String, DetectionState>> = _state.asStateFlow()

    /**
     * The state of [key] with the clock applied: "nothing found" and "cannot search" older than
     * [NOT_FOUND_TTL_MS] and a failure older than [FAILED_TTL_MS] count as no state at all. A
     * Service that stopped existing invalidates a `Found`, so that one has no age limit.
     */
    fun current(key: String): DetectionState? {
        val known = _state.value[key] ?: return null
        return when (known) {
            DetectionState.Searching, is DetectionState.Found -> known
            is DetectionState.NothingFound -> known.takeIf { nowMs() - it.atMs <= NOT_FOUND_TTL_MS }
            is DetectionState.CannotSearch -> known.takeIf { nowMs() - it.atMs <= NOT_FOUND_TTL_MS }
            is DetectionState.SearchFailed -> known.takeIf { nowMs() - it.atMs <= FAILED_TTL_MS }
        }
    }

    /**
     * Makes sure a search for [key] has run or is running, and waits for it. Nothing happens
     * when [current] already holds an answer. Concurrent callers share one search. Cancelling the
     * caller stops its waiting only. [clientProvider] runs inside the search; null means not connected.
     */
    suspend fun ensure(key: String, clientProvider: suspend () -> KubernetesClient?) {
        val flight = synchronized(lock) {
            val known = current(key)
            if (known != null && known !is DetectionState.Searching) return
            flights[key] ?: start(key, clientProvider)
        }
        flight.job.join()
    }

    /**
     * Forgets [key]'s state and abandons its search; the next [ensure] starts another. An
     * abandoned search never writes its result.
     */
    fun invalidate(key: String) {
        val abandoned = synchronized(lock) {
            _state.update { it - key }
            flights.remove(key)
        }
        abandoned?.job?.cancel()
    }

    // Called under lock.
    private fun start(key: String, clientProvider: suspend () -> KubernetesClient?): Flight {
        val flight = Flight()
        flights[key] = flight
        _state.update { it + (key to DetectionState.Searching) }
        val job = scope.async(start = CoroutineStart.LAZY) {
            val result = try {
                clientProvider()?.let { detect(it) } ?: DetectionState.SearchFailed(nowMs())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("In-cluster Prometheus search failed: {}", e.javaClass.simpleName)
                DetectionState.SearchFailed(nowMs())
            }
            finish(key, flight, result)
        }
        // Covers a search that never got to run or died by an Error: Searching must not outlive its job.
        job.invokeOnCompletion { finish(key, flight, DetectionState.SearchFailed(nowMs())) }
        flight.job = job
        job.start()
        return flight
    }

    // Writes only for the search that is still the registered one, so an abandoned search cannot
    // overwrite a newer answer, and a finished one cannot be written twice.
    private fun finish(key: String, flight: Flight, result: DetectionState) {
        synchronized(lock) {
            if (flights[key] !== flight) return
            flights.remove(key)
            _state.update { it + (key to result) }
        }
        log.debug("In-cluster Prometheus search finished: {}", result::class.simpleName)
    }

    companion object {
        const val NOT_FOUND_TTL_MS = 10 * 60 * 1000L
        const val FAILED_TTL_MS = 60 * 1000L

        private val log = LoggerFactory.getLogger(InClusterDetection::class.java)

        val shared: InClusterDetection by lazy { InClusterDetection() }
    }
}
