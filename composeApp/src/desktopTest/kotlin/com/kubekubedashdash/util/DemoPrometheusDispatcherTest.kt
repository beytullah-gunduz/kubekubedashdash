package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.InClusterMetricsSource
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.PrometheusDetector
import com.kubekubedashdash.services.metricshistory.PrometheusResponse
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ReleaseCheckSupport
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.ServiceProxyAccessReview
import com.kubekubedashdash.services.metricshistory.ServiceProxyHttp
import com.kubekubedashdash.services.metricshistory.demoSeries
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.CustomResourceAware
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.server.mock.Resetable
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.dsl.HttpMethod
import io.fabric8.mockwebserver.http.Buffer
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.Headers
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The demo's fake Prometheus, driven straight through [DemoPrometheusDispatcher.dispatch] with a
 * recording delegate (what is answered, and that a proxy path never reaches the delegate), and
 * through fabric8's real HTTP client against a mock server that wraps the CRUD dispatcher.
 */
class DemoPrometheusDispatcherTest {

    /** Records every request it gets; answers the one known pod, 404 for other pods and a marker for the rest. */
    private class RecordingDelegate :
        Dispatcher(),
        Resetable,
        CustomResourceAware {
        val seen = CopyOnWriteArrayList<String>()
        var shutdowns = 0
        var resets = 0
        val expectedContexts = CopyOnWriteArrayList<CustomResourceDefinitionContext>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            seen += "${request.method} $path"
            return when {
                request.method == "GET" && path == POD_PATH -> MockResponse().setResponseCode(200).setBody(Serialization.asJson(knownPod()))
                request.method == "GET" && path.startsWith("/api/v1/namespaces/") && "/pods/" in path -> MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(DELEGATE_MARKER_CODE).setBody("delegate")
            }
        }

        override fun shutdown() {
            shutdowns++
        }

        override fun reset() {
            resets++
        }

        override fun expectCustomResource(rdc: CustomResourceDefinitionContext) {
            expectedContexts += rdc
        }
    }

    private val delegate = RecordingDelegate()
    private val dispatcher = DemoPrometheusDispatcher(delegate)
    private val target = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")
    private val agent = target.copy(service = "prometheus-agent-operated")
    private val pod = PodRef("team-a", "web-0")
    private val window = QueryWindow(startEpochSec = 1_000, endEpochSec = 1_900, stepSec = 30)

    private val mockHolder = lazy { MockedApi() }
    private val mocked by mockHolder

    @AfterTest
    fun tearDown() {
        if (mockHolder.isInitialized()) mocked.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun request(method: HttpMethod, path: String, body: String = ""): RecordedRequest = RecordedRequest("HTTP/1.1", method, path, Headers.builder().build(), Buffer(body.toByteArray()))

    private fun get(path: String): MockResponse = dispatcher.dispatch(request(HttpMethod.GET, path))

    private fun MockResponse.text(): String = String(body.bytes, Charsets.UTF_8)

    private fun MockResponse.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    // The path the real client sends: the production URL builder, minus the host.
    private fun proxyPath(
        apiPath: String,
        vararg params: Pair<String, String>,
        on: InClusterTarget = target,
    ): String = ServiceProxyHttp.proxyUrl(MASTER, on, apiPath, params.toList()).removePrefix(MASTER)

    private fun rangePath(
        kind: PodSeriesKind,
        podRef: PodRef = pod,
        w: QueryWindow = window,
        on: InClusterTarget = target,
    ) = proxyPath(
        "query_range",
        "query" to PromQl.inClusterPodQuery(kind, podRef, w.stepSec),
        "start" to w.startEpochSec.toString(),
        "end" to w.endEpochSec.toString(),
        "step" to w.stepSec.toString(),
        on = on,
    )

    private fun errorType(response: MockResponse): String? = PrometheusResponse.errorTypeOf(response.text())

    private fun isKubernetesStatus(response: MockResponse) = InClusterMetricsSource.isKubernetesStatus(response.text())

    // ── instant queries ─────────────────────────────────────────────────────

    @Test
    fun `query=1 answers a scalar that the detection probe accepts, on the port name and the port number`() {
        for (port in listOf("web", "9090")) {
            val response = get(proxyPath("query", "query" to "1", on = target.copy(port = port)))

            assertEquals(200, response.code(), port)
            assertTrue(PrometheusResponse.isQueryableProbe(response.text()), port)
            assertEquals("scalar", response.json().getValue("data").jsonObject.getValue("resultType").jsonPrimitive.content)
        }
    }

    @Test
    fun `the no-data probe counts 42 series`() {
        val response = get(proxyPath("query", "query" to PromQl.inClusterProbeQuery()))

        assertEquals(200, response.code())
        assertEquals(PrometheusResponse.Parsed.Scalar(42.0), PrometheusResponse.parseVectorFirstValue(response.text()))
    }

    @Test
    fun `any other instant query is a bad_data answer that does not repeat the query`() {
        for (query in listOf("up", "count(container_memory_working_set_bytes)", "1 + 1", "")) {
            val response = get(proxyPath("query", "query" to query))

            assertEquals(400, response.code(), query)
            assertEquals("bad_data", errorType(response), query)
            assertFalse("container_memory" in response.text(), "the answer repeats the query")
        }
        assertEquals(400, get(proxyPath("query")).code(), "no query parameter")
    }

    @Test
    fun `the service can be addressed with a scheme, a bare name or a port number`() {
        val query = "query" to "1"

        assertEquals(200, get(proxyPath("query", query, on = target.copy(https = true))).code())
        assertEquals(200, get("/api/v1/namespaces/monitoring/services/prometheus-operated/proxy/api/v1/query?query=1").code())
        assertEquals(200, get("/api/v1/namespaces/monitoring/services/prometheus-operated:9090/proxy/api/v1/query?query=1").code())
    }

    // ── range queries ───────────────────────────────────────────────────────

    private val containers = listOf(
        ContainerResources("app", cpuRequestMillis = 100, cpuLimitMillis = 500, memoryLimitBytes = 1L shl 30),
        ContainerResources("sidecar", cpuRequestMillis = 50),
    )

    private fun nameLabelOf(kind: PodSeriesKind) = if (kind == PodSeriesKind.NetworkRx || kind == PodSeriesKind.NetworkTx) null else "container"

    @Test
    fun `each kind answers a matrix equal to the demo series of the pod's containers`() {
        for (kind in PodSeriesKind.entries) {
            val response = get(rangePath(kind))

            assertEquals(200, response.code(), "$kind")
            val parsed = PrometheusResponse.parseMatrix(response.text(), nameLabelOf(kind))
            assertIs<PrometheusResponse.Parsed.Matrix>(parsed, "$kind")
            assertEquals(demoSeries(kind, PodRef("team-a", "web-0", containers), window), parsed.series, "$kind")
        }
    }

    @Test
    fun `the series are named per container, except the network ones and the throttling of a container without a limit`() {
        fun namesOf(kind: PodSeriesKind): List<String> = (PrometheusResponse.parseMatrix(get(rangePath(kind)).text(), nameLabelOf(kind)) as PrometheusResponse.Parsed.Matrix).series.map(Series::name)

        assertEquals(listOf("app", "sidecar"), namesOf(PodSeriesKind.Cpu))
        assertEquals(listOf("app", "sidecar"), namesOf(PodSeriesKind.Memory))
        assertEquals(listOf("app"), namesOf(PodSeriesKind.Throttling))
        assertEquals(listOf(""), namesOf(PodSeriesKind.NetworkRx))
        assertEquals(listOf(""), namesOf(PodSeriesKind.NetworkTx))
    }

    @Test
    fun `samples are a number and a string, the way Prometheus writes them`() {
        val result = get(rangePath(PodSeriesKind.Cpu)).json().getValue("data").jsonObject.getValue("result").jsonArray
        assertEquals(2, result.size)

        val first = result.first().jsonObject
        assertEquals("app", first.getValue("metric").jsonObject.getValue("container").jsonPrimitive.content)
        val samples = first.getValue("values").jsonArray
        assertEquals((window.endEpochSec - window.startEpochSec) / window.stepSec + 1, samples.size.toLong())
        val sample = samples.first().jsonArray
        assertFalse(sample[0].jsonPrimitive.isString, "the timestamp is a number")
        assertEquals(window.startEpochSec, sample[0].jsonPrimitive.content.toLong())
        assertTrue(sample[1].jsonPrimitive.isString, "the value is a string")
        assertNotNull(sample[1].jsonPrimitive.content.toDoubleOrNull())
        assertFalse(Regex("[eE]").containsMatchIn(sample[1].jsonPrimitive.content), "no exponent: ${sample[1]}")

        val network = get(rangePath(PodSeriesKind.NetworkRx)).json().getValue("data").jsonObject.getValue("result").jsonArray
        assertEquals(JsonObject(emptyMap()), network.single().jsonObject.getValue("metric"))
    }

    @Test
    fun `the pod lookup is the only call that reaches the delegate`() {
        get(rangePath(PodSeriesKind.Memory))

        assertEquals(listOf("GET $POD_PATH"), delegate.seen.toList())
    }

    @Test
    fun `a pod the store does not have answers an empty matrix`() {
        for (kind in PodSeriesKind.entries) {
            val response = get(rangePath(kind, PodRef("team-a", "gone-0")))

            assertEquals(200, response.code(), "$kind")
            assertEquals(PrometheusResponse.Parsed.Matrix(emptyList()), PrometheusResponse.parseMatrix(response.text(), nameLabelOf(kind)), "$kind")
        }
        assertEquals("GET /api/v1/namespaces/team-a/pods/gone-0", delegate.seen.first())
    }

    @Test
    fun `a pod name that is not a Kubernetes name never reaches the delegate`() {
        for (name in listOf("../kube-system/pods/x", "web-0?watch=true", "Web-0", "web 0", "a/b")) {
            val response = get(rangePath(PodSeriesKind.Cpu, PodRef("team-a", name)))

            assertEquals(200, response.code(), name)
            assertEquals(PrometheusResponse.Parsed.Matrix(emptyList()), PrometheusResponse.parseMatrix(response.text(), "container"), name)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `a range query that is none of the five forms is bad_data`() {
        val cpu = PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, window.stepSec)
        val queries = listOf(
            "up",
            cpu.replace("sum by (container)", "max by (container)"),
            cpu.replace("container!=\"POD\"", "container!=\"x\""),
            // The right form with the wrong rate window for this step.
            PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, 600),
            "namespace=\"team-a\",pod=\"web-0\"",
        )
        for (query in queries) {
            val response = get(proxyPath("query_range", "query" to query, "start" to "1000", "end" to "1900", "step" to "30"))

            assertEquals(400, response.code(), query)
            assertEquals("bad_data", errorType(response), query)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `a range query with a missing or unusable time range is bad_data`() {
        val query = "query" to PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, 30)
        val bad = listOf(
            listOf(query, "end" to "1900", "step" to "30"),
            listOf(query, "start" to "1000", "step" to "30"),
            listOf(query, "start" to "1000", "end" to "1900"),
            listOf(query, "start" to "soon", "end" to "1900", "step" to "30"),
            listOf(query, "start" to "1000", "end" to "1900", "step" to "30s"),
            listOf(query, "start" to "1000", "end" to "1900", "step" to "0"),
            listOf(query, "start" to "1000", "end" to "1900", "step" to "-30"),
            listOf(query, "start" to "1900", "end" to "1000", "step" to "30"),
            listOf(query, "start" to "-5", "end" to "1000", "step" to "30"),
            // Prometheus' own bound: more than 11,000 points per series.
            listOf(query, "start" to "0", "end" to "1000000", "step" to "30"),
        )
        for (params in bad) {
            val response = get(proxyPath("query_range", *params.toTypedArray()))

            assertEquals(400, response.code(), "$params")
            assertEquals("bad_data", errorType(response), "$params")
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    // ── what the demo does not have ─────────────────────────────────────────

    @Test
    fun `the agent's Service refuses every query with a 422 on any port`() {
        for (port in listOf("web", "9090", "nope")) {
            val on = agent.copy(port = port)
            for (response in listOf(get(proxyPath("query", "query" to "1", on = on)), get(rangePath(PodSeriesKind.Cpu, on = on)))) {
                assertEquals(422, response.code(), port)
                assertEquals("execution", errorType(response), port)
                assertFalse(PrometheusResponse.isQueryableProbe(response.text()))
            }
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `a port the Service does not have is a 503 Status, like an API server with no endpoint for it`() {
        for (port in listOf("nope", "8080", "http")) {
            val response = get(proxyPath("query", "query" to "1", on = target.copy(port = port)))

            assertEquals(503, response.code(), port)
            assertTrue(isKubernetesStatus(response), port)
            val body = response.json()
            assertEquals("Failure", body.getValue("status").jsonPrimitive.content)
            assertEquals("ServiceUnavailable", body.getValue("reason").jsonPrimitive.content)
            assertEquals("no endpoints available for service \"prometheus-operated\"", body.getValue("message").jsonPrimitive.content)
            assertEquals(503, body.getValue("code").jsonPrimitive.content.toInt())
        }
        assertEquals(503, get(rangePath(PodSeriesKind.Cpu, on = target.copy(port = "nope"))).code())
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `any other service, namespace or proxy target is a 404 Status that never reaches the delegate`() {
        val paths = listOf(
            "/api/v1/namespaces/monitoring/services/prometheus-server:80/proxy/api/v1/query?query=1",
            "/api/v1/namespaces/monitoring/services/prometheus-server/proxy/api/v1/query_range?query=up",
            "/api/v1/namespaces/team-a/services/prometheus-operated:web/proxy/api/v1/query?query=1",
            "/api/v1/namespaces/lens-metrics/services/prometheus:80/proxy/api/v1/query?query=1",
            "/api/v1/namespaces/monitoring/services/a:b:c:d/proxy/api/v1/query?query=1",
            "/api/v1/namespaces/monitoring/services/https:prometheus-operated/proxy/api/v1/query?query=1",
            "/api/v1/nodes/mock-node-1/proxy/metrics/cadvisor",
            "/api/v1/namespaces/default/pods/web-0:8080/proxy/",
            "/api/v1/namespaces/default/pods/web-0/proxy/metrics",
            "/api/v1/namespaces/default/pods/web-0/proxy",
            "/api/v1/namespaces/default/services/frontend-svc/proxy/",
        )
        for (path in paths) {
            val response = get(path)

            assertEquals(404, response.code(), path)
            assertTrue(isKubernetesStatus(response), path)
            assertEquals("NotFound", response.json().getValue("reason").jsonPrimitive.content, path)
        }
        assertEquals(emptyList(), delegate.seen.toList(), "a proxy path reached the delegate")
    }

    @Test
    fun `a 404 for a missing Service names it in the Status, as the API server does`() {
        val response = get("/api/v1/namespaces/monitoring/services/prometheus-server:80/proxy/api/v1/query?query=1")

        val body = response.json()
        assertEquals("services \"prometheus-server\" not found", body.getValue("message").jsonPrimitive.content)
        assertEquals("prometheus-server", body.getValue("details").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("services", body.getValue("details").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(404, body.getValue("code").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a path the Prometheus itself does not know is a plain 404, which reads as a wrong path and not a missing Service`() {
        for (rest in listOf("", "/", "/api/v1/status/buildinfo", "/prometheus/api/v1/query", "/api/v1/query/", "/../../secrets")) {
            val response = get("/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy$rest?query=1")

            assertEquals(404, response.code(), rest)
            assertFalse(isKubernetesStatus(response), rest)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `only a GET reaches the Prometheus`() {
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            val response = dispatcher.dispatch(request(method, proxyPath("query", "query" to "1"), "query=1"))

            assertEquals(404, response.code(), "$method")
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    // ── everything else ─────────────────────────────────────────────────────

    @Test
    fun `a path that is not a proxy path goes to the delegate, names that merely start with proxy included`() {
        val paths = listOf(
            "/version",
            "/api/v1/namespaces/default/configmaps/proxy-config",
            "/api/v1/namespaces/default/pods?labelSelector=app%3Dproxy",
            "/api/v1/namespaces/monitoring/services?labelSelector=operated-prometheus%3Dtrue",
            "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews",
        )
        for (path in paths) {
            val response = get(path)

            assertEquals(DELEGATE_MARKER_CODE, response.code(), path)
            assertEquals("delegate", response.text(), path)
        }
        assertEquals(paths.map { "GET $it" }, delegate.seen.toList())
    }

    @Test
    fun `a request that is not a GET to a non-proxy path goes to the delegate too`() {
        val response = dispatcher.dispatch(request(HttpMethod.POST, "/api/v1/namespaces/default/configmaps", "{}"))

        assertEquals(DELEGATE_MARKER_CODE, response.code())
        assertEquals(listOf("POST /api/v1/namespaces/default/configmaps"), delegate.seen.toList())
    }

    @Test
    fun `shutdown, reset and custom resources are forwarded`() {
        dispatcher.shutdown()
        dispatcher.reset()
        val context = CustomResourceDefinitionContext.Builder().withGroup("example.com").withVersion("v1").withPlural("widgets").build()
        dispatcher.expectCustomResource(context)

        assertEquals(1, delegate.shutdowns)
        assertEquals(1, delegate.resets)
        assertEquals(listOf(context), delegate.expectedContexts.toList())
    }

    // ── access reviews ──────────────────────────────────────────────────────

    @Test
    fun `an access review is allowed and echoes the spec it was asked`() {
        val spec = """{"resourceAttributes":{"namespace":"monitoring","verb":"get","resource":"services","subresource":"proxy"}}"""

        val response = dispatcher.dispatch(request(HttpMethod.POST, ACCESS_REVIEW_PATH, """{"kind":"SelfSubjectAccessReview","apiVersion":"authorization.k8s.io/v1","spec":$spec}"""))

        assertEquals(201, response.code())
        val body = response.json()
        assertEquals("SelfSubjectAccessReview", body.getValue("kind").jsonPrimitive.content)
        assertTrue(body.getValue("status").jsonObject.getValue("allowed").jsonPrimitive.boolean)
        assertEquals(Json.parseToJsonElement(spec), body.getValue("spec"))
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `an access review with a body that is not a review is still allowed`() {
        for (body in listOf("", "not json", "[]", "{}", """{"spec":"x"}""")) {
            val response = dispatcher.dispatch(request(HttpMethod.POST, ACCESS_REVIEW_PATH, body))

            assertEquals(201, response.code(), body)
            assertTrue(response.json().getValue("status").jsonObject.getValue("allowed").jsonPrimitive.boolean, body)
            assertEquals(JsonObject(emptyMap()), response.json().getValue("spec"), body)
        }
    }

    // ── through fabric8's HTTP client ───────────────────────────────────────

    /** A mock API server whose dispatcher is the one under test over a real CRUD store, and a client with retries off. */
    private class MockedApi {
        private val server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), DemoPrometheusDispatcher(KubernetesCrudDispatcher()), false).also { it.init() }
        val client: KubernetesClient = server.createClient().use { seed ->
            KubernetesClientBuilder().withConfig(ConfigBuilder(seed.configuration).withRequestRetryBackoffLimit(0).withRequestRetryBackoffInterval(Config.DEFAULT_REQUEST_RETRY_BACKOFFINTERVAL).build()).build()
        }

        fun close() {
            shutdownCleanly(label = "DemoPrometheusDispatcherTest", client = client, servers = listOf(server))
        }
    }

    @Test
    fun `fabric8 reads an access review answered by the dispatcher`() {
        val review = SelfSubjectAccessReviewBuilder()
            .withNewSpec().withNewResourceAttributes()
            .withNamespace("monitoring").withVerb("get").withResource("services").withSubresource("proxy")
            .endResourceAttributes().endSpec()
            .build()

        val answer = mocked.client.authorization().v1().selfSubjectAccessReview().create(review)

        assertEquals(true, answer.status.allowed)
        assertEquals("proxy", answer.spec.resourceAttributes.subresource)
        assertEquals("monitoring", answer.spec.resourceAttributes.namespace)
    }

    @Test
    fun `the access review the app sends comes back allowed`() = runBlocking<Unit> {
        assertEquals(true, ServiceProxyAccessReview().allowed("example-context", "monitoring") { mocked.client })
    }

    @Test
    fun `over the real HTTP path a CRUD pod's containers shape the matrix`() = runBlocking<Unit> {
        mocked.client.pods().inNamespace("team-a").resource(knownPod()).create()
        val http = ServiceProxyHttp(mocked.client)

        val response = http.get(
            target,
            "query_range",
            listOf(
                "query" to PromQl.inClusterPodQuery(PodSeriesKind.Throttling, pod, window.stepSec),
                "start" to window.startEpochSec.toString(),
                "end" to window.endEpochSec.toString(),
                "step" to window.stepSec.toString(),
            ),
            timeoutSec = 10,
        )

        assertEquals(200, response.status)
        val parsed = PrometheusResponse.parseMatrix(response.body, "container")
        assertIs<PrometheusResponse.Parsed.Matrix>(parsed)
        assertEquals(listOf("app"), parsed.series.map { it.name })
    }

    @Test
    fun `the detection finds the headless Service by its label and skips the agent's, over the real HTTP path`() = runBlocking<Unit> {
        val services = mocked.client.services().inNamespace("monitoring")
        for (name in listOf("prometheus-agent-operated", "prometheus-operated")) {
            services.resource(
                ServiceBuilder()
                    .withNewMetadata().withName(name).withNamespace("monitoring").addToLabels("operated-prometheus", "true").endMetadata()
                    .withNewSpec().addNewPort().withName("web").withPort(9090).endPort().endSpec()
                    .build(),
            ).create()
        }

        val state = PrometheusDetector().detect(mocked.client)

        assertEquals(DetectionState.Found(target), state)
    }

    @Test
    fun `an unknown service through the real HTTP path is a 404 Status and a wrong port a 503 Status`() = runBlocking<Unit> {
        val http = ServiceProxyHttp(mocked.client)
        val query = listOf("query" to "1")

        val missing = http.get(target.copy(service = "prometheus-server"), "query", query, timeoutSec = 10)
        val noPort = http.get(target.copy(port = "nope"), "query", query, timeoutSec = 10)
        val agentAnswer = http.get(agent, "query", query, timeoutSec = 10)

        assertEquals(404, missing.status)
        assertTrue(InClusterMetricsSource.isKubernetesStatus(missing.body))
        assertEquals(503, noPort.status)
        assertTrue(InClusterMetricsSource.isKubernetesStatus(noPort.body))
        assertEquals(422, agentAnswer.status)
        assertEquals("execution", PrometheusResponse.errorTypeOf(agentAnswer.body))
    }

    @Test
    fun `the release check helper refuses a cluster without the demo's Service, naming no cluster detail`() {
        val failure = assertFailsWith<IllegalStateException> { ReleaseCheckSupport.demoCpuHistoryPoints(mocked.client) }

        assertEquals("detection answered NothingFound", failure.message)
    }

    private companion object {
        const val MASTER = "http://127.0.0.1:1"
        const val POD_PATH = "/api/v1/namespaces/team-a/pods/web-0"
        const val ACCESS_REVIEW_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews"

        // Not a code the dispatcher or the CRUD store ever answers: marks "the delegate answered".
        const val DELEGATE_MARKER_CODE = 299

        fun knownPod() = PodBuilder()
            .withNewMetadata().withNamespace("team-a").withName("web-0").endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("app").withImage("example/app:1")
            .withNewResources()
            .addToRequests("cpu", Quantity("100m"))
            .addToLimits("cpu", Quantity("500m"))
            .addToLimits("memory", Quantity("1Gi"))
            .endResources()
            .endContainer()
            .addNewContainer()
            .withName("sidecar").withImage("example/sidecar:1")
            .withNewResources().addToRequests("cpu", Quantity("50m")).endResources()
            .endContainer()
            .endSpec()
            .build()
    }
}
