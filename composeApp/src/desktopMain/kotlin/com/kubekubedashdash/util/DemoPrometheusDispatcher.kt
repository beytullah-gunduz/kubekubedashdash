package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.demoSeries
import io.fabric8.kubernetes.api.model.Pod
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

/**
 * The demo cluster's fake Prometheus, served on the mock API server's service-proxy paths so the
 * demo reaches it through the same HTTP code as a real cluster: detection, the proxy URL, the
 * status mapping. Everything that is not a proxy path or an access review goes to [delegate].
 *
 * A proxy path is never forwarded, whatever it names: the CRUD store answers an unknown path with
 * 200 and its whole contents. Services and ports the demo does not have answer as the API server
 * does (404 and 503 Status objects). The only call into [delegate] on a proxy path is the pod
 * lookup, a synthetic GET that reads the pod's containers for [demoSeries].
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

        val selector = POD_SELECTOR.find(query) ?: return badData("unsupported query")
        val (namespace, pod) = selector.destructured
        val kind = PodSeriesKind.entries.firstOrNull { PromQl.inClusterPodQuery(it, PodRef(namespace, pod), step) == query }
            ?: return badData("unsupported query")

        val containers = containersOf(namespace, pod)
        val series = if (containers == null) {
            emptyList()
        } else {
            demoSeries(kind, PodRef(namespace, pod, containers), QueryWindow(start, end, step))
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
