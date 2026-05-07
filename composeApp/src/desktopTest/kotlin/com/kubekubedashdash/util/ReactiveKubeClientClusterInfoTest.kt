package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodStatusBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the combine-based `clusterInfo` derivation matches the data the
 * informer flows hold. Counts must reflect the seeded cluster contents; pod
 * phase tallies must be derived from `PodInfo.phase` (the raw
 * `pod.status.phase`, not the effective status string).
 */
class ReactiveKubeClientClusterInfoTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
        server.init()

        // Seed: 2 namespaces, 4 pods (2 Running, 1 Pending, 1 Failed), 2
        // deployments, 2 services. Mock dispatcher infers cluster scope, so
        // KubernetesCrudDispatcher already exposes `default` + `kube-system`
        // namespaces; create just one extra to make the count predictable.
        val seed = server.createClient()
        try {
            seed.namespaces().resource(
                NamespaceBuilder().withNewMetadata().withName("ns-a").endMetadata().build(),
            ).create()

            fun pod(name: String, ns: String, phase: String) = PodBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .withStatus(PodStatusBuilder().withPhase(phase).build())
                .build()

            seed.pods().inNamespace("ns-a").resource(pod("p1", "ns-a", "Running")).create()
            seed.pods().inNamespace("ns-a").resource(pod("p2", "ns-a", "Running")).create()
            seed.pods().inNamespace("ns-a").resource(pod("p3", "ns-a", "Pending")).create()
            seed.pods().inNamespace("ns-a").resource(pod("p4", "ns-a", "Failed")).create()

            seed.apps().deployments().inNamespace("ns-a").resource(
                DeploymentBuilder().withNewMetadata().withName("d1").withNamespace("ns-a").endMetadata().build(),
            ).create()
            seed.apps().deployments().inNamespace("ns-a").resource(
                DeploymentBuilder().withNewMetadata().withName("d2").withNamespace("ns-a").endMetadata().build(),
            ).create()

            seed.services().inNamespace("ns-a").resource(
                ServiceBuilder().withNewMetadata().withName("s1").withNamespace("ns-a").endMetadata().build(),
            ).create()
            seed.services().inNamespace("ns-a").resource(
                ServiceBuilder().withNewMetadata().withName("s2").withNamespace("ns-a").endMetadata().build(),
            ).create()
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        manager.close()
        server.destroy()
    }

    @Test
    fun `clusterInfo aggregates counts from informers and tallies pod phases`() = runBlocking {
        // Subscribe before reading: clusterInfo is a `WhileSubscribed` flow,
        // so without a live collector it never starts the underlying
        // informers. `first { … }` alone would subscribe but we need any
        // dependent flow to subscribe too — luckily the combine internally
        // wires that.
        val info = withTimeout(15_000) {
            client.clusterInfo.first { it is ResourceState.Success } as ResourceState.Success
        }.data

        // Mock dispatcher starts empty — only `ns-a` (created above) exists.
        assertEquals(1, info.namespacesCount, "namespacesCount")
        assertEquals(4, info.podsCount, "podsCount")
        assertEquals(2, info.deploymentsCount, "deploymentsCount")
        assertEquals(2, info.servicesCount, "servicesCount")

        assertEquals(2, info.runningPods, "runningPods")
        assertEquals(1, info.pendingPods, "pendingPods")
        assertEquals(1, info.failedPods, "failedPods")
        assertEquals(0, info.succeededPods, "succeededPods")
    }
}
