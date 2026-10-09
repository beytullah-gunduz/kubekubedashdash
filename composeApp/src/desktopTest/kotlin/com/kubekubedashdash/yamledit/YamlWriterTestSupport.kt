package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.seedCrInstance
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ManagedFieldsEntryBuilder
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList

internal const val TEST_NAMESPACE = "example-ns"

/** The ConfigMap target the writer tests share. */
internal fun configMapTarget(name: String = "demo-cm") = EditTarget("ConfigMap", "", "v1", "configmaps", true, name, TEST_NAMESPACE)

/** A namespaced custom resource target (the status-carrying kind of the tests). */
internal fun widgetTarget(name: String = "w1") = EditTarget("Widget", "example.com", "v1", "widgets", true, name, TEST_NAMESPACE)

/** A document ready for apply, as `ApplyDocuments.prepare` would build it. */
internal fun applyDocument(target: EditTarget, body: LinkedHashMap<String, Any?>, index: Int = 0) = ApplyDocument(index, 1, target, namespaceDefaulted = false, body = body)

/** The manifest of a ConfigMap whose data is [data]. */
internal fun configMapManifest(name: String = "demo-cm", data: Map<String, String> = mapOf("a" to "1")): LinkedHashMap<String, Any?> = linkedMapOf(
    "apiVersion" to "v1",
    "kind" to "ConfigMap",
    "metadata" to linkedMapOf<String, Any?>("name" to name, "namespace" to TEST_NAMESPACE),
    "data" to LinkedHashMap<String, Any?>(data),
)

/** The nested map under [key] of [map]. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.sub(key: String): MutableMap<String, Any?> = this[key] as MutableMap<String, Any?>

/** One request that reached the mock server, with the body it carried. */
internal class Sent(val method: String, val path: String, val body: String, val contentType: String?) {
    override fun toString() = "$method $path"
}

/**
 * Remembers each request before the CRUD dispatcher handles it. The recorded requests of the mock
 * (`takeRequest`) cannot be used for a body: the CRUD dispatcher reads a PUT or POST body out of
 * the buffer, so it is empty by the time a test looks.
 */
private class RecordingDispatcher(private val delegate: Dispatcher, private val sink: MutableList<Sent>) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        sink += Sent(request.method, request.path.orEmpty(), String(request.body.bytes, Charsets.UTF_8), request.getHeader("Content-Type"))
        return delegate.dispatch(request)
    }

    override fun shutdown() = delegate.shutdown()
}

/**
 * A fabric8 mock server in the same mixed CRUD mode the demo cluster uses (expectations answer
 * first, the in-memory store second), with one manager connected to it under [label]. Loopback
 * only; no real user state is touched.
 */
internal class WriterMock(private val label: String = "cluster-a") {
    val server: KubernetesMockServer
    val seed: KubernetesClient
    val manager: KubeConnectionManager
    private val sent = CopyOnWriteArrayList<Sent>()

    init {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, RecordingDispatcher(KubernetesMixedDispatcher(responses), sent), false)
        server.init()
        seed = server.createClient()
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), label).getOrThrow()
    }

    /** Every request the server received since the last drain. */
    fun drain(): List<Sent> {
        val requests = ArrayList(sent)
        sent.clear()
        return requests
    }

    /** The requests [block] caused: whatever was received before is discarded first. */
    fun recorded(block: () -> Unit): List<Sent> {
        drain()
        block()
        return drain()
    }

    fun seedConfigMap(name: String = "demo-cm", data: Map<String, String> = mapOf("a" to "1"), withManagedFields: Boolean = false) {
        val metadata = ObjectMeta().apply {
            this.name = name
            this.namespace = TEST_NAMESPACE
            labels = mapOf("app" to "demo")
            if (withManagedFields) {
                managedFields = listOf(ManagedFieldsEntryBuilder().withManager("kubectl").withOperation("Update").withApiVersion("v1").build())
            }
        }
        val configMap = ConfigMapBuilder().withMetadata(metadata).withData<String, String>(data).build()
        seed.configMaps().inNamespace(TEST_NAMESPACE).resource(configMap).create()
    }

    /** A `Widget` custom resource, optionally with a status (which the mock treats as part of the object, like a CRD without a status subresource). */
    fun seedWidget(name: String = "w1", spec: Map<String, Any?> = mapOf("size" to 1), status: Map<String, Any?>? = mapOf("phase" to "Ready")) {
        val widget = GenericKubernetesResource()
        widget.apiVersion = "example.com/v1"
        widget.kind = "Widget"
        widget.metadata = ObjectMeta().apply {
            this.name = name
            this.namespace = TEST_NAMESPACE
        }
        widget.additionalProperties["spec"] = spec
        if (status != null) widget.additionalProperties["status"] = status
        seedCrInstance(seed, "example.com", "v1", "widgets", TEST_NAMESPACE, widget)
    }

    fun stop(testName: String) {
        shutdownCleanly(label = testName, manager = manager, client = seed, servers = listOf(server))
    }
}
