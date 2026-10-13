package com.kubekubedashdash.util

import com.kubekubedashdash.models.ContainerResources
import com.kubekubedashdash.services.metricshistory.DetectionState
import com.kubekubedashdash.services.metricshistory.InClusterMetricsSource
import com.kubekubedashdash.services.metricshistory.InClusterTarget
import com.kubekubedashdash.services.metricshistory.PodRef
import com.kubekubedashdash.services.metricshistory.PodSeriesKind
import com.kubekubedashdash.services.metricshistory.PodSetMode
import com.kubekubedashdash.services.metricshistory.PodSetRef
import com.kubekubedashdash.services.metricshistory.PromQl
import com.kubekubedashdash.services.metricshistory.PrometheusDetector
import com.kubekubedashdash.services.metricshistory.PrometheusResponse
import com.kubekubedashdash.services.metricshistory.QueryWindow
import com.kubekubedashdash.services.metricshistory.ReleaseCheckSupport
import com.kubekubedashdash.services.metricshistory.ScopeKind
import com.kubekubedashdash.services.metricshistory.ScopeRef
import com.kubekubedashdash.services.metricshistory.Series
import com.kubekubedashdash.services.metricshistory.SeriesPoint
import com.kubekubedashdash.services.metricshistory.ServiceProxyAccessReview
import com.kubekubedashdash.services.metricshistory.ServiceProxyHttp
import com.kubekubedashdash.services.metricshistory.demoSeries
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodSpecBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSet
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetListBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobListBuilder
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetricsListBuilder
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
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demo's fake Prometheus, driven straight through [DemoPrometheusDispatcher.dispatch] with a
 * recording delegate (what is answered, and that a proxy path never reaches the delegate), and
 * through fabric8's real HTTP client against a mock server that wraps the CRUD dispatcher.
 */
class DemoPrometheusDispatcherTest {

    /**
     * Records every request it gets; answers the one known pod, 404 for other pods and a marker for the rest.
     * Once [replicaSets], [pods] or [podMetrics] is set it also answers those lists, the way the CRUD store does.
     * [jobs] works the same way.
     */
    private class RecordingDelegate :
        Dispatcher(),
        Resetable,
        CustomResourceAware {
        val seen = CopyOnWriteArrayList<String>()
        var shutdowns = 0
        var resets = 0
        val expectedContexts = CopyOnWriteArrayList<CustomResourceDefinitionContext>()
        var replicaSets: List<ReplicaSet>? = null
        var pods: List<Pod>? = null
        var jobs: List<Job>? = null
        var podMetrics: List<PodMetrics>? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            seen += "${request.method} $path"
            val replicaSetsNamespace = REPLICASETS_PATH.matchEntire(path)?.groupValues?.get(1)
            val podsNamespace = PODS_PATH.matchEntire(path)?.groupValues?.get(1)
            val jobsNamespace = JOBS_PATH.matchEntire(path)?.groupValues?.get(1)
            val metricsNamespace = POD_METRICS_PATH.matchEntire(path)?.groupValues?.get(1)
            return when {
                request.method == "GET" && path == ALL_POD_METRICS_PATH && podMetrics != null ->
                    MockResponse().setResponseCode(200).setBody(Serialization.asJson(PodMetricsListBuilder().withItems(podMetrics.orEmpty()).build()))

                request.method == "GET" && metricsNamespace != null && podMetrics != null ->
                    MockResponse().setResponseCode(200).setBody(Serialization.asJson(PodMetricsListBuilder().withItems(podMetrics.orEmpty().filter { it.metadata.namespace == metricsNamespace }).build()))

                request.method == "GET" && path == POD_PATH -> MockResponse().setResponseCode(200).setBody(Serialization.asJson(knownPod()))

                request.method == "GET" && replicaSetsNamespace != null && replicaSets != null ->
                    MockResponse().setResponseCode(200).setBody(Serialization.asJson(ReplicaSetListBuilder().withItems(replicaSets.orEmpty().filter { it.metadata.namespace == replicaSetsNamespace }).build()))

                request.method == "GET" && podsNamespace != null && pods != null ->
                    MockResponse().setResponseCode(200).setBody(Serialization.asJson(PodListBuilder().withItems(pods.orEmpty().filter { it.metadata.namespace == podsNamespace }).build()))

                request.method == "GET" && jobsNamespace != null && jobs != null ->
                    MockResponse().setResponseCode(200).setBody(Serialization.asJson(JobListBuilder().withItems(jobs.orEmpty().filter { it.metadata.namespace == jobsNamespace }).build()))

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

    // ── pod sets ────────────────────────────────────────────────────────────

    private val previous = "frontend-6d9f8c7b5"
    private val current = "frontend-7c8d9e0f1"

    private fun rangeQueryPath(query: String, w: QueryWindow = window) = proxyPath(
        "query_range",
        "query" to query,
        "start" to w.startEpochSec.toString(),
        "end" to w.endEpochSec.toString(),
        "step" to w.stepSec.toString(),
    )

    private fun podSetPath(kind: PodSeriesKind, replicaSets: List<String>, mode: PodSetMode, namespace: String = "team-a") = rangeQueryPath(PromQl.inClusterPodSetQuery(kind, PromQl.ofReplicaSets(namespace, replicaSets)!!, mode, window.stepSec))

    private fun seriesOf(response: MockResponse, nameLabel: String?): List<Series> {
        assertEquals(200, response.code(), response.text())
        return assertIs<PrometheusResponse.Parsed.Matrix>(PrometheusResponse.parseMatrix(response.text(), nameLabel)).series
    }

    private fun resultOf(response: MockResponse) = response.json().getValue("data").jsonObject.getValue("result").jsonArray

    // Two revisions of one Deployment: the old one has no pod left, the current one has two.
    private fun seedRollout() {
        delegate.replicaSets = listOf(replicaSet(previous, OLD_CREATED), replicaSet(current, NEW_CREATED))
        delegate.pods = listOf(livePod("$current-abc12", FIRST_POD_CREATED), livePod("$current-def34", SECOND_POD_CREATED))
    }

    private fun combine(kind: PodSeriesKind, series: List<List<SeriesPoint>>): List<SeriesPoint> = series.flatten().groupBy({ it.epochSec }, { it.value }).toSortedMap()
        .map { (t, values) -> SeriesPoint(t, if (kind == PodSeriesKind.Throttling) values.average() else values.sum()) }

    // What a pod of the rollout fixture adds up to at the window's timestamps inside [from, to].
    private fun expected(kind: PodSeriesKind, podName: String, from: Long, to: Long = window.endEpochSec): List<SeriesPoint> = combine(
        kind,
        demoSeries(kind, PodRef("team-a", podName, rolloutContainers), window).map { s -> s.points.filter { it.epochSec in from..to } },
    )

    @Test
    fun `a pod set answers a line per pod, the live ones from their creation and a revision without pods as one virtual pod`() {
        seedRollout()
        for (kind in PodSeriesKind.entries) {
            val series = seriesOf(get(podSetPath(kind, listOf(previous, current), PodSetMode.PerPod)), "pod")

            assertEquals(listOf("$previous-old01", "$current-abc12", "$current-def34"), series.map { it.name }, "$kind")
            assertEquals(expected(kind, "$previous-old01", OLD_CREATED, NEW_CREATED), series[0].points, "$kind")
            assertEquals(expected(kind, "$current-abc12", FIRST_POD_CREATED), series[1].points, "$kind")
            assertEquals(expected(kind, "$current-def34", SECOND_POD_CREATED), series[2].points, "$kind")
        }
        assertEquals(
            listOf("GET /apis/apps/v1/namespaces/team-a/replicasets", "GET /api/v1/namespaces/team-a/pods"),
            delegate.seen.take(2),
            "the first query lists the ReplicaSets, then the pods",
        )
    }

    @Test
    fun `the virtual pod of an old revision lives from its ReplicaSet's creation to the next one's`() {
        seedRollout()

        val old = seriesOf(get(podSetPath(PodSeriesKind.Cpu, listOf(previous, current), PodSetMode.PerPod)), "pod").single { it.name == "$previous-old01" }

        assertTrue(old.points.first().epochSec >= OLD_CREATED, "starts before its ReplicaSet exists")
        assertTrue(old.points.first().epochSec < OLD_CREATED + window.stepSec)
        assertTrue(old.points.last().epochSec <= NEW_CREATED, "runs past the next revision")
        assertTrue(old.points.last().epochSec > NEW_CREATED - window.stepSec)
    }

    @Test
    fun `a live pod of an old revision is clipped at the next revision too, and then needs no virtual pod`() {
        delegate.replicaSets = listOf(replicaSet(previous, OLD_CREATED), replicaSet(current, NEW_CREATED))
        delegate.pods = listOf(livePod("$previous-zzz99", OLD_CREATED + 10), livePod("$current-abc12", FIRST_POD_CREATED))

        for (kind in listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory)) {
            val series = seriesOf(get(podSetPath(kind, listOf(previous, current), PodSetMode.PerPod)), "pod")

            assertEquals(listOf("$previous-zzz99", "$current-abc12"), series.map { it.name }, "$kind")
            assertEquals(expected(kind, "$previous-zzz99", OLD_CREATED + 10, NEW_CREATED), series[0].points, "$kind")
            assertTrue(series[0].points.last().epochSec <= NEW_CREATED, "$kind")
        }
    }

    @Test
    fun `the order the store lists ReplicaSets and pods in does not change the answer`() {
        seedRollout()
        for (mode in PodSetMode.entries) {
            val path = podSetPath(PodSeriesKind.Cpu, listOf(previous, current), mode)
            delegate.replicaSets = listOf(replicaSet(previous, OLD_CREATED), replicaSet(current, NEW_CREATED))
            val inOrder = get(path).text()

            delegate.replicaSets = delegate.replicaSets.orEmpty().reversed()
            delegate.pods = delegate.pods.orEmpty().reversed()

            assertEquals(inOrder, get(path).text(), "$mode")
        }
    }

    @Test
    fun `the total is one line without a pod label that adds the pods present at each timestamp`() {
        seedRollout()
        for (kind in PodSeriesKind.entries) {
            val response = get(podSetPath(kind, listOf(previous, current), PodSetMode.Total))

            val entries = resultOf(response)
            assertEquals(1, entries.size, "$kind")
            assertEquals(JsonObject(emptyMap()), entries.single().jsonObject.getValue("metric"), "$kind")
            val line = seriesOf(response, null).single().points
            val parts = listOf(
                expected(kind, "$previous-old01", OLD_CREATED, NEW_CREATED),
                expected(kind, "$current-abc12", FIRST_POD_CREATED),
                expected(kind, "$current-def34", SECOND_POD_CREATED),
            )
            assertEquals(combine(kind, parts), line, "$kind")
            // One pod before the second one exists, two after.
            val between = line.first { it.epochSec in (SECOND_POD_CREATED - window.stepSec)..(SECOND_POD_CREATED - 1) }
            assertEquals(parts[1].first { it.epochSec == between.epochSec }.value, between.value, "$kind")
        }
    }

    @Test
    fun `throttling is the mean of a pod's containers, not their sum`() {
        seedRollout()
        // Long enough for the throttling wave to cross its threshold.
        val wide = QueryWindow(startEpochSec = 1_000, endEpochSec = 7_000, stepSec = 30)
        val perContainer = demoSeries(PodSeriesKind.Throttling, PodRef("team-a", "$current-abc12", rolloutContainers), wide)
        assertEquals(2, perContainer.size, "both containers of the fixture have a CPU limit")
        val mean = perContainer[0].points.zip(perContainer[1].points)
            .filter { (a, _) -> a.epochSec >= FIRST_POD_CREATED }
            .map { (a, b) -> SeriesPoint(a.epochSec, (a.value + b.value) / 2) }
        assertTrue(mean.any { it.value > 0 }, "the fixture must throttle somewhere for the mean to mean anything")
        val query = PromQl.inClusterPodSetQuery(PodSeriesKind.Throttling, PromQl.ofReplicaSets("team-a", listOf(previous, current))!!, PodSetMode.PerPod, wide.stepSec)

        val pod = seriesOf(get(rangeQueryPath(query, wide)), "pod").single { it.name == "$current-abc12" }

        assertEquals(mean, pod.points)
    }

    @Test
    fun `a ReplicaSet name with a dot is escaped in the query and read back from it`() {
        val dotted = "frontend.v2-7c8d9e0f1"
        delegate.replicaSets = listOf(replicaSet(dotted, NEW_CREATED))
        delegate.pods = listOf(livePod("$dotted-abc12", FIRST_POD_CREATED))
        val query = PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PromQl.ofReplicaSets("team-a", listOf(dotted))!!, PodSetMode.PerPod, window.stepSec)
        assertTrue("""frontend\\.v2-7c8d9e0f1-""" in query, query)

        val series = seriesOf(get(rangeQueryPath(query)), "pod")

        assertEquals(listOf("$dotted-abc12"), series.map { it.name })
        assertEquals(expected(PodSeriesKind.Cpu, "$dotted-abc12", FIRST_POD_CREATED), series.single().points)
    }

    @Test
    fun `a ReplicaSet name too long for its pods' names is found among the listed ones`() {
        val long = "frontend-" + "a".repeat(51)
        val prefix = PromQl.podPrefix(long)
        assertEquals(60, long.length)
        assertEquals(58, prefix.length)
        assertFalse(prefix.endsWith("-"), "the prefix must be cut short, with no dash to strip")
        delegate.replicaSets = listOf(replicaSet(previous, OLD_CREATED), replicaSet(long, NEW_CREATED))
        delegate.pods = listOf(livePod("${prefix}abc12", FIRST_POD_CREATED))

        val series = seriesOf(get(podSetPath(PodSeriesKind.Memory, listOf(previous, long), PodSetMode.PerPod)), "pod")

        assertEquals(listOf("$previous-old01", "${prefix}abc12"), series.map { it.name })
        assertEquals(expected(PodSeriesKind.Memory, "${prefix}abc12", FIRST_POD_CREATED), series[1].points)
        assertTrue("GET /apis/apps/v1/namespaces/team-a/replicasets" in delegate.seen)
    }

    @Test
    fun `a cut-short prefix that names no single listed ReplicaSet is bad_data`() {
        val long = "frontend-" + "a".repeat(51)
        val twinA = "frontend-" + "a".repeat(49) + "b1"
        val twinB = "frontend-" + "a".repeat(49) + "c1"
        assertEquals(PromQl.podPrefix(twinA), PromQl.podPrefix(twinB))
        val cases = mapOf(
            "not listed" to (listOf(replicaSet(previous, OLD_CREATED)) to long),
            "two listed with the same prefix" to (listOf(replicaSet(twinA, OLD_CREATED), replicaSet(twinB, NEW_CREATED)) to twinA),
        )
        for ((label, case) in cases) {
            delegate.replicaSets = case.first

            val response = get(podSetPath(PodSeriesKind.Cpu, listOf(case.second), PodSetMode.PerPod))

            assertEquals(400, response.code(), label)
            assertEquals("bad_data", errorType(response), label)
        }
    }

    @Test
    fun `a pod set with nothing to draw answers an empty matrix, not an error`() {
        val cases = mapOf<String, () -> Unit>(
            "the store answers no list" to { delegate.replicaSets = null },
            "no ReplicaSets" to { delegate.replicaSets = emptyList() },
            "only another namespace's" to { delegate.replicaSets = listOf(replicaSet(current, NEW_CREATED, namespace = "team-b")) },
            "only other ReplicaSets" to { delegate.replicaSets = listOf(replicaSet("frontend-1a2b3c4d5", NEW_CREATED)) },
            "created after the window" to { delegate.replicaSets = listOf(replicaSet(current, window.endEpochSec + 100)) },
            "pods but no ReplicaSet" to {
                delegate.replicaSets = emptyList()
                delegate.pods = listOf(livePod("$current-abc12", FIRST_POD_CREATED))
            },
        )
        for ((label, setup) in cases) {
            delegate.replicaSets = null
            delegate.pods = null
            setup()
            for (mode in PodSetMode.entries) {
                val response = get(podSetPath(PodSeriesKind.Cpu, listOf(current), mode))

                assertEquals(200, response.code(), "$label $mode")
                assertEquals(0, resultOf(response).size, "$label $mode")
            }
        }
    }

    @Test
    fun `a pod set query that is not the exact form the client writes is bad_data and asks the delegate nothing`() {
        val ref = PromQl.ofReplicaSets("team-a", listOf(previous, current))!!
        val good = PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, ref, PodSetMode.PerPod, window.stepSec)
        val alternation = "($previous-|$current-)"
        assertTrue(alternation in good, good)
        val queries = listOf(
            // The names are sorted on the way out.
            good.replace(alternation, "($current-|$previous-)"),
            // The right form with the wrong rate window for this step.
            PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, ref, PodSetMode.PerPod, 600),
            good.replace("sum by (pod)", "max by (pod)"),
            good.replace("[a-z0-9]{5}", "[a-z0-9]{4}"),
            // A namespace and prefixes that are not Kubernetes names.
            PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PodSetRef("Team-A", ref.pattern), PodSetMode.PerPod, window.stepSec),
            PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PodSetRef("team-a/x", ref.pattern), PodSetMode.PerPod, window.stepSec),
            good.replace("$previous-|", "Frontend-6d9f8c7b5-|"),
            good.replace("$previous-|", "$previous/-|"),
            good.replace("$previous-|", """frontend\\x-|"""),
            good.replace(alternation, "($previous-|)"),
            good.replace(alternation, "()"),
            // Right shape, but not what the client writes for this set.
            good.replace(alternation, "($previous-|$previous-|$current-)"),
        )
        for (query in queries) {
            val response = get(rangeQueryPath(query))

            assertEquals(400, response.code(), query)
            assertEquals("bad_data", errorType(response), query)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    // ── Job, CronJob and Spark pod sets ─────────────────────────────────────

    private val jobName = "db-vacuum-28000002"

    private fun batchPath(kind: PodSeriesKind, ref: PodSetRef, mode: PodSetMode) = rangeQueryPath(PromQl.inClusterPodSetQuery(kind, ref, mode, window.stepSec))

    private fun podsOf(ref: PodSetRef, kind: PodSeriesKind = PodSeriesKind.Cpu): List<Series> = seriesOf(get(batchPath(kind, ref, PodSetMode.PerPod)), "pod")

    @Test
    fun `a Job's pods are found when its name is a listed Job, each from its creation to its last container's end`() {
        delegate.replicaSets = emptyList()
        delegate.jobs = listOf(jobNamed(jobName), jobNamed("db-vacuum-28000003"))
        delegate.pods = listOf(
            endedPod("$jobName-k4m7p", 1_100, 1_400),
            livePod("$jobName-r2t5v", 1_500),
            endedPod("db-vacuum-28000003-b4c5d", 1_200, 1_300),
            livePod("$jobName-extra-abcde", 1_100),
        )
        val ref = PromQl.ofJob("team-a", jobName, indexed = false)!!

        for (kind in PodSeriesKind.entries) {
            val series = podsOf(ref, kind)

            assertEquals(listOf("$jobName-k4m7p", "$jobName-r2t5v"), series.map { it.name }, "$kind")
            assertEquals(expected(kind, "$jobName-k4m7p", 1_100, 1_400), series[0].points, "$kind")
            assertEquals(expected(kind, "$jobName-r2t5v", 1_500), series[1].points, "$kind")
        }
        assertEquals(
            listOf("GET /apis/apps/v1/namespaces/team-a/replicasets", "GET /apis/batch/v1/namespaces/team-a/jobs", "GET /api/v1/namespaces/team-a/pods"),
            delegate.seen.take(3),
        )
    }

    @Test
    fun `the total of a Job adds its pods where they overlap`() {
        delegate.replicaSets = emptyList()
        delegate.jobs = listOf(jobNamed(jobName))
        delegate.pods = listOf(endedPod("$jobName-k4m7p", 1_100, 1_600), endedPod("$jobName-r2t5v", 1_400, 1_800))
        val ref = PromQl.ofJob("team-a", jobName, indexed = false)!!

        val line = seriesOf(get(batchPath(PodSeriesKind.Memory, ref, PodSetMode.Total)), null).single().points

        val parts = listOf(expected(PodSeriesKind.Memory, "$jobName-k4m7p", 1_100, 1_600), expected(PodSeriesKind.Memory, "$jobName-r2t5v", 1_400, 1_800))
        assertEquals(combine(PodSeriesKind.Memory, parts), line)
        assertTrue(line.any { it.epochSec in 1_400..1_600 }, "the pods overlap in this fixture")
    }

    @Test
    fun `the Jobs are listed only when an alternative is not a listed ReplicaSet`() {
        seedRollout()

        podsOf(PromQl.ofReplicaSets("team-a", listOf(previous, current))!!)

        assertFalse(delegate.seen.any { "/jobs" in it }, delegate.seen.toString())
    }

    @Test
    fun `an alternative that is neither a listed ReplicaSet nor a listed Job draws nothing and lists no pods`() {
        val cases = mapOf(
            "the store answers no list" to null,
            "another Job" to listOf(jobNamed("db-vacuum-28000003")),
            "the Job of another namespace" to listOf(jobNamed(jobName, namespace = "team-b")),
        )
        for ((label, jobs) in cases) {
            delegate.seen.clear()
            delegate.replicaSets = emptyList()
            delegate.jobs = jobs
            delegate.pods = listOf(livePod("$jobName-k4m7p", 1_100))
            val ref = PromQl.ofJob("team-a", jobName, indexed = false)!!

            for (mode in PodSetMode.entries) {
                val response = get(batchPath(PodSeriesKind.Cpu, ref, mode))

                assertEquals(200, response.code(), "$label $mode")
                assertEquals(0, resultOf(response).size, "$label $mode")
            }
            assertFalse(delegate.seen.any { it.endsWith("/pods") }, label)
        }
    }

    @Test
    fun `an indexed Job's pods are selected by index, and a name too long to keep whole at every index width`() {
        val short = PromQl.ofJob("team-a", "db-vacuum", indexed = true)!!
        val long = "nightly-backup-" + "a".repeat(40)
        assertEquals(55, long.length)
        val cut = PromQl.ofJob("team-a", long, indexed = true)!!
        delegate.pods = listOf(
            livePod("db-vacuum-0-abcde", 1_100),
            livePod("db-vacuum-12-fghjk", 1_200),
            livePod("db-vacuum-abcde", 1_100),
            livePod("db-vacuum-x-abcde", 1_100),
            livePod("${long.take(55)}-3-abcde", 1_300),
            livePod("${long.take(54)}-12-fghjk", 1_300),
            livePod("${long.take(55)}-abcde", 1_300),
        )

        assertEquals(listOf("db-vacuum-0-abcde", "db-vacuum-12-fghjk"), podsOf(short).map { it.name })
        assertEquals(listOf("${long.take(54)}-12-fghjk", "${long.take(55)}-3-abcde"), podsOf(cut).map { it.name })
        assertEquals(listOf("GET /api/v1/namespaces/team-a/pods", "GET /api/v1/namespaces/team-a/pods"), delegate.seen.toList(), "a pattern of this kind needs no other list")
    }

    @Test
    fun `a CronJob's pods are those of every run, and its total adds them`() {
        val ref = PromQl.ofCronJob("team-a", "nightly-backup", emptyList())!!
        delegate.pods = listOf(
            endedPod("nightly-backup-28000001-abcde", 1_050, 1_500),
            endedPod("nightly-backup-28000002-fghjk", 1_300, 1_700),
            livePod("nightly-backup-28000003-mnpqr", 1_800),
            livePod("nightly-backup-2800000-stvwx", 1_100),
            livePod("nightly-backup-28000001-abc", 1_100),
            livePod("log-rotation-28000001-abcde", 1_100),
        )
        val runs = listOf(
            Triple("nightly-backup-28000001-abcde", 1_050L, 1_500L),
            Triple("nightly-backup-28000002-fghjk", 1_300L, 1_700L),
            Triple("nightly-backup-28000003-mnpqr", 1_800L, window.endEpochSec),
        )

        for (kind in PodSeriesKind.entries) {
            val series = podsOf(ref, kind)

            assertEquals(runs.map { it.first }, series.map { it.name }, "$kind")
            runs.forEachIndexed { i, (name, from, to) -> assertEquals(expected(kind, name, from, to), series[i].points, "$kind $name") }
        }
        val total = seriesOf(get(batchPath(PodSeriesKind.Cpu, ref, PodSetMode.Total)), null).single().points
        assertEquals(combine(PodSeriesKind.Cpu, runs.map { (name, from, to) -> expected(PodSeriesKind.Cpu, name, from, to) }), total)
        assertEquals(listOf("GET /api/v1/namespaces/team-a/pods"), delegate.seen.distinct())
    }

    @Test
    fun `a CronJob's pattern with a run started by hand selects that run's pods too`() {
        val ref = PromQl.ofCronJob("team-a", "nightly-backup", listOf("nightly-backup-28000001", "log-rotation", "log-rotation.v2"))!!
        assertTrue("|log-rotation-|" in ref.pattern, ref.pattern)
        assertNotNull(DemoPrometheusDispatcher.compilePattern(ref.pattern))
        delegate.pods = listOf(
            endedPod("nightly-backup-28000001-abcde", 1_050, 1_500),
            livePod("nightly-backup-28000003-mnpqr", 1_800),
            endedPod("log-rotation-fghjk", 1_300, 1_700),
            endedPod("log-rotation.v2-stvwx", 1_350, 1_750),
            livePod("log-rotation-extra-fghjk", 1_100),
            livePod("db-vacuum-fghjk", 1_100),
        )

        for (kind in PodSeriesKind.entries) {
            val series = podsOf(ref, kind)

            assertEquals(
                listOf("log-rotation-fghjk", "log-rotation.v2-stvwx", "nightly-backup-28000001-abcde", "nightly-backup-28000003-mnpqr"),
                series.map { it.name },
                "$kind",
            )
            assertEquals(expected(kind, "log-rotation-fghjk", 1_300, 1_700), series[0].points, "$kind")
        }
        assertEquals(listOf("GET /api/v1/namespaces/team-a/pods"), delegate.seen.distinct())
    }

    @Test
    fun `a CronJob's pattern refuses an alternative that is not a pod prefix`() {
        val good = PromQl.ofCronJob("team-a", "nightly-backup", listOf("log-rotation"))!!.pattern
        val patterns = listOf(
            good.replace("|log-rotation-", "|"),
            good.replace("|log-rotation-", "|.*"),
            good.replace("|log-rotation-", "|log-rotation-[0-9]+"),
            good.replace("|log-rotation-", "|Log-"),
            """(log-rotation-|nightly-backup-[0-9]{8}-([0-9]+-)?)[a-z0-9]{5}""",
            good.replace("(nightly-backup-[0-9]{8}-([0-9]+-)?|", "(nightly-backup-[0-9]{8}-|"),
        )
        for (pattern in patterns) {
            val response = get(rangeQueryPath(PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PodSetRef("team-a", pattern), PodSetMode.PerPod, window.stepSec)))

            assertEquals(400, response.code(), pattern)
            assertEquals("bad_data", errorType(response), pattern)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `a Spark app's pods are selected by their exact names`() {
        val ref = PromQl.ofSparkPods("team-a", listOf("spark-etl-nightly-driver"), listOf("spark-etl-nightly-exec-2", "spark-etl-nightly-exec-1"))!!
        delegate.pods = listOf(
            endedPod("spark-etl-nightly-driver", 1_100, 1_700),
            endedPod("spark-etl-nightly-exec-1", 1_150, 1_700),
            endedPod("spark-etl-nightly-exec-2", 1_150, 1_650),
            livePod("spark-etl-nightly-exec-3", 1_150),
            livePod("spark-etl-nightly-driver-x", 1_100),
        )

        for (kind in PodSeriesKind.entries) {
            val series = podsOf(ref, kind)

            assertEquals(listOf("spark-etl-nightly-driver", "spark-etl-nightly-exec-1", "spark-etl-nightly-exec-2"), series.map { it.name }, "$kind")
            assertEquals(expected(kind, "spark-etl-nightly-exec-2", 1_150, 1_650), series[2].points, "$kind")
        }
    }

    @Test
    fun `the executors of a Spark app with more than 60 pods are selected by their stem`() {
        val stem = "spark-etl-nightly-1a2b3c4d5e6f7890"
        val executors = (1..70).map { "$stem-exec-$it" }
        val ref = PromQl.ofSparkPods("team-a", listOf("spark-etl-nightly-driver"), executors)!!
        assertTrue("$stem-exec-[0-9]+" in ref.pattern, ref.pattern)
        delegate.pods = listOf(livePod("spark-etl-nightly-driver", 1_100)) + executors.map { livePod(it, 1_200) } +
            listOf(livePod("$stem-exec-x", 1_200), livePod("spark-failed-job-1a2b3c4d5e6f7890-exec-1", 1_200), livePod("$stem-exec-1-extra", 1_200))

        val series = podsOf(ref)

        assertEquals((listOf("spark-etl-nightly-driver") + executors).sorted(), series.map { it.name })
        assertEquals(expected(PodSeriesKind.Cpu, "$stem-exec-70", 1_200), series.single { it.name == "$stem-exec-70" }.points)
    }

    @Test
    fun `the longest pattern the client writes is answered`() {
        val names = (1..80).map { "spark-report-hourly-1760000000000000000-exec-$it" }
        val ref = PromQl.ofExactPods("team-a", names)!!
        assertTrue(PromQl.isValidPodSet(ref))
        assertTrue(ref.pattern.length in 3_500..4_000, "${ref.pattern.length} characters")
        delegate.pods = listOf(livePod(names[0], 1_100), livePod(names[79], 1_100), livePod("spark-report-hourly-1760000000000000000-exec-81", 1_100))

        val series = podsOf(ref)

        assertEquals(listOf(names[0], names[79]).sorted(), series.map { it.name })
    }

    @Test
    fun `a namespace's pods are every pod the store lists for it, a line each, and its total adds them`() {
        delegate.pods = listOf(
            livePod("web-1", 1_200),
            endedPod("web-0", 1_100, 1_600),
            livePod("web-0", 1_100, namespace = "team-b"),
        )
        val ref = PromQl.ofNamespace("team-a")!!

        for (kind in PodSeriesKind.entries) {
            val series = podsOf(ref, kind)

            assertEquals(listOf("web-0", "web-1"), series.map { it.name }, "$kind")
            assertEquals(expected(kind, "web-0", 1_100, 1_600), series[0].points, "$kind")
            assertEquals(expected(kind, "web-1", 1_200), series[1].points, "$kind")

            val total = seriesOf(get(batchPath(kind, ref, PodSetMode.Total)), null).single().points
            assertEquals(combine(kind, listOf(expected(kind, "web-0", 1_100, 1_600), expected(kind, "web-1", 1_200))), total, "$kind")
        }
        assertEquals(listOf("GET /api/v1/namespaces/team-a/pods"), delegate.seen.distinct())
    }

    @Test
    fun `a namespace without a pod answers an empty matrix`() {
        delegate.pods = listOf(livePod("web-0", 1_100, namespace = "team-b"))
        val ref = PromQl.ofNamespace("team-a")!!

        for (mode in PodSetMode.entries) {
            assertEquals(0, resultOf(get(batchPath(PodSeriesKind.Cpu, ref, mode))).size, "$mode")
        }
    }

    @Test
    fun `a pattern that only resembles a namespace's is bad_data and asks the delegate nothing`() {
        for (pattern in listOf(".+x", "x.+", "(.+)", ".++", ".+?", ".+|web-0", "..+", ".+.+")) {
            val response = get(batchPath(PodSeriesKind.Cpu, PodSetRef("team-a", pattern), PodSetMode.PerPod))

            assertEquals(400, response.code(), pattern)
            assertEquals("bad_data", errorType(response), pattern)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    private fun factoryPatterns(): Map<String, PodSetRef> = mapOf(
        "ReplicaSets" to PromQl.ofReplicaSets("team-a", listOf(previous, "frontend.v2-7c8d9e0f1"))!!,
        "a Job" to PromQl.ofJob("team-a", jobName, indexed = false)!!,
        "a Job with a dot" to PromQl.ofJob("team-a", "db.vacuum-28000002", indexed = false)!!,
        "an indexed Job" to PromQl.ofJob("team-a", "db-vacuum", indexed = true)!!,
        "an indexed Job with a long name" to PromQl.ofJob("team-a", "nightly-backup-" + "a".repeat(40), indexed = true)!!,
        "a CronJob" to PromQl.ofCronJob("team-a", "nightly-backup", emptyList())!!,
        "exact pods" to PromQl.ofExactPods("team-a", listOf("web-0", "web.1", "spark-etl-nightly-exec-1"))!!,
        "Spark pods by stem" to PromQl.ofSparkPods("team-a", listOf("spark-etl-nightly-driver"), (1..70).map { "spark-etl-nightly-1a2b3c4d5e6f7890-exec-$it" })!!,
        "a Spark driver" to PromQl.ofExactPods("team-a", listOf("spark-etl-nightly-driver"))!!,
        "Spark executors by stem" to PromQl.ofSparkExecutors("team-a", (1..70).map { "spark-etl-nightly-1a2b3c4d5e6f7890-exec-$it" })!!,
        "a namespace" to PromQl.ofNamespace("team-a")!!,
    )

    @Test
    fun `every pattern a factory writes is answered, for every kind and mode`() {
        for ((label, ref) in factoryPatterns()) {
            for (kind in PodSeriesKind.entries) {
                for (mode in PodSetMode.entries) {
                    val response = get(batchPath(kind, ref, mode))

                    assertEquals(200, response.code(), "$label $kind $mode: ${response.text()}")
                    assertEquals(0, resultOf(response).size, "$label $kind $mode: the store has no pods")
                }
            }
        }
    }

    @Test
    fun `a pattern the client does not write is bad_data and asks the delegate nothing`() {
        val patterns = listOf(
            ".*",
            "(web-0|.*)",
            "(a)[a-z0-9]{5}x",
            "((a))",
            "(a-[0-9]{7}-)[a-z0-9]{5}",
            "(a-[0-9]*-)[a-z0-9]{5}",
            "(a-[0-9]{8}-)[a-z0-9]{5}",
            "(a-[0-9]{7}-|b-[0-9]{2}-)[a-z0-9]{5}",
            "(a-[0-9]+-|b-[0-9]+-)[a-z0-9]{5}",
            "(a-exec-[0-9]*)",
            "(a-exec-[0-9]+-x)",
            "(a|)",
            "(a-[0-9]{1}-|)[a-z0-9]{5}",
            "(a--b)(c)",
        )
        for (pattern in patterns) {
            val response = get(rangeQueryPath(PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PodSetRef("team-a", pattern), PodSetMode.PerPod, window.stepSec)))

            assertEquals(400, response.code(), pattern)
            assertEquals("bad_data", errorType(response), pattern)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `a string literal that does not close, or an escape the client does not write, is bad_data`() {
        val good = PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, PromQl.ofExactPods("team-a", listOf("web-0"))!!, PodSetMode.PerPod, window.stepSec)
        val queries = listOf(
            good.replace("(web-0)\"", "(web-0)\\\""),
            good.replace("(web-0)", "(web-0)\\"),
            good.replace("(web-0)", "(web\\x-0)"),
            "namespace=\"team-a\",pod=~\"" + "a".repeat(5_000) + "\\",
        )
        for (query in queries) {
            val response = get(rangeQueryPath(query))

            assertEquals(400, response.code(), query)
            assertEquals("bad_data", errorType(response), query)
        }
        assertEquals(emptyList(), delegate.seen.toList())
    }

    @Test
    fun `java regex compiles every pattern a factory writes, and a bad one is no regex`() {
        for ((label, ref) in factoryPatterns()) {
            assertNotNull(DemoPrometheusDispatcher.compilePattern(ref.pattern), label)
        }
        // The grammars admit no pattern that does not compile, so the refusal of one is only reachable through this function.
        assertNull(DemoPrometheusDispatcher.compilePattern("(web-0"))
        assertNull(DemoPrometheusDispatcher.compilePattern("web-0)"))
        assertNull(DemoPrometheusDispatcher.compilePattern("a{2,1}"))
    }

    // ── scopes ──────────────────────────────────────────────────────────────

    private fun scopePath(kind: ScopeKind, namespaces: Set<String>) = rangeQueryPath(PromQl.inClusterScopeQuery(kind, ScopeRef(namespaces), window.stepSec))

    private fun scopeLine(kind: ScopeKind, namespaces: Set<String>): List<SeriesPoint> = seriesOf(get(scopePath(kind, namespaces)), null).single().points

    private val mib = 1024.0 * 1024

    // CPU 0.4 cores and memory 520 MiB in team-a, 1.5 cores and 1 GiB in team-b.
    private fun seedPodMetrics() {
        val m = 1024L * 1024
        delegate.podMetrics = listOf(
            MockClusterProvider.buildPodMetrics("web-0", "team-a", listOf(Triple("app", 250L, 300 * m), Triple("sidecar", 50L, 20 * m))),
            MockClusterProvider.buildPodMetrics("web-1", "team-a", listOf(Triple("app", 100L, 200 * m))),
            MockClusterProvider.buildPodMetrics("web-0", "team-b", listOf(Triple("app", 1_500L, 1_024 * m))),
        )
    }

    private fun expectedNow(kind: ScopeKind, namespaces: Set<String>): Double {
        val cpu = mapOf("team-a" to 0.4, "team-b" to 1.5)
        val memory = mapOf("team-a" to 520 * mib, "team-b" to 1_024 * mib)
        val byNamespace = if (kind == ScopeKind.CpuCores) cpu else memory
        return namespaces.ifEmpty { byNamespace.keys }.sumOf { byNamespace.getValue(it) }
    }

    @Test
    fun `a scope answers one line without labels that ends on its PodMetrics sum, the same every time`() {
        seedPodMetrics()
        val pointCount = (window.endEpochSec - window.startEpochSec) / window.stepSec + 1
        for (kind in ScopeKind.entries) {
            for (namespaces in listOf(emptySet(), setOf("team-a"), setOf("team-b"), setOf("team-a", "team-b"))) {
                val response = get(scopePath(kind, namespaces))

                val entry = resultOf(response).single().jsonObject
                assertEquals(JsonObject(emptyMap()), entry.getValue("metric"), "$kind $namespaces")
                val points = seriesOf(response, null).single().points
                assertEquals(pointCount, points.size.toLong(), "$kind $namespaces")
                assertEquals(window.startEpochSec, points.first().epochSec, "$kind $namespaces")
                assertEquals(window.endEpochSec, points.last().epochSec, "$kind $namespaces")
                assertTrue(points.all { it.value > 0 }, "$kind $namespaces")
                val now = expectedNow(kind, namespaces)
                assertEquals(now, points.last().value, 1e-9 * now, "$kind $namespaces: the right edge is what the gauges add up")
                assertEquals(response.text(), get(scopePath(kind, namespaces)).text(), "$kind $namespaces: the same request must give the same body")
            }
        }
        assertEquals(
            setOf(
                "GET /apis/metrics.k8s.io/v1beta1/pods",
                "GET /apis/metrics.k8s.io/v1beta1/namespaces/team-a/pods",
                "GET /apis/metrics.k8s.io/v1beta1/namespaces/team-b/pods",
            ),
            delegate.seen.toSet(),
            "a scope reads PodMetrics and nothing else",
        )
    }

    @Test
    fun `earlier points wander around the right edge, more for CPU than memory, not as a plain wave`() {
        seedPodMetrics()
        for (kind in ScopeKind.entries) {
            val line = scopeLine(kind, emptySet())
            val now = line.last().value
            val ratios = line.map { it.value / now }

            assertTrue(ratios.all { it in 0.6..1.5 }, "$kind: $ratios")
            val spread = ratios.max() - ratios.min()
            assertTrue(spread > if (kind == ScopeKind.CpuCores) 0.05 else 0.025, "$kind must move: $ratios")
            // A slow wave over this 15-minute window turns at most twice; the per-minute jitter turns it often.
            val turns = line.zipWithNext { a, b -> b.value - a.value }.zipWithNext().count { (a, b) -> a * b < 0 }
            assertTrue(turns >= 4, "$kind turns $turns times: $ratios")
        }
        val cpu = scopeLine(ScopeKind.CpuCores, emptySet()).map { it.value }
        val memory = scopeLine(ScopeKind.MemoryBytes, emptySet()).map { it.value }
        assertTrue((memory.max() - memory.min()) / memory.last() < (cpu.max() - cpu.min()) / cpu.last(), "memory moves less than CPU")
    }

    @Test
    fun `a scope without PodMetrics answers no series`() {
        for (metrics in listOf(null, emptyList<PodMetrics>())) {
            delegate.podMetrics = metrics
            for (kind in ScopeKind.entries) {
                val response = get(scopePath(kind, setOf("team-a")))

                assertEquals(200, response.code(), "$kind $metrics")
                assertEquals(0, resultOf(response).size, "$kind $metrics")
            }
        }
    }

    @Test
    fun `a scope query that is not the exact form the client writes is bad_data and asks the delegate nothing`() {
        val one = PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a")), window.stepSec)
        val two = PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(setOf("team-a", "team-b")), window.stepSec)
        val memory = PromQl.inClusterScopeQuery(ScopeKind.MemoryBytes, ScopeRef(setOf("team-a")), window.stepSec)
        val queries = listOf(
            """sum(rate(container_cpu_usage_seconds_total{namespace="team-a"}[240s]))""",
            one.replace("""namespace="team-a"""", """namespace=~"team-a""""),
            one.replace("""namespace="team-a"""", """namespace="Team-A""""),
            one.replace("container!=\"POD\"", "container!=\"x\""),
            one.replace("[240s]", "[120s]"),
            one.replace("sum(", "avg("),
            two.replace("team-a|team-b", "team-b|team-a"),
            two.replace("team-a|team-b", "team-a||team-b"),
            PromQl.inClusterScopeQuery(ScopeKind.CpuCores, ScopeRef(emptySet()), 600),
            memory.replace("max by (namespace, pod, container)", "max by (pod, container)"),
        )
        for (query in queries) {
            val response = get(rangeQueryPath(query))

            assertEquals(400, response.code(), query)
            assertEquals("bad_data", errorType(response), query)
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
        val REPLICASETS_PATH = Regex("/apis/apps/v1/namespaces/([^/]+)/replicasets")
        val PODS_PATH = Regex("/api/v1/namespaces/([^/]+)/pods")
        val JOBS_PATH = Regex("/apis/batch/v1/namespaces/([^/]+)/jobs")
        const val ALL_POD_METRICS_PATH = "/apis/metrics.k8s.io/v1beta1/pods"
        val POD_METRICS_PATH = Regex("/apis/metrics.k8s.io/v1beta1/namespaces/([^/]+)/pods")
        const val ACCESS_REVIEW_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews"

        // Not a code the dispatcher or the CRUD store ever answers: marks "the delegate answered".
        const val DELEGATE_MARKER_CODE = 299

        // Seconds inside the 1000..1900 test window: an old revision, its replacement, and the replacement's pods.
        const val OLD_CREATED = 1_010L
        const val NEW_CREATED = 1_455L
        const val FIRST_POD_CREATED = 1_500L
        const val SECOND_POD_CREATED = 1_600L

        // Two containers, both with a CPU limit, so a pod has throttling series to average.
        fun rolloutSpec(): PodSpec = PodSpecBuilder()
            .addNewContainer()
            .withName("app").withImage("example/app:1")
            .withNewResources().addToRequests("cpu", Quantity("100m")).addToLimits("cpu", Quantity("500m")).addToLimits("memory", Quantity("1Gi")).endResources()
            .endContainer()
            .addNewContainer()
            .withName("sidecar").withImage("example/sidecar:1")
            .withNewResources().addToRequests("cpu", Quantity("50m")).addToLimits("cpu", Quantity("200m")).addToLimits("memory", Quantity("256Mi")).endResources()
            .endContainer()
            .build()

        val rolloutContainers = ResourceMappers.containerResources(PodBuilder().withSpec(rolloutSpec()).build())

        fun replicaSet(name: String, createdSec: Long, namespace: String = "team-a"): ReplicaSet = ReplicaSetBuilder()
            .withNewMetadata().withNamespace(namespace).withName(name).withCreationTimestamp(Instant.ofEpochSecond(createdSec).toString()).endMetadata()
            .withNewSpec().withNewTemplate().withSpec(rolloutSpec()).endTemplate().endSpec()
            .build()

        fun livePod(name: String, createdSec: Long, namespace: String = "team-a"): Pod = PodBuilder()
            .withNewMetadata().withNamespace(namespace).withName(name).withCreationTimestamp(Instant.ofEpochSecond(createdSec).toString()).endMetadata()
            .withSpec(rolloutSpec())
            .build()

        fun jobNamed(name: String, namespace: String = "team-a"): Job = JobBuilder()
            .withNewMetadata().withNamespace(namespace).withName(name).endMetadata()
            .build()

        // A pod whose containers have ended: the app at [finishedSec], the sidecar five seconds before it.
        fun endedPod(name: String, createdSec: Long, finishedSec: Long, namespace: String = "team-a"): Pod = PodBuilder(livePod(name, createdSec, namespace))
            .withNewStatus()
            .withPhase("Succeeded")
            .addNewContainerStatus().withName("sidecar").withNewState().withNewTerminated().withExitCode(0).withFinishedAt(Instant.ofEpochSecond(finishedSec - 5).toString()).endTerminated().endState().endContainerStatus()
            .addNewContainerStatus().withName("app").withNewState().withNewTerminated().withExitCode(0).withFinishedAt(Instant.ofEpochSecond(finishedSec).toString()).endTerminated().endState().endContainerStatus()
            .endStatus()
            .build()

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
