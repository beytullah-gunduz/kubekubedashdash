package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * stopAll() must clear the simulator's churn but preserve the curated baseline
 * (mock-node-1 + the seeded core pods) so the demo cluster stays coherent
 * (audit C6). It previously deleted everything, leaving the demo permanently
 * degraded with nothing to re-seed it.
 */
class DemoClusterSimulatorStopAllTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        client = server.createClient()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.destroy()
    }

    private fun node(name: String) = NodeBuilder().withNewMetadata().withName(name).endMetadata().build()
    private fun pod(name: String, ns: String) = PodBuilder().withNewMetadata().withName(name).withNamespace(ns).endMetadata().build()

    @Test
    fun `stopAll keeps the protected baseline and clears churn`() {
        // Baseline (must survive): mock-node-1 + one protected seeded pod.
        client.nodes().resource(node("mock-node-1")).create()
        client.pods().inNamespace("default").resource(pod("frontend-7b9d5c8f4-abc12", "default")).create()
        // Churn (must be deleted): an extra node + an extra pod.
        client.nodes().resource(node("churn-node-7")).create()
        client.pods().inNamespace("default").resource(pod("churn-pod-1", "default")).create()

        DemoClusterSimulator(client).stopAll()

        assertNotNull(client.nodes().withName("mock-node-1").get(), "mock-node-1 must survive")
        assertNull(client.nodes().withName("churn-node-7").get(), "churn node must be deleted")
        assertNotNull(
            client.pods().inNamespace("default").withName("frontend-7b9d5c8f4-abc12").get(),
            "protected baseline pod must survive",
        )
        assertNull(
            client.pods().inNamespace("default").withName("churn-pod-1").get(),
            "churn pod must be deleted",
        )
    }
}
