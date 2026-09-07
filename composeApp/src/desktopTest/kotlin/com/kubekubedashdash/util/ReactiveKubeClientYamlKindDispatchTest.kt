package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ReactiveKubeClient.getResourceYaml` dispatched on the bare kind name
 * before looking at the API group, so a CRD that reuses a built-in kind
 * name in its own group (the sidebar forwards the CRD's group/version/
 * plural) rendered the same-named BUILT-IN object's YAML — next to a Delete
 * that, since item 4, removes the custom one. A group-qualified call must
 * take the generic branch; a bare call must still resolve the built-in.
 * Loopback CRUD mock only; no real user state is touched.
 */
class ReactiveKubeClientYamlKindDispatchTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var scope: CoroutineScope
    private lateinit var client: ReactiveKubeClient

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

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientYamlKindDispatchTest", manager = manager, client = seed, servers = listOf(server))
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

    @Test
    fun `a blank group counts as no group`() {
        val yaml = client.getResourceYaml("StatefulSet", "shared", "default", group = "", version = "", plural = "")

        assertTrue(yaml.contains("apps/v1"), yaml)
        assertTrue(yaml.contains("built-in"), yaml)
    }
}
