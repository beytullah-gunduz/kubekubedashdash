package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same bare-kind shadow in the blocking `KubeClient.getResourceYaml`,
 * which serves MCP. Its callers pass no group today, but the contract is the
 * one function and must not diverge from the reactive client's.
 * Loopback CRUD mock only; no real user state is touched.
 */
class KubeClientYamlKindDispatchTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var client: KubeClient

    private val customStatefulSet = ResourceDefinitionContext.Builder()
        .withGroup("widgets.example")
        .withVersion("v1")
        .withKind("StatefulSet")
        .withPlural("statefulsets")
        .withNamespaced(true)
        .build()

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        seed = server.createClient()
        seed.apps().statefulSets().inNamespace("default").resource(
            StatefulSetBuilder()
                .withNewMetadata().withName("shared").withNamespace("default").addToLabels("owner", "built-in").endMetadata()
                .build(),
        ).create()
        seed.genericKubernetesResources(customStatefulSet).inNamespace("default").resource(
            GenericKubernetesResourceBuilder()
                .withApiVersion("widgets.example/v1")
                .withKind("StatefulSet")
                .withNewMetadata().withName("shared").withNamespace("default").addToLabels("owner", "custom").endMetadata()
                .build(),
        ).create()

        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = KubeClient(manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "KubeClientYamlKindDispatchTest", manager = manager, client = seed, servers = listOf(server))
    }

    @Test
    fun `a bare kind still resolves the built-in object`() {
        val yaml = client.getResourceYaml("StatefulSet", "shared", "default")

        // fabric8 quotes scalars (`apiVersion: "apps/v1"`), so match the values only.
        assertTrue(yaml.contains("apps/v1"), yaml)
        assertTrue(yaml.contains("built-in"), yaml)
        assertFalse(yaml.contains("widgets.example"), yaml)
    }

    @Test
    fun `a group-qualified kind that reuses a built-in name renders the custom object, not the built-in`() {
        val yaml = client.getResourceYaml(
            "StatefulSet",
            "shared",
            "default",
            group = "widgets.example",
            version = "v1",
            plural = "statefulsets",
        )

        assertTrue(yaml.contains("widgets.example/v1"), yaml)
        assertTrue(yaml.contains("custom"), yaml)
        assertFalse(yaml.contains("apps/v1") || yaml.contains("built-in"), "the built-in must not shadow the custom object: $yaml")
    }
}
