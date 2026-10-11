package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.CliInvocationFailure
import com.kubekubedashdash.util.ShellEnvironment
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The Managed Prometheus source against a loopback HttpServer: the request it builds, how each
 * status is classified, and when the no-data probe is sent. The token provider is a fake, so no
 * test starts gcloud or leaves the machine.
 */
class GmpMetricsSourceTest {

    private var previousCloudClis: String? = null

    @BeforeTest
    fun disableCloudClis() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
        startServer()
    }

    @AfterTest
    fun restoreCloudClis() {
        server.stop(0)
        val previous = previousCloudClis
        if (previous == null) {
            System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        } else {
            System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
        }
    }

    private data class Seen(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: String)

    private data class Reply(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private val target = GmpTarget("example-project", "us-central1", "example-cluster")
    private val pod = PodRef("team-a", "web-0")
    private val window = QueryWindow(startEpochSec = 1_000, endEpochSec = 1_120, stepSec = 30)

    private val rangePath = "/v1/projects/example-project/location/global/prometheus/api/v1/query_range"
    private val probePath = "/v1/projects/example-project/location/global/prometheus/api/v1/query"

    private val oneSeries = """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"container":"app"},"values":[[1000,"1.5"],[1030,"2"]]}]}}"""
    private val noSeries = """{"status":"success","data":{"resultType":"matrix","result":[]}}"""

    private fun probeCount(value: String) = """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1000,"$value"]}]}}"""

    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Seen>()

    @Volatile
    private var respond: (Seen) -> Reply = { Reply(200, oneSeries) }

    private val clock = AtomicLong(1_000_000)
    private val tokenFetches = AtomicInteger()

    private fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        server.createContext("/") { exchange ->
            // Read the whole request before answering, or the client can see a reset.
            val body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val request = Seen(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = body,
            )
            seen.add(request)
            val reply = respond(request)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    private fun newClient(): HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Hands out fake-token-1, then fake-token-2, … from a fake clock; counts the fetches. */
    private fun fakeToken(fetch: (Int) -> Result<String> = { n -> Result.success("fake-token-$n") }) = GcloudAccessToken(
        fetch = { fetch(tokenFetches.incrementAndGet()) },
        nowMs = { clock.get() },
    )

    private fun source(
        token: GcloudAccessToken = fakeToken(),
        http: PrometheusHttp = PrometheusHttp(newClient()),
        base: String = baseUrl,
    ) = GmpMetricsSource(target, token, http, base, nowMs = { clock.get() })

    private fun form(request: Seen): Map<String, String> = request.body.split("&").associate {
        val (name, value) = it.split("=", limit = 2)
        URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    @Test
    fun `a query is a form POST to the project's query_range with a bearer token`() = runBlocking<Unit> {
        val result = source().podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Ok(listOf(Series("app", listOf(SeriesPoint(1000, 1.5), SeriesPoint(1030, 2.0))))), result)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals(rangePath, request.path)
        assertEquals("Bearer fake-token-1", request.authorization)
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        assertEquals(
            mapOf(
                "query" to PromQl.gmpPodQuery(PodSeriesKind.Cpu, target, pod, window.stepSec),
                "start" to "1000",
                "end" to "1120",
                "step" to "30",
            ),
            form(request),
        )
    }

    @Test
    fun `a pod level network series carries no name`() = runBlocking<Unit> {
        respond = { Reply(200, """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[[1000,"5"]]}]}}""") }

        val result = source().podSeries(PodSeriesKind.NetworkRx, pod, window)

        assertEquals(SeriesResult.Ok(listOf(Series("", listOf(SeriesPoint(1000, 5.0))))), result)
        assertEquals(PromQl.gmpPodQuery(PodSeriesKind.NetworkRx, target, pod, window.stepSec), form(seen.single())["query"])
    }

    @Test
    fun `a 401 refreshes the token once and retries`() = runBlocking<Unit> {
        respond = { request -> if (request.authorization == "Bearer fake-token-1") Reply(401) else Reply(200, oneSeries) }

        val result = source().podSeries(PodSeriesKind.Cpu, pod, window)

        assertIs<SeriesResult.Ok>(result)
        assertEquals(listOf("Bearer fake-token-1", "Bearer fake-token-2"), seen.map { it.authorization })
        assertEquals(2, tokenFetches.get())
    }

    @Test
    fun `a second 401 is not signed in`() = runBlocking<Unit> {
        respond = { Reply(401) }

        val result = source().podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Failed(HistoryFailure.NotSignedIn, "HTTP 401"), result)
        assertEquals(2, seen.size)
        assertEquals(2, tokenFetches.get())
    }

    @Test
    fun `403 and 404 are forbidden, with the status kept apart`() = runBlocking<Unit> {
        respond = { Reply(403, """{"error":{"message":"fake-token-1 may not read example-project"}}""") }
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 403"), source().podSeries(PodSeriesKind.Cpu, pod, window))

        respond = { Reply(404, "no such project") }
        assertEquals(SeriesResult.Failed(HistoryFailure.Forbidden, "HTTP 404"), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `400 with a Prometheus envelope is a bad query named by its error type`() = runBlocking<Unit> {
        respond = { Reply(400, """{"status":"error","errorType":"bad_data","error":"x"}""") }

        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "bad_data"), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `422 without an envelope is a bad query named by its status`() = runBlocking<Unit> {
        respond = { Reply(422, "<html>nope</html>") }

        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "HTTP 422"), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `any other status is unavailable`() = runBlocking<Unit> {
        respond = { Reply(503, "upstream down") }

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 503"), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `a 200 that is not a matrix is unavailable, and an error envelope is a bad query`() = runBlocking<Unit> {
        respond = { Reply(200, "not json") }
        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "unreadable response"), source().podSeries(PodSeriesKind.Cpu, pod, window))

        respond = { Reply(200, """{"status":"error","errorType":"execution","error":"x"}""") }
        assertEquals(SeriesResult.Failed(HistoryFailure.BadQuery, "execution"), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `a failure never carries the token`() = runBlocking<Unit> {
        respond = { Reply(500, "fake-token-1") }

        val result = source().podSeries(PodSeriesKind.Cpu, pod, window)

        assertIs<SeriesResult.Failed>(result)
        assertFalse(result.detail.contains("fake-token"), "the detail leaks the token: ${result.detail}")
    }

    @Test
    fun `an empty cpu answer with a probe of zero means no container metrics`() = runBlocking<Unit> {
        respond = { request -> if (request.path == rangePath) Reply(200, noSeries) else Reply(200, probeCount("0")) }

        val result = source().podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), result)
        assertEquals(listOf(rangePath, probePath), seen.map { it.path })
        assertEquals(mapOf("query" to PromQl.gmpProbeQuery(target)), form(seen.last()))
    }

    @Test
    fun `an empty memory answer is probed too`() = runBlocking<Unit> {
        respond = { request -> if (request.path == rangePath) Reply(200, noSeries) else Reply(200, probeCount("0")) }

        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), source().podSeries(PodSeriesKind.Memory, pod, window))
    }

    @Test
    fun `an empty answer with other series in the cluster is just empty`() = runBlocking<Unit> {
        respond = { request -> if (request.path == rangePath) Reply(200, noSeries) else Reply(200, probeCount("42")) }

        assertEquals(SeriesResult.Ok(emptyList()), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `an empty probe vector counts as no data`() = runBlocking<Unit> {
        respond = { request ->
            if (request.path == rangePath) Reply(200, noSeries) else Reply(200, """{"status":"success","data":{"resultType":"vector","result":[]}}""")
        }

        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), source().podSeries(PodSeriesKind.Cpu, pod, window))
    }

    @Test
    fun `the probe answer is reused for five minutes and then asked again`() = runBlocking<Unit> {
        respond = { request -> if (request.path == rangePath) Reply(200, noSeries) else Reply(200, probeCount("42")) }
        val gmp = source()

        gmp.podSeries(PodSeriesKind.Cpu, pod, window)
        clock.addAndGet(4 * 60 * 1000L)
        gmp.podSeries(PodSeriesKind.Memory, pod, window)
        assertEquals(1, seen.count { it.path == probePath }, "a second empty query inside five minutes must not probe again")

        clock.addAndGet(60 * 1000L + 1)
        gmp.podSeries(PodSeriesKind.Cpu, pod, window)
        assertEquals(2, seen.count { it.path == probePath })
    }

    @Test
    fun `a failed probe says nothing and is not remembered`() = runBlocking<Unit> {
        var probes = 0
        respond = { request ->
            when {
                request.path == rangePath -> Reply(200, noSeries)
                ++probes == 1 -> Reply(503)
                else -> Reply(200, probeCount("0"))
            }
        }
        val gmp = source()

        assertEquals(SeriesResult.Ok(emptyList()), gmp.podSeries(PodSeriesKind.Cpu, pod, window))
        assertEquals(SeriesResult.Failed(HistoryFailure.NoContainerMetrics), gmp.podSeries(PodSeriesKind.Cpu, pod, window))
        assertEquals(2, probes)
    }

    @Test
    fun `an empty answer for a kind that is not cpu or memory is never probed`() = runBlocking<Unit> {
        respond = { Reply(200, noSeries) }
        val gmp = source()

        for (kind in listOf(PodSeriesKind.NetworkRx, PodSeriesKind.NetworkTx, PodSeriesKind.Throttling)) {
            assertEquals(SeriesResult.Ok(emptyList()), gmp.podSeries(kind, pod, window))
        }
        assertEquals(3, seen.size)
        assertFalse(seen.any { it.path == probePath })
    }

    @Test
    fun `a token failure is reported without sending a request`() = runBlocking<Unit> {
        val token = fakeToken { Result.failure(CliInvocationFailure(-1, "", "gcloud CLI not found on PATH")) }

        val result = source(token = token).podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Failed(HistoryFailure.GcloudUnavailable), result)
        assertEquals(0, seen.size)
    }

    @Test
    fun `a signed-out gcloud is not signed in and sends no request`() = runBlocking<Unit> {
        val token = fakeToken { Result.failure(CliInvocationFailure(1, "", "ERROR: no active account")) }

        assertEquals(SeriesResult.Failed(HistoryFailure.NotSignedIn), source(token = token).podSeries(PodSeriesKind.Cpu, pod, window))
        assertEquals(0, seen.size)
    }

    @Test
    fun `a closed port is a network error`() = runBlocking<Unit> {
        val closed = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        val closedBase = "http://127.0.0.1:${closed.address.port}"
        closed.start()
        closed.stop(0)

        val result = source(base = closedBase).podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "network error"), result)
        assertEquals(0, seen.size)
    }

    @Test
    fun `a redirect is not followed and so never carries the token elsewhere`() = runBlocking<Unit> {
        // Back to this server, so a followed redirect shows up as a second request.
        respond = { Reply(302, headers = mapOf("Location" to "$baseUrl/elsewhere")) }

        val result = source(http = PrometheusHttp.shared).podSeries(PodSeriesKind.Cpu, pod, window)

        assertEquals(SeriesResult.Failed(HistoryFailure.Unavailable, "HTTP 302"), result)
        assertEquals(1, seen.size)
    }
}
