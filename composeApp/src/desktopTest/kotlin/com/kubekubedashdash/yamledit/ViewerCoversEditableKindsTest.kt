package com.kubekubedashdash.yamledit

import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor opens every kind in [EditableKinds.BUILT_INS] from the YAML tab, so the viewer
 * (`ReactiveKubeClient.getResourceYaml`) has to read each of them at the table's group and version.
 * NetworkPolicy and Endpoints were missing from the viewer and showed "# Resource not found".
 * Loopback CRUD mock only; no real user state is touched.
 */
class ViewerCoversEditableKindsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var scope: CoroutineScope
    private lateinit var client: ReactiveKubeClient

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        seed = server.createClient()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ViewerCoversEditableKindsTest", manager = manager, client = seed, servers = listOf(server))
    }

    private fun seed(b: BuiltInKind, name: String, namespace: String?) {
        val rdc = ResourceDefinitionContext.Builder()
            .withGroup(b.group)
            .withVersion(b.version)
            .withKind(b.kind)
            .withPlural(b.plural)
            .withNamespaced(b.namespaced)
            .build()
        val resource = GenericKubernetesResourceBuilder()
            .withApiVersion(if (b.group.isEmpty()) b.version else "${b.group}/${b.version}")
            .withKind(b.kind)
            .withNewMetadata().withName(name).also { if (namespace != null) it.withNamespace(namespace) }.endMetadata()
            .build()
        val op = seed.genericKubernetesResources(rdc)
        if (namespace != null) op.inNamespace(namespace).resource(resource).create() else op.resource(resource).create()
    }

    @Test
    fun `NetworkPolicy and Endpoints have a YAML`() {
        val policy = EditableKinds.builtIn("networking.k8s.io", "NetworkPolicy")!!
        seed(policy, "deny-all", "example-ns")
        val yaml = client.getResourceYaml("NetworkPolicy", "deny-all", "example-ns")
        assertTrue(yaml.contains("deny-all") && yaml.contains("networking.k8s.io/v1"), yaml)

        val endpoints = EditableKinds.builtIn("", "Endpoints")!!
        seed(endpoints, "demo-svc", "example-ns")
        for (label in listOf("Endpoints", "Endpoint")) {
            val text = client.getResourceYaml(label, "demo-svc", "example-ns")
            assertTrue(text.contains("demo-svc") && text.contains("Endpoints"), "$label: $text")
        }
    }

    @Test
    fun `the viewer reads every built-in kind the editor can open`() {
        val notFound = ArrayList<String>()
        for (b in EditableKinds.BUILT_INS) {
            // The CRD object itself has no YAML tab; the table row serves the Apply window only.
            if (b.kind == "CustomResourceDefinition") continue
            val namespace = if (b.namespaced) "example-ns" else null
            seed(b, "demo-1", namespace)
            val yaml = client.getResourceYaml(b.kind, "demo-1", namespace)
            if (!yaml.contains("demo-1") || yaml.startsWith("# ")) notFound += "${b.kind}: ${yaml.lineSequence().first()}"
        }
        assertEquals(emptyList(), notFound)
    }
}
