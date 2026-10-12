package com.kubekubedashdash.services.metricshistory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.mockwebserver.http.MockResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-cluster source against a loopback fabric8 mock API server: the request it sends and every
 * way the answer can go, including what each failure leaves in the detection state and in the logs.
 */
class InClusterMetricsSourceTest {

    private var previousCloudClis: String? = null
    private val stub = ClusterStub()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = AtomicLong(1_000_000)
    private val detection = InClusterDetection(scope, clock::get)
    private val key = "example-context"
    private val target = InClusterTarget("monitoring", "prometheus-operated", "web", https = false, pathPrefix = "")
    private val pod = PodRef("team-a", "web-0")
    private val window = QueryWindow(startEpochSec = 1_000, endEpochSec = 1_120, stepSec = 30)
    private val reviews = CopyOnWriteArrayList<String>()
    private val live: KubernetesClient by lazy { stub.client() }

    @Volatile
    private var reviewAnswer: Boolean? = false

    @Volatile
    private var reviewFails = false

    @BeforeTest
    fun setUp() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
    }

    @AfterTest
    fun tearDown() {
        try {
            shutdownCleanly(scope, label = "InClusterMetricsSourceTest")
            stub.close("InClusterMetricsSourceTest")
        } finally {
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
    }

    private fun source(
        target: InClusterTarget = this.target,
        auto: Boolean = true,
        queryTimeoutSec: Long = 5,
        client: () -> KubernetesClient? = { live },
    ) = InClusterMetricsSource(
        target = target,
        auto = auto,
        clientProvider = { client() },
        detection = detection,
        key = key,
        accessReview = { namespace ->
            reviews += namespace
            if (reviewFails) error("review failed")
            reviewAnswer
        },
        nowMs = clock::get,
        queryTimeoutSec = queryTimeoutSec,
    )

    private fun answer(code: Int, body: String) {
        stub.proxy = { jsonResponse(code, body) }
    }

    private val oneSeries = matrixBody("app" to listOf(1_000L to "1.5", 1_030L to "2"))

    private suspend fun cpu(source: InClusterMetricsSource) = source.podSeries(PodSeriesKind.Cpu, pod, window)

    // ── the request and the 200 answer ──────────────────────────────────────────

    @Test
    fun `the label names the namespace and the service`() {
        assertEquals("Prometheus · monitoring/prometheus-operated", source().label)
    }

    @Test
    fun `a 200 matrix is one series per container, asked for with the in-cluster query on the proxy path`() = runBlocking<Unit> {
        answer(200, oneSeries)

        val result = cpu(source())

        assertEquals(SeriesResult.Ok(listOf(Series("app", listOf(SeriesPoint(1_000, 1.5), SeriesPoint(1_030, 2.0))))), result)
        val request = stub.proxyRequests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/api/v1/query_range",
            request.path.substringBefore('?'),
        )
        assertEquals(PromQl.inClusterPodQuery(PodSeriesKind.Cpu, pod, 30), request.param("query"))
        assertEquals("1000", request.param("start"))
        assertEquals("1120", request.param("end"))
        assertEquals("30", request.param("step"))
    }

    @Test
    fun `every kind sends its own query, and the network kinds read unlabelled series`() = runBlocking<Unit> {
        val source = source()
        for (kind in PodSeriesKind.entries) {
            stub.requests.clear()
            val labelled = kind != PodSeriesKind.NetworkRx && kind != PodSeriesKind.NetworkTx
            answer(200, matrixBody((if (labelled) "app" else null) to listOf(1_000L to "7")))

            val result = source.podSeries(kind, pod, window)

            assertEquals(SeriesResult.Ok(listOf(Series(if (labelled) "app" else "", listOf(SeriesPoint(1_000, 7.0))))), result, "$kind")
            assertEquals(PromQl.inClusterPodQuery(kind, pod, 30), stub.proxyRequests.single().param("query"), "$kind")
        }
    }

    @Test
    fun `a 200 error envelope is a rejected query, with the errorType only when it is a plain word`() = runBlocking<Unit> {
        answer(200, """{"status":"error","errorType":"bad_data","error":"parse error at char 5"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data"), cpu(source()))

        answer(200, """{"status":"error","errorType":"<b>SECRET-42</b>","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "unknown"), cpu(source()))
    }

    @Test
    fun `a 200 that is not a matrix is unreadable`() = runBlocking<Unit> {
        for (body in listOf("<html>hi</html>", SCALAR_OK, """{"status":"success"}""", "")) {
            answer(200, body)

            assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "unreadable response"), cpu(source()), body)
        }
    }

    // ── no container metrics ────────────────────────────────────────────────────

    private fun answerCpuEmptyAndProbe(probe: () -> MockResponse) {
        stub.proxy = { request ->
            if (queryParam(request.path, "query") == PromQl.inClusterProbeQuery()) probe() else jsonResponse(200, EMPTY_MATRIX)
        }
    }

    private fun probeRequests() = stub.proxyRequests.filter { it.param("query") == PromQl.inClusterProbeQuery() }

    @Test
    fun `an empty cpu answer from a Prometheus without container metrics is NoContainerMetrics, and the probe is asked once for five minutes`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(200, countBody("0")) }
        val source = source()

        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), cpu(source))
        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), source.podSeries(PodSeriesKind.Memory, pod, window))
        assertEquals(1, probeRequests().size, "the probe was asked twice within its cache time")
        assertEquals("/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/api/v1/query", probeRequests().single().path.substringBefore('?'))

        clock.addAndGet(5 * 60 * 1000L - 1)
        cpu(source)
        assertEquals(1, probeRequests().size)

        clock.addAndGet(1)
        cpu(source)
        assertEquals(2, probeRequests().size, "the answer outlived its five minutes")
    }

    @Test
    fun `an empty vector for the probe means no container metrics too`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(200, """{"status":"success","data":{"resultType":"vector","result":[]}}""") }

        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), cpu(source()))
    }

    @Test
    fun `an empty answer from a Prometheus that has container metrics is an empty chart, remembered as such`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(200, countBody("42")) }
        val source = source()

        assertEquals(SeriesResult.Ok(emptyList()), cpu(source))
        assertEquals(SeriesResult.Ok(emptyList()), cpu(source))

        assertEquals(1, probeRequests().size)
    }

    @Test
    fun `a probe that fails says nothing and is asked again`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(500, "SECRET-BODY-42") }
        val source = source()

        assertEquals(SeriesResult.Ok(emptyList()), cpu(source))
        assertEquals(SeriesResult.Ok(emptyList()), cpu(source))

        assertEquals(2, probeRequests().size, "a failed probe was kept")
    }

    @Test
    fun `network and throttling answers that are empty never probe`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(200, countBody("0")) }
        val source = source()

        for (kind in listOf(PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx, PodSeriesKind.Throttling)) {
            assertEquals(SeriesResult.Ok(emptyList()), source.podSeries(kind, pod, window), "$kind")
        }

        assertEquals(0, probeRequests().size)
    }

    // ── the status table ────────────────────────────────────────────────────────

    @Test
    fun `400 and 422 are a rejected query named by the errorType, else by the status`() = runBlocking<Unit> {
        answer(400, """{"status":"error","errorType":"bad_data","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data"), cpu(source()))

        answer(422, """{"status":"error","errorType":"execution","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "execution"), cpu(source()))

        answer(400, "SECRET-BODY-42")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "HTTP 400"), cpu(source()))

        answer(422, """{"status":"error","errorType":"not a word: SECRET-42","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "HTTP 422"), cpu(source()))

        answer(422, """{"status":"error","errorType":"${"x".repeat(60)}","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "HTTP 422"), cpu(source()))
    }

    @Test
    fun `401 is forbidden by its status and asks no review`() = runBlocking<Unit> {
        answer(401, """{"kind":"Status","code":401}""")

        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 401"), cpu(source()))
        assertEquals(emptyList(), reviews.toList())
    }

    @Test
    fun `403 is NoProxyAccess when the review says no, and forbidden when it says yes or cannot say`() = runBlocking<Unit> {
        answer(403, K8S_FORBIDDEN)

        reviewAnswer = false
        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), cpu(source()))
        assertEquals(listOf("monitoring"), reviews.toList())

        reviewAnswer = true
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), cpu(source()))

        reviewAnswer = null
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), cpu(source()))

        reviewFails = true
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), cpu(source()))
    }

    private suspend fun seedFound() {
        stub.addService("monitoring", "prometheus-operated", mapOf("operated-prometheus" to "true"), listOf("web" to 9090))
        detection.ensure(key) { live }
        assertEquals(DetectionState.Found(target), detection.current(key))
    }

    @Test
    fun `404 with a Kubernetes Status body is ServiceGone, and a detected target is searched for again`() = runBlocking<Unit> {
        seedFound()
        answer(404, K8S_NOT_FOUND)

        val result = cpu(source(auto = true))

        assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), result)
        assertNull(detection.current(key), "the stale Found stayed")
    }

    @Test
    fun `a detected target sends the search back to the start at most once a minute`() = runBlocking<Unit> {
        val gone = source(auto = true)
        seedFound()
        answer(404, K8S_NOT_FOUND)
        cpu(gone)
        assertNull(detection.current(key))

        // The search finds the same Service again (its probe answers), and the range query still gets a 404 Status.
        answer(200, SCALAR_OK)
        detection.ensure(key) { live }
        assertEquals(DetectionState.Found(target), detection.current(key))
        answer(404, K8S_NOT_FOUND)
        assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), cpu(gone))
        assertEquals(DetectionState.Found(target), detection.current(key), "invalidated again within the minute")

        clock.addAndGet(InClusterDetection.FAILED_TTL_MS)
        cpu(gone)
        assertNull(detection.current(key), "a minute later the stale target is dropped again")
    }

    @Test
    fun `404 with a Kubernetes Status body leaves the search alone for a target the user typed`() = runBlocking<Unit> {
        seedFound()
        answer(404, K8S_NOT_FOUND)

        val result = cpu(source(auto = false))

        assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), result)
        assertEquals(DetectionState.Found(target), detection.current(key))
    }

    @Test
    fun `any other 404 is a wrong path and keeps the detected target`() = runBlocking<Unit> {
        seedFound()
        for (body in listOf("404 page not found", """{"status":"error","errorType":"not_found","error":"x"}""", "{not json", """{"kind":"Pod"}""", "")) {
            answer(404, body)

            assertEquals(SeriesResult.Failed(HistoryFailure.WrongPath), cpu(source(auto = true)), body)
        }
        assertEquals(DetectionState.Found(target), detection.current(key))
    }

    @Test
    fun `503 with a Kubernetes Status body is no ready endpoints, any other 503 or status is its code`() = runBlocking<Unit> {
        answer(503, K8S_NO_ENDPOINTS)
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "no ready endpoints"), cpu(source()))

        answer(503, "upstream connect error")
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"), cpu(source()))

        for (code in listOf(500, 502, 429, 418)) {
            answer(code, "SECRET-BODY-42")

            assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP $code"), cpu(source()), "$code")
        }
    }

    // ── transport failures ──────────────────────────────────────────────────────

    @Test
    fun `a refused connection is a network error`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        val refusing = stub.client(masterUrl = "http://localhost:$closedPort/")

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "network error"), cpu(source { refusing }))
    }

    @Test
    fun `an answer that takes longer than the timeout is a timeout`() = runBlocking<Unit> {
        stub.proxy = { jsonResponse(200, oneSeries).setBodyDelay(Duration.ofSeconds(8)) }
        val startedAt = System.nanoTime()

        val result = cpu(source(queryTimeoutSec = 1))

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "timeout"), result)
        assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 6_000)
    }

    @Test
    fun `a target that fails validation is a bad request and sends nothing`() = runBlocking<Unit> {
        val bad = InClusterTarget("Bad_NS", "prometheus-operated", "web", https = false, pathPrefix = "")

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "bad request"), cpu(source(target = bad)))
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun `a closed client is an error, not a cancellation`() = runBlocking<Unit> {
        val closed = stub.client().also { it.close() }

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "error"), cpu(source { closed }))
    }

    @Test
    fun `no client is not connected and sends nothing`() = runBlocking<Unit> {
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "not connected"), cpu(source { null }))
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun `a client provider that throws is an error`() = runBlocking<Unit> {
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "error"), cpu(source { error("closed") }))
    }

    @Test
    fun `a new client after a reconnect is used, and the closed one is not touched again`() = runBlocking<Unit> {
        answer(200, oneSeries)
        var current = stub.client()
        val first = current
        val source = source { current }
        assertIs<SeriesResult.Ok>(cpu(source))

        val second = stub.client()
        current = second
        first.close()

        assertIs<SeriesResult.Ok>(cpu(source))
        assertIs<SeriesResult.Ok>(cpu(source))
        assertEquals(3, stub.proxyRequests.size)
    }

    @Test
    fun `a caller that is cancelled gets the cancellation, not a failure`() = runBlocking<Unit> {
        stub.proxy = { jsonResponse(200, oneSeries).setBodyDelay(Duration.ofSeconds(5)) }
        val outcome = CompletableDeferred<Result<SeriesResult>>()
        val call = launch(Dispatchers.Default) { outcome.complete(runCatching { cpu(source(queryTimeoutSec = 10)) }) }
        withTimeout(10_000) { while (stub.proxyRequests.isEmpty()) delay(10) }

        call.cancel()

        val result = withTimeout(2_000) { outcome.await() }
        assertIs<CancellationException>(result.exceptionOrNull(), "the call returned $result")
    }

    // ── pod sets and scopes ─────────────────────────────────────────────────────

    private val podSet = PodSetRef("team-a", listOf("redis-cache-6d9f8c7b5", "redis-cache-5f7a3b1d0"))
    private val nsScope = ScopeRef(setOf("team-a", "team-b"))

    private val podMatrix = """{"status":"success","data":{"resultType":"matrix","result":[""" +
        """{"metric":{"pod":"redis-cache-6d9f8c7b5-x7k2p"},"values":[[1000,"1.5"],[1030,"2"]]},""" +
        """{"metric":{"pod":"redis-cache-5f7a3b1d0-92f6w"},"values":[[1000,"4"]]}]}}"""

    private val sumMatrix = matrixBody(null to listOf(1_000L to "5.5", 1_030L to "6"))

    private suspend fun perPod(source: InClusterMetricsSource, kind: PodSeriesKind = PodSeriesKind.Cpu) = source.podSetSeries(kind, podSet, PodSetMode.PerPod, window)

    @Test
    fun `a per pod set query sends the pod pattern on the proxy path and names each series by its pod`() = runBlocking<Unit> {
        answer(200, podMatrix)

        val result = perPod(source())

        assertEquals(
            SeriesResult.Ok(
                listOf(
                    Series("redis-cache-5f7a3b1d0-92f6w", listOf(SeriesPoint(1_000, 4.0))),
                    Series("redis-cache-6d9f8c7b5-x7k2p", listOf(SeriesPoint(1_000, 1.5), SeriesPoint(1_030, 2.0))),
                ),
            ),
            result,
        )
        val request = stub.proxyRequests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/api/v1/query_range",
            request.path.substringBefore('?'),
        )
        assertEquals(PromQl.inClusterPodSetQuery(PodSeriesKind.Cpu, podSet, PodSetMode.PerPod, 30), request.param("query"))
        assertTrue(request.param("query").orEmpty().contains("pod=~"), request.param("query"))
        assertEquals(listOf("1000", "1120", "30"), listOf(request.param("start"), request.param("end"), request.param("step")))
    }

    @Test
    fun `a total pod set query is one series without a name`() = runBlocking<Unit> {
        answer(200, sumMatrix)

        val result = source().podSetSeries(PodSeriesKind.NetworkRx, podSet, PodSetMode.Total, window)

        assertEquals(SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(1_000, 5.5), SeriesPoint(1_030, 6.0))))), result)
        assertEquals(PromQl.inClusterPodSetQuery(PodSeriesKind.NetworkRx, podSet, PodSetMode.Total, 30), stub.proxyRequests.single().param("query"))
    }

    @Test
    fun `a pod set with no pods in the window is an empty chart and is never probed`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(200, countBody("0")) }
        val source = source()

        for (kind in listOf(PodSeriesKind.Cpu, PodSeriesKind.Memory)) {
            for (mode in PodSetMode.entries) {
                assertEquals(SeriesResult.Ok(emptyList()), source.podSetSeries(kind, podSet, mode, window), "$kind $mode")
            }
        }

        assertEquals(0, probeRequests().size)
        assertEquals(4, stub.proxyRequests.size)
    }

    @Test
    fun `a pod set failure maps as a pod failure does`() = runBlocking<Unit> {
        answer(400, """{"status":"error","errorType":"bad_data","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data"), perPod(source()))

        answer(422, "SECRET-BODY-42")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "HTTP 422"), perPod(source()))

        answer(200, """{"status":"error","errorType":"<b>SECRET-42</b>","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "unknown"), perPod(source()))

        answer(200, "<html>hi</html>")
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "unreadable response"), perPod(source()))

        answer(401, """{"kind":"Status","code":401}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 401"), perPod(source()))

        answer(403, K8S_FORBIDDEN)
        reviewAnswer = false
        assertEquals(SeriesResult.Failed(HistoryFailure.NoProxyAccess), perPod(source()))
        reviewAnswer = true
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), perPod(source()))

        answer(404, "404 page not found")
        assertEquals(SeriesResult.Failed(HistoryFailure.WrongPath), perPod(source()))

        answer(503, K8S_NO_ENDPOINTS)
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "no ready endpoints"), perPod(source()))

        answer(500, "SECRET-BODY-42")
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 500"), perPod(source()))
    }

    @Test
    fun `a pod set query without a client is not connected and sends nothing`() = runBlocking<Unit> {
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "not connected"), perPod(source { null }))
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun `an invalid pod set is a bad query and sends nothing`() = runBlocking<Unit> {
        val source = source()

        for (bad in listOf(
            PodSetRef("team-a", emptyList()),
            PodSetRef("team-a", listOf("Web\"x")),
            PodSetRef("team-a", listOf("redis-cache-5f7a3b1d0", "a|b")),
            PodSetRef("Team_A", listOf("redis-cache-5f7a3b1d0")),
            PodSetRef("", listOf("redis-cache-5f7a3b1d0")),
        )) {
            assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "invalid pod set"), source.podSetSeries(PodSeriesKind.Cpu, bad, PodSetMode.PerPod, window), "$bad")
        }
        assertEquals(0, stub.requests.size)
    }

    @Test
    fun `a scope query sends the namespace sum and returns one series without a name`() = runBlocking<Unit> {
        answer(200, sumMatrix)

        val result = source().scopeSeries(ScopeKind.MemoryBytes, nsScope, window)

        assertEquals(SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(1_000, 5.5), SeriesPoint(1_030, 6.0))))), result)
        val request = stub.proxyRequests.single()
        assertEquals(PromQl.inClusterScopeQuery(ScopeKind.MemoryBytes, nsScope, 30), request.param("query"))
        assertTrue(request.param("query").orEmpty().contains("namespace=~"), request.param("query"))
    }

    @Test
    fun `a scope with no series is an empty chart and is never probed`() = runBlocking<Unit> {
        answerCpuEmptyAndProbe { jsonResponse(200, countBody("0")) }

        assertEquals(SeriesResult.Ok(emptyList()), source().scopeSeries(ScopeKind.CpuCores, ScopeRef(emptySet()), window))

        assertEquals(0, probeRequests().size)
    }

    @Test
    fun `a scope failure maps as a pod failure does`() = runBlocking<Unit> {
        answer(400, """{"status":"error","errorType":"bad_data","error":"x"}""")
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data"), source().scopeSeries(ScopeKind.CpuCores, nsScope, window))

        answer(404, K8S_NOT_FOUND)
        assertEquals(SeriesResult.Failed(HistoryFailure.ServiceGone), source(auto = false).scopeSeries(ScopeKind.CpuCores, nsScope, window))

        answer(500, "SECRET-BODY-42")
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 500"), source().scopeSeries(ScopeKind.MemoryBytes, nsScope, window))
    }

    @Test
    fun `an invalid scope is a bad query and sends nothing`() = runBlocking<Unit> {
        assertEquals(
            SeriesResult.Failed(HistoryFailure.BadQuery, "invalid scope"),
            source().scopeSeries(ScopeKind.CpuCores, ScopeRef(setOf("team-a", "Team_B")), window),
        )
        assertEquals(0, stub.requests.size)
    }

    // ── what reaches the logs ───────────────────────────────────────────────────

    @Test
    fun `no failure puts a URL, a query, a name or a body in the log`() = runBlocking<Unit> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val ours = LoggerFactory.getLogger("com.kubekubedashdash") as Logger
        val previousLevel = ours.level
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        ours.level = Level.TRACE
        try {
            val source = source()
            for ((code, body) in listOf(
                200 to "SECRET-BODY-42",
                400 to """{"status":"error","errorType":"SECRET-BODY-42","error":"SECRET-BODY-42"}""",
                401 to "SECRET-BODY-42",
                403 to K8S_FORBIDDEN.replace("nope", "SECRET-BODY-42"),
                404 to "SECRET-BODY-42",
                503 to "SECRET-BODY-42",
                500 to "SECRET-BODY-42",
            )) {
                answer(code, body)
                cpu(source)
                source.podSetSeries(PodSeriesKind.Cpu, podSet, PodSetMode.PerPod, window)
                source.scopeSeries(ScopeKind.CpuCores, nsScope, window)
            }
            cpu(source { null })
            cpu(source(target = InClusterTarget("Bad_NS", "prometheus-operated", "web", false, "")))
            cpu(source { stub.client().also { it.close() } })
            source().podSetSeries(PodSeriesKind.Cpu, PodSetRef("team-a", listOf("Bad_RS")), PodSetMode.Total, window)
            source().scopeSeries(ScopeKind.CpuCores, ScopeRef(setOf("Bad_NS")), window)

            val lines = events.list.filter { it.loggerName.startsWith("com.kubekubedashdash") }.map { it.formattedMessage + it.throwableProxy?.message.orEmpty() }
            assertTrue(lines.isNotEmpty(), "the failures should log at DEBUG")
            val leaked = lines.filter { line -> listOf("SECRET", "/proxy", "prometheus-operated", "monitoring", "web-0", "team-a", "team-b", "redis-cache", "Bad_", "container_", "/api/v1", "localhost", "127.0.0.1").any { it in line } }
            assertFalse(leaked.isNotEmpty(), "logged: $leaked")
        } finally {
            root.detachAppender(events)
            events.stop()
            ours.level = previousLevel
        }
    }
}
