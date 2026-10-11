package com.kubekubedashdash.services.metricshistory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kubekubedashdash.util.ShellEnvironment
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The service-proxy transport against a loopback fabric8 mock server: the exact URL, the encoding,
 * one request per call whatever the parent client's retry budget, the failure shapes, and
 * cancellation. Nothing here leaves the machine.
 */
class ServiceProxyHttpTest {

    private companion object {
        const val SUCCESS = """{"status":"success","data":{"resultType":"scalar","result":[1,"1"]}}"""
        const val QUERY_PATH = "/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/api/v1/query"
        const val LATE_BODY = """{"status":"success","data":{"marker":"late-body-42"}}"""

        fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }

    /** Records and answers the proxy paths; everything else goes to the CRUD store. */
    private class ProxyRecorder(private val crud: KubernetesCrudDispatcher) : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        @Volatile
        var answer: (RecordedRequest) -> MockResponse = { json(200, SUCCESS) }

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!request.path.contains("/proxy/")) return crud.dispatch(request)
            requests += request
            return answer(request)
        }
    }

    private var previousCloudClis: String? = null
    private lateinit var recorder: ProxyRecorder
    private lateinit var mock: KubernetesMockServer
    private val clients = mutableListOf<KubernetesClient>()

    @BeforeTest
    fun setUp() {
        previousCloudClis = System.getProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
        System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, "true")
        recorder = ProxyRecorder(KubernetesCrudDispatcher())
        mock = KubernetesMockServer(Context(), MockWebServer(), HashMap(), recorder, false)
        mock.init()
    }

    @AfterTest
    fun tearDown() {
        val closing = clients.map { runCatching { it.close() } }
        clients.clear()
        try {
            shutdownCleanly(label = "ServiceProxyHttpTest", servers = listOf(mock))
        } finally {
            val previous = previousCloudClis
            if (previous == null) {
                System.clearProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY)
            } else {
                System.setProperty(ShellEnvironment.DISABLE_CLOUD_CLIS_PROPERTY, previous)
            }
        }
        closing.forEach { it.getOrThrow() }
    }

    /** No request retries unless a test asks for them, like the app's other test clients. */
    private fun newClient(masterUrl: String = mock.url("/"), retryLimit: Int = 0, retryIntervalMs: Int = Config.DEFAULT_REQUEST_RETRY_BACKOFFINTERVAL): KubernetesClient {
        val config = mock.createClient().use {
            ConfigBuilder(it.configuration)
                .withMasterUrl(masterUrl)
                .withRequestRetryBackoffLimit(retryLimit)
                .withRequestRetryBackoffInterval(retryIntervalMs)
                .build()
        }
        return KubernetesClientBuilder().withConfig(config).build().also { clients += it }
    }

    private fun target(
        namespace: String = "monitoring",
        service: String = "prometheus-operated",
        port: String = "web",
        https: Boolean = false,
        pathPrefix: String = "",
    ) = InClusterTarget(namespace, service, port, https, pathPrefix)

    private suspend fun awaitRequests(count: Int) {
        withTimeout(10_000) { while (recorder.requests.size < count) delay(10) }
    }

    @Test
    fun `an http target with a port name is reached at the proxy path, with a GET that accepts JSON`() = runBlocking<Unit> {
        val response = ServiceProxyHttp(newClient()).get(target(), "query", listOf("query" to "1"), ServiceProxyHttp.PROBE_TIMEOUT_SEC)

        assertEquals(ServiceProxyHttp.Response(200, SUCCESS), response)
        val request = recorder.requests.single()
        assertEquals("$QUERY_PATH?query=1", request.path)
        assertEquals("GET", request.method)
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `an https target puts the scheme in front of the service`() = runBlocking<Unit> {
        ServiceProxyHttp(newClient()).get(target(https = true), "query", listOf("query" to "1"), 5)

        assertEquals(
            "/api/v1/namespaces/monitoring/services/https:prometheus-operated:web/proxy/api/v1/query?query=1",
            recorder.requests.single().path,
        )
    }

    @Test
    fun `a port number goes where the port name would`() = runBlocking<Unit> {
        ServiceProxyHttp(newClient()).get(target(namespace = "team-a", service = "prometheus-server", port = "80"), "query", listOf("query" to "1"), 5)

        assertEquals(
            "/api/v1/namespaces/team-a/services/prometheus-server:80/proxy/api/v1/query?query=1",
            recorder.requests.single().path,
        )
    }

    @Test
    fun `a path prefix sits between proxy and the API path, and a range query keeps its parameters in order`() = runBlocking<Unit> {
        val params = listOf("query" to "up", "start" to "1700000000", "end" to "1700003600", "step" to "15")

        ServiceProxyHttp(newClient()).get(target(pathPrefix = "/select/0/prometheus"), "query_range", params, 5)

        assertEquals(
            "/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/select/0/prometheus/api/v1/query_range" +
                "?query=up&start=1700000000&end=1700003600&step=15",
            recorder.requests.single().path,
        )
    }

    @Test
    fun `a master URL with its own path keeps it`() = runBlocking<Unit> {
        for (master in listOf(mock.url("/k8s/clusters/c-x"), mock.url("/k8s/clusters/c-x/"))) {
            recorder.requests.clear()

            ServiceProxyHttp(newClient(masterUrl = master)).get(target(), "query", listOf("query" to "1"), 5)

            assertEquals("/k8s/clusters/c-x$QUERY_PATH?query=1", recorder.requests.single().path, master)
        }
    }

    @Test
    fun `a PromQL with braces, quotes, spaces, commas and operators is percent-encoded and arrives intact`() = runBlocking<Unit> {
        val promql = """sum by (container) (rate(container_cpu_usage_seconds_total{namespace="team-a",pod="web-0",container!=""}[240s])) / 7"""

        ServiceProxyHttp(newClient()).get(target(), "query", listOf("query" to promql, "time" to "1700000000"), 5)

        val path = recorder.requests.single().path
        val rawQuery = path.substringAfter('?')
        assertFalse(rawQuery.any { it in "{}\" ,!" }, "raw query: $rawQuery")
        assertTrue(rawQuery.contains("%7B") && rawQuery.contains("%7D") && rawQuery.contains("%22") && rawQuery.contains("%2C") && rawQuery.contains("%21") && rawQuery.contains("%3D"), rawQuery)
        val decoded = rawQuery.split('&').associate { pair -> pair.substringBefore('=').let { URLDecoder.decode(it, "UTF-8") } to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }
        assertEquals(mapOf("query" to promql, "time" to "1700000000"), decoded)
    }

    @Test
    fun `a PromQL with the characters of the plan encodes to the exact form`() {
        assertEquals(
            "http://host.invalid/p/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/api/v1/query?query=a%7Bb%3D%22c+d%22%2Ce%21%3D%22f%22%7D",
            ServiceProxyHttp.proxyUrl("http://host.invalid/p/", target(), "query", listOf("query" to """a{b="c d",e!="f"}""")),
        )
    }

    @Test
    fun `no parameters means no question mark`() {
        assertEquals(
            "http://host.invalid/api/v1/namespaces/monitoring/services/prometheus-operated:web/proxy/api/v1/query",
            ServiceProxyHttp.proxyUrl("http://host.invalid", target(), "query", emptyList()),
        )
    }

    @Test
    fun `a non-2xx answer is a response, not an exception`() = runBlocking<Unit> {
        val http = ServiceProxyHttp(newClient())
        for (code in listOf(400, 403, 404, 422, 500, 502)) {
            recorder.answer = { json(code, """{"status":"error","errorType":"x","error":"y"}""") }

            val response = http.get(target(), "query", listOf("query" to "1"), 5)

            assertEquals(code, response.status)
            assertEquals("""{"status":"error","errorType":"x","error":"y"}""", response.body)
        }
    }

    @Test
    fun `a 503 is sent exactly once even when the parent client retries`() = runBlocking<Unit> {
        recorder.answer = { MockResponse().setResponseCode(503).setBody("no endpoints available for service") }
        // Retries ON for the parent: five, 10 ms apart. The derived client has to drop them itself.
        val client = newClient(retryLimit = 5, retryIntervalMs = 10)

        val response = ServiceProxyHttp(client).get(target(), "query", listOf("query" to "1"), 5)

        assertEquals(ServiceProxyHttp.Response(503, "no endpoints available for service"), response)
        assertEquals(1, recorder.requests.size, "the derived client must not retry")

        // Control: the same request through the parent client is retried, so the count above proves the tag.
        recorder.requests.clear()
        val url = ServiceProxyHttp.proxyUrl(client.masterUrl.toString(), target(), "query", listOf("query" to "1"))
        val viaParent = client.httpClient.sendAsync(client.httpClient.newHttpRequestBuilder().uri(url).build(), String::class.java).await()
        assertEquals(503, viaParent.code())
        assertEquals(6, recorder.requests.size, "the parent client retries a 503 five times")
    }

    @Test
    fun `a target that fails validation never reaches the network, and the error names no value`() = runBlocking<Unit> {
        val http = ServiceProxyHttp(newClient())
        val bad = listOf(
            target(namespace = "secret-ns/../kube-system"),
            target(service = "Secret_Service"),
            target(port = "70000"),
            target(pathPrefix = "/../x"),
            target(namespace = " monitoring"),
            target(pathPrefix = "/prom/"),
        )
        for (t in bad) {
            val failure = assertFailsWith<IllegalArgumentException> { http.get(t, "query", listOf("query" to "1"), 5) }
            val message = failure.message.orEmpty()
            assertTrue(listOf("secret-ns", "Secret_Service", "70000", "/../x", "monitoring", "/prom").none { it in message }, "'$message' echoes a value")
        }
        val unsupported = assertFailsWith<IllegalArgumentException> { http.get(target(), "../../secrets", emptyList(), 5) }
        assertFalse("secrets" in unsupported.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { http.get(target(), "status/buildinfo", emptyList(), 5) }
        assertEquals(0, recorder.requests.size)
    }

    @Test
    fun `a request that outlives its timeout fails with a TimeoutException`() = runBlocking<Unit> {
        recorder.answer = { json(200, SUCCESS).setBodyDelay(Duration.ofSeconds(8)) }
        val startedAt = System.nanoTime()

        assertFailsWith<TimeoutException> { ServiceProxyHttp(newClient()).get(target(), "query", listOf("query" to "1"), 1) }

        assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 6_000, "the call did not honour its 1 s timeout")
    }

    @Test
    fun `a refused connection is an IOException`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = newClient(masterUrl = "http://localhost:$closedPort/")

        assertFailsWith<IOException> { ServiceProxyHttp(client).get(target(), "query", listOf("query" to "1"), 5) }
    }

    @Test
    fun `after the client is closed a call fails with an exception that is not a cancellation`() = runBlocking<Unit> {
        val client = newClient()
        val http = ServiceProxyHttp(client)
        http.get(target(), "query", listOf("query" to "1"), 5)
        client.close()

        // Vert.x throws IllegalStateException("Client is closed") from the request call itself.
        val failure = assertFailsWith<Exception> { http.get(target(), "query", listOf("query" to "1"), 5) }

        assertFalse(failure is CancellationException, "a closed client is a failure, not the caller's cancellation: $failure")
    }

    @Test
    fun `a cancelled get returns promptly with a CancellationException, and a response that lands later is neither logged nor delivered`() = runBlocking<Unit> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        // The app's logback.xml keeps io.fabric8 at WARN, because fabric8's wire logging at TRACE
        // prints every URL and body; that level is left as configured. Ours is opened fully.
        val ours = LoggerFactory.getLogger("com.kubekubedashdash") as Logger
        val fabric8 = LoggerFactory.getLogger("io.fabric8.kubernetes.client") as Logger
        val previousLevel = ours.level
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        ours.level = Level.TRACE
        try {
            recorder.answer = { json(200, LATE_BODY).setBodyDelay(Duration.ofMillis(1_500)) }
            val http = ServiceProxyHttp(newClient())
            val outcome = CompletableDeferred<Throwable?>()
            val caller = launch(Dispatchers.Default) {
                try {
                    http.get(target(), "query", listOf("query" to "1"), 15)
                    outcome.complete(null)
                } catch (e: Throwable) {
                    outcome.complete(e)
                    throw e
                }
            }
            awaitRequests(1)

            val cancelledAt = System.nanoTime()
            caller.cancel()
            val thrown = withTimeout(2_000) { outcome.await() }
            val tookMs = (System.nanoTime() - cancelledAt) / 1_000_000

            assertIs<CancellationException>(thrown)
            assertTrue(tookMs < 1_000, "the cancelled get took $tookMs ms")
            caller.join()
            assertTrue(caller.isCancelled)

            // Past the point the mock server answers (1.5 s after the request).
            delay(2_000)
            assertTrue(caller.isCancelled, "the late response must not revive the caller")
            assertEquals(thrown, outcome.await(), "the outcome is the cancellation and nothing later")
            val fromCode = events.list.filter { event -> event.loggerName.startsWith(ours.name) || event.loggerName.startsWith(fabric8.name) }
            val leaked = fromCode.filter { event ->
                val text = event.formattedMessage + event.throwableProxy?.message.orEmpty()
                listOf("late-body-42", "/proxy", "prometheus-operated", "/api/v1").any { it in text }
            }
            assertTrue(leaked.isEmpty(), "logged: ${leaked.map { it.formattedMessage }}")
        } finally {
            root.detachAppender(events)
            events.stop()
            ours.level = previousLevel
        }
    }
}
