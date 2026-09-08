package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A delete the API accepts can leave the object behind: a claim in use or a
 * bound volume keeps its protection finalizer and sits in Terminating, and
 * the screen toasted "Deleted" regardless (review follow-up F6).
 * [ClusterActions.deleteResourceReporting] looks once more after the delete
 * and reports what is left, ignoring the garbage collector's own finalizers;
 * [ClusterActions.deleteResource] keeps its contract. The CRUD mock keeps an
 * object with finalizers and stamps its deletion timestamp, as the API
 * server does. Loopback only; no real user state.
 */
class ClusterActionsDeleteOutcomeTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var actions: ClusterActions

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        seed = server.createClient()
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        actions = ClusterActions(manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "ClusterActionsDeleteOutcomeTest", manager = manager, client = seed, servers = listOf(server))
    }

    private fun seedClaim(name: String, vararg finalizers: String) {
        seed.persistentVolumeClaims().inNamespace("ns").resource(
            PersistentVolumeClaimBuilder().withNewMetadata().withName(name).withNamespace("ns").withFinalizers(*finalizers).endMetadata().build(),
        ).create()
    }

    @Test
    fun `a claim behind its protection finalizer is reported Terminating and stays`() {
        seedClaim("data", "kubernetes.io/pvc-protection")

        val outcome = actions.deleteResourceReporting("PersistentVolumeClaim", "data", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Terminating(listOf("kubernetes.io/pvc-protection")), outcome)
        val left = assertNotNull(seed.persistentVolumeClaims().inNamespace("ns").withName("data").get(), "the claim stays until the finalizer clears")
        assertNotNull(left.metadata.deletionTimestamp, "the API stamped the deletion")
    }

    @Test
    fun `a free claim is reported Gone and is gone`() {
        seedClaim("scratch")

        val outcome = actions.deleteResourceReporting("PersistentVolumeClaim", "scratch", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Gone, outcome)
        assertNull(seed.persistentVolumeClaims().inNamespace("ns").withName("scratch").get())
    }

    @Test
    fun `a bound volume behind its protection finalizer is reported Terminating`() {
        seed.persistentVolumes().resource(
            PersistentVolumeBuilder().withNewMetadata().withName("pv-1").withFinalizers("kubernetes.io/pv-protection").endMetadata().build(),
        ).create()

        val outcome = actions.deleteResourceReporting("PersistentVolume", "pv-1", null).getOrThrow()

        assertEquals(DeleteOutcome.Terminating(listOf("kubernetes.io/pv-protection")), outcome)
    }

    @Test
    fun `the garbage collector's foreground finalizer alone reads as Gone`() {
        seed.apps().deployments().inNamespace("ns").resource(
            DeploymentBuilder().withNewMetadata().withName("web").withNamespace("ns").withFinalizers("foregroundDeletion").endMetadata().build(),
        ).create()

        val outcome = actions.deleteResourceReporting("Deployment", "web", "ns").getOrThrow()

        assertEquals(DeleteOutcome.Gone, outcome, "foregroundDeletion is the collector's own bookkeeping, not a wait the user can act on")
        assertNotNull(seed.apps().deployments().inNamespace("ns").withName("web").get()?.metadata?.deletionTimestamp, "the mock kept it, as the API would until dependants are gone")
    }

    @Test
    fun `a custom resource behind its controller's finalizer is reported Terminating through the generic branch`() {
        val rdc = ResourceDefinitionContext.Builder().withGroup("widgets.example").withVersion("v1").withKind("Widget").withPlural("widgets").withNamespaced(true).build()
        seed.genericKubernetesResources(rdc).inNamespace("ns").resource(
            GenericKubernetesResourceBuilder().withApiVersion("widgets.example/v1").withKind("Widget")
                .withNewMetadata().withName("w1").withNamespace("ns").withFinalizers("widgets.example/cleanup").endMetadata().build(),
        ).create()

        val outcome = actions.deleteResourceReporting("Widget", "w1", "ns", group = "widgets.example", version = "v1", plural = "widgets").getOrThrow()

        assertEquals(DeleteOutcome.Terminating(listOf("widgets.example/cleanup")), outcome)
    }

    @Test
    fun `the plain delete keeps its contract on a protected claim`() {
        seedClaim("data", "kubernetes.io/pvc-protection")

        val result = actions.deleteResource("PersistentVolumeClaim", "data", "ns")

        assertTrue(result.isSuccess, "the API accepted the delete; what is left is the reporting variant's business")
        assertNotNull(seed.persistentVolumeClaims().inNamespace("ns").withName("data").get())
    }
}
