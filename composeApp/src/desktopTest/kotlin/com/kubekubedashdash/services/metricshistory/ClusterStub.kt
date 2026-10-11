package com.kubekubedashdash.services.metricshistory

import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePortBuilder
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
import java.lang.reflect.Proxy
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

internal const val SCALAR_OK = """{"status":"success","data":{"resultType":"scalar","result":[1,"1"]}}"""
internal const val VECTOR_OK = """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1000,"1"]}]}}"""
internal const val EMPTY_MATRIX = """{"status":"success","data":{"resultType":"matrix","result":[]}}"""
internal const val K8S_NOT_FOUND = """{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"services \"x\" not found","reason":"NotFound","code":404}"""
internal const val K8S_NO_ENDPOINTS = """{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"no endpoints available for service \"x\"","reason":"ServiceUnavailable","code":503}"""
internal const val K8S_FORBIDDEN = """{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure","message":"services is forbidden: nope","reason":"Forbidden","code":403}"""

internal fun jsonResponse(code: Int, body: String): MockResponse = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

/** A matrix answer with one series per (container, samples) pair; a null container makes the series unlabelled. */
internal fun matrixBody(vararg series: Pair<String?, List<Pair<Long, String>>>): String {
    val items = series.joinToString(",") { (container, samples) ->
        val metric = if (container == null) "{}" else """{"container":"$container"}"""
        """{"metric":$metric,"values":[${samples.joinToString(",") { (t, v) -> """[$t,"$v"]""" }}]}"""
    }
    return """{"status":"success","data":{"resultType":"matrix","result":[$items]}}"""
}

internal fun countBody(value: String) = """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1000,"$value"]}]}}"""

/** The decoded value of one query parameter of a request path. */
internal fun queryParam(path: String, name: String): String? = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
    .map { it.substringBefore('=') to it.substringAfter('=', "") }
    .firstOrNull { (key, _) -> key == name }
    ?.let { (_, value) -> URLDecoder.decode(value, "UTF-8") }

/** A KubernetesClient that is never called: only its identity matters to the code under test. */
internal fun dummyClient(): KubernetesClient = Proxy.newProxyInstance(KubernetesClient::class.java.classLoader, arrayOf(KubernetesClient::class.java)) { self, method, args ->
    when (method.name) {
        "toString" -> "DummyKubernetesClient"
        "hashCode" -> System.identityHashCode(self)
        "equals" -> self === args?.get(0)
        "close" -> null
        else -> throw UnsupportedOperationException(method.name)
    }
} as KubernetesClient

/**
 * A loopback fabric8 mock API server. Service-proxy paths, the Prometheus finders' Service reads and
 * access reviews go through scripted hooks and are recorded; everything else is the CRUD store, so
 * a test seeds Services through [addService] and a finder reads them back with its label selector.
 */
internal class ClusterStub {
    data class Seen(val method: String, val path: String, val body: String) {
        /** The decoded value of one query parameter. */
        fun param(name: String): String? = queryParam(path, name)
    }

    val requests = CopyOnWriteArrayList<Seen>()

    /** Answers a Prometheus API request through the service proxy. */
    @Volatile
    var proxy: (RecordedRequest) -> MockResponse = { jsonResponse(200, SCALAR_OK) }

    /** Gets the CRUD answer to a finder's Service read and returns what to send instead (a delay, a 403, a 500). */
    @Volatile
    var finders: (RecordedRequest, MockResponse) -> MockResponse = { _, answer -> answer }

    @Volatile
    var review: (RecordedRequest) -> MockResponse = { reviewResponse(allowed = true) }

    private val crud = KubernetesCrudDispatcher()

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // getBytes() copies; readUtf8() would empty the buffer the CRUD store reads next.
            requests += Seen(request.method.orEmpty(), request.path.orEmpty(), request.body?.bytes?.toString(Charsets.UTF_8).orEmpty())
            val path = request.path.orEmpty()
            return when {
                "/proxy/" in path -> proxy(request)
                request.method == "POST" && path.startsWith(REVIEW_PATH) -> review(request)
                isFinderRequest(request.method.orEmpty(), path) -> finders(request, crud.dispatch(request))
                else -> crud.dispatch(request)
            }
        }
    }

    private val mock = KubernetesMockServer(Context(), MockWebServer(), HashMap(), dispatcher, false).also { it.init() }
    private val clients = mutableListOf<KubernetesClient>()
    private val seed: KubernetesClient by lazy { mock.createClient().also { clients += it } }

    val masterUrl: String get() = mock.url("/")

    /** Requests the Prometheus finders send: three cluster-wide Service lists and one read of the Lens Service. */
    val finderRequests: List<Seen> get() = requests.filter { isFinderRequest(it.method, it.path) }

    val proxyRequests: List<Seen> get() = requests.filter { "/proxy/" in it.path }

    val reviewRequests: List<Seen> get() = requests.filter { it.method == "POST" && it.path.startsWith(REVIEW_PATH) }

    /** A client with request retries off, unless a test asks for them. */
    fun client(retryLimit: Int = 0, masterUrl: String = this.masterUrl): KubernetesClient {
        val config = mock.createClient().use {
            ConfigBuilder(it.configuration)
                .withMasterUrl(masterUrl)
                .withRequestRetryBackoffLimit(retryLimit)
                .withRequestRetryBackoffInterval(Config.DEFAULT_REQUEST_RETRY_BACKOFFINTERVAL)
                .build()
        }
        return KubernetesClientBuilder().withConfig(config).build().also { clients += it }
    }

    fun addService(namespace: String, name: String, labels: Map<String, String>, ports: List<Pair<String?, Int>> = listOf("web" to 9090)) {
        val service = ServiceBuilder()
            .withNewMetadata().withNamespace(namespace).withName(name).withLabels<String, String>(labels).endMetadata()
            .withNewSpec().withPorts(ports.map { (portName, number) -> ServicePortBuilder().withName(portName).withPort(number).build() }).endSpec()
            .build()
        seed.services().inNamespace(namespace).resource(service).create()
    }

    fun close(label: String) {
        val closing = clients.map { runCatching { it.close() } }
        clients.clear()
        try {
            shutdownCleanly(label = label, servers = listOf(mock))
        } finally {
            closing.forEach { it.getOrThrow() }
        }
    }

    companion object {
        const val REVIEW_PATH = "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews"

        // fabric8's cluster-wide list is /api/v1/services?labelSelector=…; the Lens finder reads one Service by name.
        fun isFinderRequest(method: String, path: String): Boolean = method == "GET" &&
            (path.startsWith("/api/v1/services") || path.substringBefore('?') == "/api/v1/namespaces/lens-metrics/services/prometheus")

        fun reviewResponse(allowed: Boolean): MockResponse = jsonResponse(
            201,
            """{"kind":"SelfSubjectAccessReview","apiVersion":"authorization.k8s.io/v1","status":{"allowed":$allowed}}""",
        )

        fun delayed(answer: MockResponse, duration: Duration): MockResponse = answer.setBodyDelay(duration)
    }
}
