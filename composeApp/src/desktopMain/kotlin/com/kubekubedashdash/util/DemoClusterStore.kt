package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.CustomResourceAware
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.Resetable
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import io.fabric8.mockwebserver.dsl.HttpMethod
import io.fabric8.mockwebserver.http.Dispatcher
import io.fabric8.mockwebserver.http.MockResponse
import io.fabric8.mockwebserver.http.RecordedRequest
import io.fabric8.mockwebserver.internal.MockDispatcher
import io.fabric8.mockwebserver.internal.SimpleRequest
import java.util.Queue

/**
 * The demo's mock API server store: fabric8's mixed dispatcher (the recorded responses, which carry
 * `/version`, then the CRUD store for the rest), built here so the seed can reach the store.
 *
 * The store stamps every object it is handed with the current time and keeps that through updates, so
 * an object whose age matters is written back through [backdate].
 */
internal class DemoClusterStore(private val responses: Map<ServerRequest, Queue<ServerResponse>>) :
    Dispatcher(),
    Resetable,
    CustomResourceAware {
    private val recorded = MockDispatcher(responses)
    private val crud = KubernetesCrudDispatcher()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val queue = responses[SimpleRequest(HttpMethod.valueOf(request.method), request.path)]
        return if (queue != null && queue.isNotEmpty()) recorded.dispatch(request) else crud.dispatch(request)
    }

    override fun reset() = crud.reset()

    override fun expectCustomResource(rdc: CustomResourceDefinitionContext) = crud.expectCustomResource(rdc)

    /**
     * Gives the stored object at [itemPath] (`/apis/apps/v1/namespaces/<ns>/deployments/<name>`) the creation
     * time [creationTimestamp]. Meant for seeding, before any client watches the store: the write takes no lock.
     */
    fun backdate(itemPath: String, creationTimestamp: String) {
        val attributes = crud.getKey(itemPath)
        val stored = checkNotNull(crud.findResource(attributes)) { "nothing is stored at the path" }
        val resource = Serialization.unmarshal(stored.value, GenericKubernetesResource::class.java)
        resource.metadata.creationTimestamp = creationTimestamp
        crud.processEvent(itemPath, attributes, stored.key, null, Serialization.asJson(resource))
    }
}
