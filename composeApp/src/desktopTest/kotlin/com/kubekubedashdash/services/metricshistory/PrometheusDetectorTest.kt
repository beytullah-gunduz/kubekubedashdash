package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.ShellEnvironment
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.mockwebserver.http.MockResponse
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Prometheus search against a loopback fabric8 mock API server: which Services are candidates
 * and in what order, how a probe is judged, and what each kind of failure turns the whole search into.
 */
class PrometheusDetectorTest {

    private var previousCloudClis: String? = null
    private lateinit var stub: ClusterStub
    private val clock = AtomicLong(5_000)

    @BeforeTest
    fun setUp() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
        stub = ClusterStub()
    }

    @AfterTest
    fun tearDown() {
        try {
            stub.close("PrometheusDetectorTest")
        } finally {
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    private val operated = mapOf("operated-prometheus" to "true")
    private val chart = mapOf("app.kubernetes.io/name" to "prometheus", "app.kubernetes.io/component" to "server")
    private val oldChart = mapOf("app" to "prometheus", "component" to "server")

    private fun target(namespace: String, service: String, port: String) = InClusterTarget(namespace, service, port, https = false, pathPrefix = "")

    private fun detect(
        client: KubernetesClient = stub.client(),
        budgetMs: Long = 5_000,
        probeTimeoutSec: Long = 2,
    ): DetectionState = runBlocking { PrometheusDetector(budgetMs, probeTimeoutSec, clock::get).detect(client) }

    /** `<namespace>/<service>:<port>` of each probe, in the order they were sent. */
    private fun probed(): List<String> = stub.proxyRequests.map { it.path.substringAfter("/namespaces/").substringBefore("/proxy").replace("/services/", "/") }

    /** Answers a probe by the Service it names; anything unlisted is a 404 from the Prometheus itself. */
    private fun answerProbes(vararg answers: Pair<String, () -> MockResponse>) {
        val byService = answers.toMap()
        stub.proxy = { request ->
            val id = request.path.substringAfter("/namespaces/").substringBefore("/proxy").replace("/services/", "/")
            byService[id]?.invoke() ?: jsonResponse(404, "404 page not found")
        }
    }

    private fun ok() = jsonResponse(200, SCALAR_OK)

    private fun service(name: String, vararg ports: Pair<String?, Int>): Service = ServiceBuilder()
        .withNewMetadata().withNamespace("monitoring").withName(name).endMetadata()
        .withNewSpec().withPorts(ports.map { (portName, number) -> ServicePortBuilder().withName(portName).withPort(number).build() }).endSpec()
        .build()

    @Test
    fun `the finders are tried in rank order and the first confirmed candidate wins`() {
        stub.addService("monitoring", "prometheus-operated", operated, listOf("web" to 9090))
        stub.addService("team-a", "prometheus-server", chart, listOf("http" to 80))
        stub.addService("team-a", "legacy-prometheus", oldChart, listOf("http" to 80))
        stub.addService("lens-metrics", "prometheus", emptyMap(), listOf(null to 80))
        answerProbes("lens-metrics/prometheus:80" to ::ok)

        val result = detect()

        assertEquals(DetectionState.Found(target("lens-metrics", "prometheus", "80")), result)
        assertEquals(
            listOf("monitoring/prometheus-operated:web", "team-a/prometheus-server:http", "team-a/legacy-prometheus:http", "lens-metrics/prometheus:80"),
            probed(),
        )
    }

    @Test
    fun `the first candidate that answers is the only one probed`() {
        stub.addService("monitoring", "prometheus-operated", operated, listOf("web" to 9090))
        stub.addService("team-a", "prometheus-server", chart, listOf("http" to 80))

        val result = detect()

        assertEquals(DetectionState.Found(target("monitoring", "prometheus-operated", "web")), result)
        assertEquals(listOf("monitoring/prometheus-operated:web"), probed())
    }

    @Test
    fun `each finder's rank decides the order, not the order the lists answer in`() {
        stub.addService("team-a", "legacy-prometheus", oldChart, listOf("http" to 80))
        stub.addService("team-a", "prometheus-server", chart, listOf("http" to 80))
        stub.addService("monitoring", "prometheus-operated", operated, listOf("web" to 9090))
        // The rank-1 list is the slowest to answer.
        stub.finders = { request, answer -> if ("operated-prometheus" in request.path) answer.setBodyDelay(Duration.ofMillis(600)) else answer }
        answerProbes("team-a/legacy-prometheus:http" to ::ok)

        val result = detect()

        assertEquals(DetectionState.Found(target("team-a", "legacy-prometheus", "http")), result)
        assertEquals(
            listOf("monitoring/prometheus-operated:web", "team-a/prometheus-server:http", "team-a/legacy-prometheus:http"),
            probed(),
        )
    }

    @Test
    fun `within a rank candidates sort by namespace, then name`() {
        stub.addService("b-ns", "prom", chart, listOf("http" to 80))
        stub.addService("a-ns", "prom-z", chart, listOf("http" to 80))
        stub.addService("a-ns", "prom-b", chart, listOf("http" to 80))
        answerProbes()

        val result = detect()

        assertIs<DetectionState.NothingFound>(result)
        assertEquals(listOf("a-ns/prom-b:http", "a-ns/prom-z:http", "b-ns/prom:http"), probed())
    }

    @Test
    fun `a Service that carries two rank labels is one candidate at its best rank`() {
        stub.addService("team-a", "prometheus-server", chart + oldChart, listOf("http" to 80))
        answerProbes()

        val result = detect()

        assertIs<DetectionState.NothingFound>(result)
        assertEquals(listOf("team-a/prometheus-server:http"), probed())
    }

    @Test
    fun `the operator agent's governing Service is never probed`() {
        stub.addService("monitoring", "prometheus-agent-operated", operated, listOf("web" to 9090))

        val alone = detect()

        assertIs<DetectionState.NothingFound>(alone)
        assertEquals(emptyList(), probed())

        stub.addService("monitoring", "prometheus-operated", operated, listOf("web" to 9090))
        val withServer = detect()

        assertEquals(DetectionState.Found(target("monitoring", "prometheus-operated", "web")), withServer)
        assertEquals(listOf("monitoring/prometheus-operated:web"), probed())
    }

    @Test
    fun `at most four candidates are probed`() {
        for (namespace in listOf("ns-a", "ns-b", "ns-c", "ns-d", "ns-e")) {
            stub.addService(namespace, "prom", chart, listOf("http" to 80))
        }
        answerProbes("ns-e/prom:http" to ::ok)

        val result = detect()

        assertIs<DetectionState.NothingFound>(result)
        assertEquals(listOf("ns-a/prom:http", "ns-b/prom:http", "ns-c/prom:http", "ns-d/prom:http"), probed())
    }

    @Test
    fun `the port is the first of web, http-web or http by position, else the first port, by name or by number`() {
        fun port(vararg ports: Pair<String?, Int>) = PrometheusDetector.choosePort(service("s", *ports))

        assertEquals("web", port("metrics" to 9091, "web" to 9090))
        assertEquals("http-web", port("metrics" to 9091, "http-web" to 9090, "web" to 9093))
        assertEquals("http", port("grpc" to 10901, "http" to 10902))
        assertEquals("grpc", port("grpc" to 10901, "cluster" to 10902))
        assertEquals("9090", port(null to 9090, "x" to 1))
        assertEquals("9090", port(null to 9090))
        assertEquals("web", port(null to 1, "web" to 9090))
        assertNull(PrometheusDetector.choosePort(service("s")))
    }

    @Test
    fun `the probe goes to the chosen port`() {
        stub.addService("monitoring", "prometheus-operated", operated, listOf("metrics" to 9091, "http-web" to 9090))
        stub.addService("team-a", "prometheus-server", chart, listOf(null to 9090))
        stub.addService("team-b", "no-ports", chart, emptyList())
        answerProbes()

        detect()

        assertEquals(listOf("monitoring/prometheus-operated:http-web", "team-a/prometheus-server:9090"), probed())
    }

    @Test
    fun `a Service whose fields cannot be validated is skipped and never reaches a URL`() {
        stub.addService("monitoring", "Bad_Name", chart, listOf("http" to 80))
        stub.addService("monitoring", "ok-name", chart, listOf("Not A Port" to 80))
        stub.addService("monitoring", "fine", chart, listOf("http" to 80))
        answerProbes()

        detect()

        assertEquals(listOf("monitoring/fine:http"), probed())
    }

    @Test
    fun `a probe that is not a queryable Prometheus moves on to the next candidate`() {
        stub.addService("a-ns", "agent", chart, listOf("http" to 80))
        stub.addService("b-ns", "html", chart, listOf("http" to 80))
        stub.addService("c-ns", "matrix", chart, listOf("http" to 80))
        stub.addService("d-ns", "real", chart, listOf("http" to 80))
        answerProbes(
            "a-ns/agent:http" to { jsonResponse(422, """{"status":"error","errorType":"execution","error":"unavailable with Prometheus Agent"}""") },
            "b-ns/html:http" to { jsonResponse(200, "<html>login</html>") },
            "c-ns/matrix:http" to { jsonResponse(200, EMPTY_MATRIX) },
            "d-ns/real:http" to { jsonResponse(200, VECTOR_OK) },
        )

        val result = detect()

        assertEquals(DetectionState.Found(target("d-ns", "real", "http")), result)
        assertEquals(4, probed().size)
    }

    @Test
    fun `a 400, 401 or 404 answer rejects the candidate`() {
        stub.addService("a-ns", "p", chart, listOf("http" to 80))
        stub.addService("b-ns", "p", chart, listOf("http" to 80))
        stub.addService("c-ns", "p", chart, listOf("http" to 80))
        answerProbes(
            "a-ns/p:http" to { jsonResponse(400, "bad") },
            "b-ns/p:http" to { jsonResponse(401, "who") },
        )

        val result = detect()

        assertIs<DetectionState.NothingFound>(result)
        assertEquals(3, probed().size)
    }

    @Test
    fun `a probe the API server refuses with 403 still finds the candidate and stops there`() {
        stub.addService("a-ns", "p", chart, listOf("http" to 80))
        stub.addService("b-ns", "p", chart, listOf("http" to 80))
        answerProbes("a-ns/p:http" to { jsonResponse(403, K8S_FORBIDDEN) })

        val result = detect()

        assertEquals(DetectionState.Found(target("a-ns", "p", "http")), result)
        assertEquals(listOf("a-ns/p:http"), probed())
    }

    @Test
    fun `a probe that cannot be judged is skipped, and a search with nothing else is a failure, not nothing found`() {
        stub.addService("a-ns", "p", chart, listOf("http" to 80))
        stub.addService("b-ns", "p", chart, listOf("http" to 80))
        answerProbes("a-ns/p:http" to { jsonResponse(503, K8S_NO_ENDPOINTS) })

        // The second candidate is plainly not a Prometheus; the first could not be judged.
        val failed = detect()

        assertEquals(DetectionState.SearchFailed(clock.get()), failed)
        assertEquals(2, probed().size)

        // A later candidate that answers still wins.
        answerProbes("a-ns/p:http" to { jsonResponse(502, "bad gateway") }, "b-ns/p:http" to ::ok)
        stub.requests.clear()
        assertEquals(DetectionState.Found(target("b-ns", "p", "http")), detect())
    }

    @Test
    fun `a probe that times out is inconclusive`() {
        stub.addService("a-ns", "p", chart, listOf("http" to 80))
        answerProbes("a-ns/p:http" to { jsonResponse(200, SCALAR_OK).setBodyDelay(Duration.ofSeconds(6)) })

        val result = detect(probeTimeoutSec = 1)

        assertEquals(DetectionState.SearchFailed(clock.get()), result)
    }

    @Test
    fun `when every list is refused with 403 the cluster cannot be searched`() {
        stub.addService("monitoring", "prometheus-operated", operated, listOf("web" to 9090))
        stub.finders = { _, _ -> jsonResponse(403, K8S_FORBIDDEN) }

        val result = detect()

        assertEquals(DetectionState.CannotSearch(clock.get()), result)
        assertEquals(emptyList(), probed())
        assertEquals(4, stub.finderRequests.size)
    }

    @Test
    fun `a refusal on some lists is nothing found, because the others were answered`() {
        stub.finders = { request, answer -> if (request.path.startsWith("/api/v1/services")) jsonResponse(403, K8S_FORBIDDEN) else answer }

        val result = detect()

        assertEquals(DetectionState.NothingFound(clock.get()), result)
    }

    @Test
    fun `an empty cluster is nothing found`() {
        assertEquals(DetectionState.NothingFound(clock.get()), detect())
        assertEquals(4, stub.finderRequests.size)
    }

    @Test
    fun `one finder failing ends the search as failed even when another found a candidate`() {
        stub.addService("team-a", "prometheus-server", chart, listOf("http" to 80))
        stub.finders = { request, answer -> if ("operated-prometheus" in request.path) jsonResponse(500, """{"kind":"Status","code":500}""") else answer }

        val result = detect()

        assertEquals(DetectionState.SearchFailed(clock.get()), result)
        assertEquals(emptyList(), probed())
    }

    @Test
    fun `a closed client is a failed search, never nothing found`() {
        val client = stub.client()
        client.close()

        val result = detect(client = client)

        assertEquals(DetectionState.SearchFailed(clock.get()), result)
    }

    @Test
    fun `lists that outlive the budget are a failed search, and the call returns at the budget`() {
        stub.finders = { _, answer -> answer.setBodyDelay(Duration.ofSeconds(4)) }
        val startedAt = System.nanoTime()

        val result = detect(budgetMs = 300)

        assertEquals(DetectionState.SearchFailed(clock.get()), result)
        assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 3_000, "the search did not stop at its budget")
    }

    @Test
    fun `the four lists run in parallel under one budget`() {
        stub.finders = { _, answer -> answer.setBodyDelay(Duration.ofMillis(700)) }
        val startedAt = System.nanoTime()

        val result = detect(budgetMs = 3_000)

        assertIs<DetectionState.NothingFound>(result)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(tookMs < 2_000, "four 700 ms lists took $tookMs ms, so they ran one after the other")
    }
}
