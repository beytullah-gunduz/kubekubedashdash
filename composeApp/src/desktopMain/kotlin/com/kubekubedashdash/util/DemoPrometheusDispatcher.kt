package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.demoSeries
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetList
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsList
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.CustomResourceAware
import io.fabric8.kubernetes.client.server.mock.Resetable
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.mockwebserver.dsl.HttpMethod
import io.fabric8.mockwebserver.http.Buffer
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.Headers
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

/**
 * The demo cluster's fake Prometheus, served on the mock API server's service-proxy paths so the
 * demo reaches it through the same HTTP code as a real cluster: detection, the proxy URL, the
 * status mapping. Everything that is not a proxy path or an access review goes to [delegate].
 *
 * A proxy path is never forwarded, whatever it names: the CRUD store answers an unknown path with
 * 200 and its whole contents. Services and ports the demo does not have answer as the API server
 * does (404 and 503 Status objects). The only calls into [delegate] on a proxy path are synthetic
 * GETs that read what [demoSeries] needs: a pod's containers, or for a pod-set query the
 * namespace's ReplicaSets and pods. A scope query reads the PodMetrics the Overview gauges add up.
 *
 * Nothing here logs; a path, query or name stays inside the reply to the in-process client.
 */
internal class DemoPrometheusDispatcher(private val delegate: Dispatcher) :
    Dispatcher(),
    Resetable,
    CustomResourceAware {

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val bare = path.substringBefore('?')
        if (request.method() == HttpMethod.POST && bare == ACCESS_REVIEW_PATH) return accessReview(request)
        if (isProxyPath(bare)) return proxy(request.method(), bare, path.substringAfter('?', ""))
        return delegate.dispatch(request)
    }

    override fun shutdown() = delegate.shutdown()

    override fun reset() {
        (delegate as? Resetable)?.reset()
    }

    override fun expectCustomResource(rdc: CustomResourceDefinitionContext) {
        (delegate as? CustomResourceAware)?.expectCustomResource(rdc)
    }

    // ── service proxy ───────────────────────────────────────────────────────

    private fun proxy(method: HttpMethod, bare: String, rawQuery: String): MockResponse {
        val match = SERVICE_PROXY.matchEntire(bare) ?: return notFoundRoute()
        val (namespace, serviceRef, rest) = match.destructured
        // [scheme:]name[:port], as the API server splits it.
        val parts = serviceRef.split(':')
        val (name, port) = when (parts.size) {
            1 -> parts[0] to ""
            2 -> parts[0] to parts[1]
            3 -> parts[1] to parts[2]
            else -> return notFound("services", serviceRef)
        }
        if (namespace != MONITORING || name !in SERVED_SERVICES) return notFound("services", name)
        val agent = name == AGENT_SERVICE
        // The agent answers on any port: it refuses the query, whatever the client asked.
        if (!agent && port != "" && port != WEB_PORT_NAME && port != WEB_PORT_NUMBER) return noEndpoints(name)
        if (method != HttpMethod.GET) return notFoundRoute()
        return when (rest) {
            "/api/v1/query" -> if (agent) agentRefusal() else instantQuery(rawQuery)

            "/api/v1/query_range" -> if (agent) agentRefusal() else rangeQuery(rawQuery)

            // A Prometheus that does not know the path answers a plain 404, which is not the API server's own.
            else -> plain404()
        }
    }

    private fun instantQuery(rawQuery: String): MockResponse {
        val query = queryParams(rawQuery)["query"] ?: return badData("missing query")
        return when (query) {
            "1" -> success(
                buildJsonObject {
                    put("resultType", "scalar")
                    putJsonArray("result") {
                        add(JsonPrimitive(nowSec()))
                        add(JsonPrimitive("1"))
                    }
                },
            )

            PromQl.inClusterProbeQuery() -> success(
                buildJsonObject {
                    put("resultType", "vector")
                    putJsonArray("result") {
                        add(
                            buildJsonObject {
                                putJsonObject("metric") {}
                                putJsonArray("value") {
                                    add(JsonPrimitive(nowSec()))
                                    add(JsonPrimitive(DEMO_SERIES_COUNT.toString()))
                                }
                            },
                        )
                    }
                },
            )

            else -> badData("unsupported query")
        }
    }

    private fun rangeQuery(rawQuery: String): MockResponse {
        val params = queryParams(rawQuery)
        val query = params["query"] ?: return badData("missing query")
        val start = params["start"]?.toLongOrNull() ?: return badData("invalid start")
        val end = params["end"]?.toLongOrNull() ?: return badData("invalid end")
        val step = params["step"]?.toLongOrNull()?.takeIf { it > 0 } ?: return badData("invalid step")
        if (start < 0 || end < start) return badData("invalid time range")
        if ((end - start) / step > MAX_POINTS) return badData("too many points")

        val window = QueryWindow(start, end, step)
        POD_SET_SELECTOR.find(query)?.let { return podSetMatrix(query, it, window) }
        scopeOf(query, step)?.let { (kind, scope) -> return scopeMatrix(kind, scope, window) }

        val selector = POD_SELECTOR.find(query) ?: return badData("unsupported query")
        val (namespace, pod) = selector.destructured
        val kind = PodSeriesKind.entries.firstOrNull { PromQl.inClusterPodQuery(it, PodRef(namespace, pod), step) == query }
            ?: return badData("unsupported query")

        val containers = containersOf(namespace, pod)
        val series = if (containers == null) {
            emptyList()
        } else {
            demoSeries(kind, PodRef(namespace, pod, containers), window)
        }
        val perContainer = kind == PodSeriesKind.Cpu || kind == PodSeriesKind.Memory || kind == PodSeriesKind.Throttling
        return success(
            buildJsonObject {
                put("resultType", "matrix")
                putJsonArray("result") {
                    for (s in series) {
                        add(
                            buildJsonObject {
                                putJsonObject("metric") { if (perContainer) put("container", s.name) }
                                put("values", samples(s.points))
                            },
                        )
                    }
                }
            },
        )
    }

    // The pod's containers from the CRUD store, or null when the pod is not there. A name that
    // is not a Kubernetes name never reaches the store's path matching.
    private fun containersOf(namespace: String, pod: String): List<ContainerResources>? {
        if (!DNS_SUBDOMAIN.matches(namespace) || !DNS_SUBDOMAIN.matches(pod)) return null
        val lookup = RecordedRequest("HTTP/1.1", HttpMethod.GET, "/api/v1/namespaces/$namespace/pods/$pod", Headers.builder().build(), Buffer())
        val answer = delegate.dispatch(lookup)
        if (answer.code() != 200) return null
        return try {
            val json = String(answer.body?.bytes ?: return null, StandardCharsets.UTF_8)
            Serialization.unmarshal(json, Pod::class.java)?.let { ResourceMappers.containerResources(it) }
        } catch (_: RuntimeException) {
            null
        }
    }

    // ── pod sets ────────────────────────────────────────────────────────────

    /** A pod a pod-set answer draws; [fromSec] and [toSec] bound its lifetime, null where unknown. */
    private data class DemoPod(val name: String, val containers: List<ContainerResources>, val fromSec: Long?, val toSec: Long?)

    private class ListedReplicaSet(val name: String, val prefix: String, val createdSec: Long?, val containers: List<ContainerResources>)

    // The pods of the ReplicaSets the query names, in the form PromQl.inClusterPodSetQuery writes. Anything
    // else is refused, and a bad name is refused before the delegate is asked anything.
    private fun podSetMatrix(query: String, selector: MatchResult, window: QueryWindow): MockResponse {
        val (namespace, alternation) = selector.destructured
        if (!KubernetesNames.isDnsLabel(namespace)) return badData("unsupported query")
        val prefixes = alternation.split('|').map { decodePrefix(it) ?: return badData("unsupported query") }
        val inNamespace = lazy(LazyThreadSafetyMode.NONE) { replicaSetsIn(namespace) }
        val names = prefixes.map { prefix ->
            // A prefix that ends in "-" is the whole name plus that dash. One cut short by the 58-character limit is found among the listed names.
            val name = if (prefix.endsWith('-')) {
                prefix.dropLast(1)
            } else {
                inNamespace.value.mapNotNull { it.metadata?.name }.singleOrNull { PromQl.podPrefix(it) == prefix }
            }
            name?.takeIf(KubernetesNames::isDnsSubdomain) ?: return badData("unsupported query")
        }
        val ref = PodSetRef(namespace, names)
        val (kind, mode) = PodSeriesKind.entries.flatMap { k -> PodSetMode.entries.map { k to it } }
            .firstOrNull { (k, m) -> PromQl.inClusterPodSetQuery(k, ref, m, window.stepSec) == query }
            ?: return badData("unsupported query")

        val pods = podsOfReplicaSets(namespace, prefixes.toSet(), inNamespace.value)
        val perPod = pods.mapNotNull { pod ->
            val points = clip(podPoints(kind, namespace, pod, window), pod)
            if (points.isEmpty()) null else pod.name to points
        }.sortedBy { it.first }
        val result = when (mode) {
            PodSetMode.PerPod -> perPod
            PodSetMode.Total -> pointwise(kind, perPod.map { it.second }).takeIf { it.isNotEmpty() }?.let { listOf(null to it) }.orEmpty()
        }
        return matrix(result)
    }

    // A prefix as PromQl.podSetRegex wrote it: RE2-escaped, then as a string literal. A DNS name has no
    // metacharacter but the dot, so whatever is left after both decodings must be name characters.
    private fun decodePrefix(raw: String): String? = raw.replace("\\\\", "\\").replace("\\.", ".").takeIf { PREFIX_CHARACTERS.matches(it) }

    // The query's ReplicaSets that exist, oldest first, each with the pods it owns. One that has no pod
    // left is drawn as a single virtual pod, so a revision that has rolled out still has a line.
    private fun podsOfReplicaSets(namespace: String, prefixes: Set<String>, replicaSets: List<ReplicaSet>): List<DemoPod> {
        val listed = replicaSets.mapNotNull { rs ->
            val name = rs.metadata?.name ?: return@mapNotNull null
            val prefix = PromQl.podPrefix(name)
            if (prefix !in prefixes) return@mapNotNull null
            val spec = rs.spec?.template?.spec
            val containers = if (spec == null) emptyList() else ResourceMappers.containerResources(PodBuilder().withSpec(spec).build())
            ListedReplicaSet(name, prefix, creationSec(rs.metadata?.creationTimestamp), containers)
        }.sortedWith(compareBy({ it.createdSec ?: Long.MIN_VALUE }, { it.name }))
        if (listed.isEmpty()) return emptyList()

        val live = podsIn(namespace).groupBy { pod -> listed.indexOfFirst { isPodOf(pod.name, it.prefix) } }
        return listed.flatMapIndexed { index, rs ->
            // The next revision's creation ends this one's pods.
            val until = listed.getOrNull(index + 1)?.createdSec
            val own = live[index].orEmpty()
            if (own.isEmpty()) {
                listOf(DemoPod(rs.prefix + VIRTUAL_POD_SUFFIX, rs.containers, rs.createdSec, until))
            } else {
                own.map { it.copy(toSec = until) }
            }
        }
    }

    private fun isPodOf(podName: String, prefix: String): Boolean = podName.length == prefix.length + POD_SUFFIX_LENGTH &&
        podName.startsWith(prefix) &&
        podName.drop(prefix.length).all { it in 'a'..'z' || it in '0'..'9' }

    // One value per timestamp for the pod: the sum over its containers, or their mean for the throttling ratio.
    private fun podPoints(kind: PodSeriesKind, namespace: String, pod: DemoPod, window: QueryWindow): List<SeriesPoint> = pointwise(kind, demoSeries(kind, PodRef(namespace, pod.name, pod.containers), window).map { it.points })

    private fun clip(points: List<SeriesPoint>, pod: DemoPod): List<SeriesPoint> = points.filter { p -> (pod.fromSec == null || p.epochSec >= pod.fromSec) && (pod.toSec == null || p.epochSec <= pod.toSec) }

    // Combines series that share their timestamps: a sum, except for the throttling ratio, which is a mean.
    private fun pointwise(kind: PodSeriesKind, series: List<List<SeriesPoint>>): List<SeriesPoint> = series.flatten()
        .groupBy({ it.epochSec }, { it.value })
        .toSortedMap()
        .map { (t, values) -> SeriesPoint(t, if (kind == PodSeriesKind.Throttling) values.average() else values.sum()) }

    // The namespace's ReplicaSets and pods from the CRUD store; an answer that is not a list reads as none.
    private fun replicaSetsIn(namespace: String): List<ReplicaSet> = listFrom("/apis/apps/v1/namespaces/$namespace/replicasets", ReplicaSetList::class.java)?.items.orEmpty()

    private fun podsIn(namespace: String): List<DemoPod> = listFrom("/api/v1/namespaces/$namespace/pods", PodList::class.java)?.items.orEmpty().mapNotNull { pod ->
        val name = pod.metadata?.name ?: return@mapNotNull null
        DemoPod(name, ResourceMappers.containerResources(pod), creationSec(pod.metadata?.creationTimestamp), toSec = null)
    }

    private fun <T> listFrom(path: String, type: Class<T>): T? {
        val lookup = RecordedRequest("HTTP/1.1", HttpMethod.GET, path, Headers.builder().build(), Buffer())
        val answer = delegate.dispatch(lookup)
        if (answer.code() != 200) return null
        return try {
            Serialization.unmarshal(String(answer.body?.bytes ?: return null, StandardCharsets.UTF_8), type)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun creationSec(timestamp: String?): Long? = try {
        timestamp?.let { Instant.parse(it).epochSecond }
    } catch (_: DateTimeException) {
        null
    }

    // ── scopes ──────────────────────────────────────────────────────────────

    // The namespaces a scope query names and the kind it asks for, in the form PromQl.inClusterScopeQuery writes.
    private fun scopeOf(query: String, step: Long): Pair<ScopeKind, ScopeRef>? {
        val selector = SCOPE_SELECTOR.find(query) ?: return null
        val one = selector.groups[1]?.value
        val many = selector.groups[2]?.value
        val names = when {
            one != null -> listOf(one)
            many != null -> many.split('|')
            else -> emptyList()
        }
        if (!names.all(KubernetesNames::isDnsLabel)) return null
        val scope = ScopeRef(names.toSet())
        val kind = ScopeKind.entries.firstOrNull { PromQl.inClusterScopeQuery(it, scope, step) == query } ?: return null
        return kind to scope
    }

    // One line for the whole scope that ends on the scope's usage right now, the sum the Overview gauges
    // show, so the card and the gauges agree at the right edge. Earlier points wander around it. No
    // PodMetrics in the scope reads as no series, as Prometheus answers for namespaces without containers.
    private fun scopeMatrix(kind: ScopeKind, scope: ScopeRef, window: QueryWindow): MockResponse {
        val now = scopeUsage(kind, scope)?.takeIf { it > 0 } ?: return matrix(emptyList())
        val seed = scope.namespaces.sorted().joinToString(",")
        val atEnd = scopeShape(kind, seed, window.endEpochSec, jitter = false)
        val points = (window.startEpochSec..window.endEpochSec step window.stepSec).map { t ->
            SeriesPoint(t, now * scopeShape(kind, seed, t, jitter = t != window.endEpochSec) / atEnd)
        }
        return matrix(listOf(null to points))
    }

    // The scope's CPU (cores) or memory (bytes) summed over the PodMetrics of its namespaces, or of every
    // namespace for an empty scope; null when none could be read.
    private fun scopeUsage(kind: ScopeKind, scope: ScopeRef): Double? {
        val paths = if (scope.namespaces.isEmpty()) {
            listOf("/apis/metrics.k8s.io/v1beta1/pods")
        } else {
            scope.namespaces.sorted().map { "/apis/metrics.k8s.io/v1beta1/namespaces/$it/pods" }
        }
        val items = paths.flatMap { listFrom(it, PodMetricsList::class.java)?.items.orEmpty() }
        if (items.isEmpty()) return null
        val resource = if (kind == ScopeKind.CpuCores) "cpu" else "memory"
        return items.sumOf { pm ->
            pm.containers.orEmpty().sumOf { c -> c.usage?.get(resource)?.let(::quantityAmount) ?: 0.0 }
        }
    }

    private fun quantityAmount(quantity: Quantity): Double = try {
        Quantity.getAmountInBytes(quantity).toDouble()
    } catch (_: RuntimeException) {
        0.0
    }

    // A slow drift, a faster ripple and, away from the right edge, a little jitter per minute; fixed by the
    // scope and the time, so a refresh redraws the same line. Memory moves half as much as CPU.
    private fun scopeShape(kind: ScopeKind, seed: String, t: Long, jitter: Boolean): Double {
        val amplitude = if (kind == ScopeKind.CpuCores) 1.0 else 0.5
        val phase = 2 * PI * ((seed.hashCode() + 7_919 * kind.ordinal) and 0xFFFF) / 65_536.0
        val drift = 0.12 * sin(2 * PI * t / 5_400.0 + phase)
        val ripple = 0.05 * sin(2 * PI * t / 1_380.0 + 2 * phase)
        val noise = if (jitter) 0.04 * signedNoise(seed.hashCode().toLong() * 31 + kind.ordinal, Math.floorDiv(t, 60L)) else 0.0
        return 1 + amplitude * (drift + ripple + noise)
    }

    // A value in [-1, 1) from a seed and a slot: the SplitMix64 finaliser.
    private fun signedNoise(seed: Long, slot: Long): Double {
        var z = seed * -0x61c8864680b583ebL + slot
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble() * 2 - 1
    }

    // One entry per series; a null name is the entry without a pod label.
    private fun matrix(series: List<Pair<String?, List<SeriesPoint>>>) = success(
        buildJsonObject {
            put("resultType", "matrix")
            putJsonArray("result") {
                for ((pod, points) in series) {
                    add(
                        buildJsonObject {
                            putJsonObject("metric") { if (pod != null) put("pod", pod) }
                            put("values", samples(points))
                        },
                    )
                }
            }
        },
    )

    // ── access review ───────────────────────────────────────────────────────

    // Allows everything and echoes the spec, so the client reads back the question it asked.
    private fun accessReview(request: RecordedRequest): MockResponse {
        val spec = try {
            Json.parseToJsonElement(String(request.body?.bytes ?: ByteArray(0), StandardCharsets.UTF_8)).let { (it as? JsonObject)?.get("spec") } as? JsonObject
        } catch (_: RuntimeException) {
            null
        } ?: JsonObject(emptyMap())
        val review = buildJsonObject {
            put("kind", "SelfSubjectAccessReview")
            put("apiVersion", "authorization.k8s.io/v1")
            putJsonObject("metadata") {}
            put("spec", spec)
            putJsonObject("status") { put("allowed", true) }
        }
        return json(201, review.toString())
    }

    // ── replies ─────────────────────────────────────────────────────────────

    private fun success(data: JsonObject) = json(
        200,
        buildJsonObject {
            put("status", "success")
            put("data", data)
        }.toString(),
    )

    private fun badData(message: String) = prometheusError(400, "bad_data", message)

    private fun agentRefusal() = prometheusError(422, "execution", "unavailable with Prometheus Agent")

    private fun prometheusError(code: Int, errorType: String, message: String) = json(
        code,
        buildJsonObject {
            put("status", "error")
            put("errorType", errorType)
            put("error", message)
        }.toString(),
    )

    // The API server's own answers, as k8s v1.34.1 storage.go ResourceLocation gives them.
    private fun notFound(resource: String, name: String) = json(
        404,
        buildJsonObject {
            put("kind", "Status")
            put("apiVersion", "v1")
            putJsonObject("metadata") {}
            put("status", "Failure")
            put("message", """$resource "$name" not found""")
            put("reason", "NotFound")
            putJsonObject("details") {
                put("name", name)
                put("kind", resource)
            }
            put("code", 404)
        }.toString(),
    )

    // What the API server says to a route it does not serve: no name to put in the message.
    private fun notFoundRoute() = json(
        404,
        buildJsonObject {
            put("kind", "Status")
            put("apiVersion", "v1")
            putJsonObject("metadata") {}
            put("status", "Failure")
            put("message", "the server could not find the requested resource")
            put("reason", "NotFound")
            put("code", 404)
        }.toString(),
    )

    private fun noEndpoints(name: String) = json(
        503,
        buildJsonObject {
            put("kind", "Status")
            put("apiVersion", "v1")
            putJsonObject("metadata") {}
            put("status", "Failure")
            put("message", """no endpoints available for service "$name"""")
            put("reason", "ServiceUnavailable")
            put("code", 503)
        }.toString(),
    )

    private fun plain404() = MockResponse().setResponseCode(404).setHeader("Content-Type", "text/plain; charset=utf-8").setBody("404 page not found\n")

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun nowSec() = System.currentTimeMillis() / 1000

    // [unix seconds as a number, "value" as a string], the way Prometheus writes a sample.
    private fun samples(points: List<SeriesPoint>) = buildJsonArray {
        for (p in points) {
            add(
                buildJsonArray {
                    add(JsonPrimitive(p.epochSec))
                    add(JsonPrimitive(sampleText(p.value)))
                },
            )
        }
    }

    // The shortest decimal that reads back as the same double, never in exponent form.
    private fun sampleText(value: Double): String = if (value.isFinite()) BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() else "NaN"

    companion object {
        const val ACCESS_REVIEW_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews"

        private const val MONITORING = "monitoring"
        private const val AGENT_SERVICE = "prometheus-agent-operated"
        private const val WEB_PORT_NAME = "web"
        private const val WEB_PORT_NUMBER = "9090"
        private val SERVED_SERVICES = setOf("prometheus-operated", AGENT_SERVICE)

        // What `count(container_cpu_usage_seconds_total)` answers: a small, round series count.
        private const val DEMO_SERIES_COUNT = 42

        // Prometheus refuses a range query that would return more points than this per series.
        private const val MAX_POINTS = 11_000L

        private val SERVICE_PROXY = Regex("/api/v1/namespaces/([^/]+)/services/([^/]+)/proxy(/.*)?")
        private val POD_SELECTOR = Regex("""namespace="([^"]*)",pod="([^"]*)"""")
        private val DNS_SUBDOMAIN = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")

        // namespace="x",pod=~"(a-|b-)[a-z0-9]{5}": the alternation is captured as written.
        private val POD_SET_SELECTOR = Regex("""namespace="([^"]*)",pod=~"\(([^"]*)\)\[a-z0-9\]\{5\}"""")

        // {container!="",...}, {namespace="x",container!="",...} or {namespace=~"x|y",container!="",...}
        private val SCOPE_SELECTOR = Regex("""\{(?:namespace="([^"]*)",|namespace=~"([^"]*)",)?container!=""""")

        // What is left of a ReplicaSet's pod prefix once its escapes are undone.
        private val PREFIX_CHARACTERS = Regex("[a-z0-9][-a-z0-9.]*")
        private const val POD_SUFFIX_LENGTH = 5

        // The pod that stands for a ReplicaSet whose pods are gone; it fits the [a-z0-9]{5} of the pattern.
        private const val VIRTUAL_POD_SUFFIX = "old01"

        /**
         * True when a path segment is `proxy`: the subresource of a Service, Pod or Node, whatever
         * follows it. A name that merely starts with it (`proxy-config`) is not one.
         */
        private fun isProxyPath(bare: String): Boolean = bare.split('/').any { it == "proxy" }

        /** The query parameters, form-decoded the way fabric8's HttpRequest.formURLEncode encodes them (space is `+`). */
        private fun queryParams(rawQuery: String): Map<String, String> = rawQuery.split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
    }
}
