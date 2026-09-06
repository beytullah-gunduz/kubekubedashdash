package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.EndpointsBuilder
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every kind the sidebar lists through `GenericResourceScreen` WITHOUT an
 * API group must be deletable by its bare kind string — the exact string the
 * row menu, the bulk verb and the command palette pass to
 * [ClusterActions.deleteResource]. Each case seeds one object on a CRUD mock
 * server, deletes it with that string, and checks it is gone.
 *
 * `Deployment` is included as a control: it was already supported and uses
 * foreground propagation, so it proves the CRUD mock accepts a DELETE that
 * carries DeleteOptions. The last case pins the other direction: a
 * group-qualified kind that happens to reuse a built-in name must reach the
 * generic branch, never the typed case for the built-in. Loopback only; no
 * real user state is touched.
 */
class ClusterActionsDeleteKindsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var seed: KubernetesClient
    private lateinit var actions: ClusterActions

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
        seed = server.createClient()
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        actions = ClusterActions(manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            label = "ClusterActionsDeleteKindsTest",
            manager = manager,
            client = seed,
            servers = listOf(server),
        )
    }

    private fun deleteAndAssertGone(kind: String, name: String, namespace: String?, stillThere: () -> Any?) {
        val result = actions.deleteResource(kind, name, namespace)
        assertTrue(result.isSuccess, "$kind must be deletable by its bare kind string, got: ${result.exceptionOrNull()}")
        assertNull(stillThere(), "$kind '$name' must be gone after deleteResource")
    }

    @Test
    fun `control - Deployment (already supported, foreground propagation) deletes on the CRUD mock`() {
        seed.apps().deployments().inNamespace("default").resource(
            DeploymentBuilder().withNewMetadata().withName("dep").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("Deployment", "dep", "default") {
            seed.apps().deployments().inNamespace("default").withName("dep").get()
        }
    }

    @Test
    fun `StatefulSet deletes by bare kind`() {
        seed.apps().statefulSets().inNamespace("default").resource(
            StatefulSetBuilder().withNewMetadata().withName("ss").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("StatefulSet", "ss", "default") {
            seed.apps().statefulSets().inNamespace("default").withName("ss").get()
        }
    }

    @Test
    fun `DaemonSet deletes by bare kind`() {
        seed.apps().daemonSets().inNamespace("default").resource(
            DaemonSetBuilder().withNewMetadata().withName("ds").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("DaemonSet", "ds", "default") {
            seed.apps().daemonSets().inNamespace("default").withName("ds").get()
        }
    }

    @Test
    fun `ReplicaSet deletes by bare kind`() {
        seed.apps().replicaSets().inNamespace("default").resource(
            ReplicaSetBuilder().withNewMetadata().withName("rs").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("ReplicaSet", "rs", "default") {
            seed.apps().replicaSets().inNamespace("default").withName("rs").get()
        }
    }

    @Test
    fun `Ingress deletes by bare kind`() {
        seed.network().v1().ingresses().inNamespace("default").resource(
            IngressBuilder().withNewMetadata().withName("ing").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("Ingress", "ing", "default") {
            seed.network().v1().ingresses().inNamespace("default").withName("ing").get()
        }
    }

    @Test
    fun `Endpoints deletes by the router's singular kind string`() {
        seed.endpoints().inNamespace("default").resource(
            EndpointsBuilder().withNewMetadata().withName("ep").withNamespace("default").endMetadata().build(),
        ).create()
        // WorkspaceRouters labels the screen "Endpoint" (singular); the API kind is "Endpoints".
        deleteAndAssertGone("Endpoint", "ep", "default") {
            seed.endpoints().inNamespace("default").withName("ep").get()
        }
    }

    @Test
    fun `Endpoints deletes by the API kind string`() {
        seed.endpoints().inNamespace("default").resource(
            EndpointsBuilder().withNewMetadata().withName("ep2").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("Endpoints", "ep2", "default") {
            seed.endpoints().inNamespace("default").withName("ep2").get()
        }
    }

    @Test
    fun `NetworkPolicy deletes by bare kind`() {
        seed.network().v1().networkPolicies().inNamespace("default").resource(
            NetworkPolicyBuilder().withNewMetadata().withName("np").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("NetworkPolicy", "np", "default") {
            seed.network().v1().networkPolicies().inNamespace("default").withName("np").get()
        }
    }

    @Test
    fun `PersistentVolume deletes by bare kind (cluster-scoped)`() {
        seed.persistentVolumes().resource(
            PersistentVolumeBuilder().withNewMetadata().withName("pv").endMetadata().build(),
        ).create()
        deleteAndAssertGone("PersistentVolume", "pv", null) {
            seed.persistentVolumes().withName("pv").get()
        }
    }

    @Test
    fun `PersistentVolumeClaim deletes by bare kind`() {
        seed.persistentVolumeClaims().inNamespace("default").resource(
            PersistentVolumeClaimBuilder().withNewMetadata().withName("pvc").withNamespace("default").endMetadata().build(),
        ).create()
        deleteAndAssertGone("PersistentVolumeClaim", "pvc", "default") {
            seed.persistentVolumeClaims().inNamespace("default").withName("pvc").get()
        }
    }

    @Test
    fun `StorageClass deletes by bare kind (cluster-scoped)`() {
        seed.storage().v1().storageClasses().resource(
            StorageClassBuilder().withNewMetadata().withName("sc").endMetadata().build(),
        ).create()
        deleteAndAssertGone("StorageClass", "sc", null) {
            seed.storage().v1().storageClasses().withName("sc").get()
        }
    }

    @Test
    fun `a namespaced kind without a namespace fails clearly instead of hitting the API`() {
        val result = actions.deleteResource("StatefulSet", "ss", null)
        assertTrue(result.isFailure, "a namespaced kind must refuse a null namespace")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("requires a namespace") == true,
            "the failure must name the missing namespace, got: ${result.exceptionOrNull()}",
        )
    }

    @Test
    fun `a group-qualified kind that reuses a built-in name never deletes the built-in object`() {
        // A CRD may reuse a built-in kind name inside its own API group, and the
        // sidebar lists it through the same screen — which forwards the CRD's
        // group/version/plural. That call must take the generic branch.
        val rdc = ResourceDefinitionContext.Builder()
            .withGroup("widgets.example")
            .withVersion("v1")
            .withKind("StatefulSet")
            .withPlural("statefulsets")
            .withNamespaced(true)
            .build()
        seed.apps().statefulSets().inNamespace("default").resource(
            StatefulSetBuilder().withNewMetadata().withName("shared").withNamespace("default").endMetadata().build(),
        ).create()
        val custom = GenericKubernetesResourceBuilder()
            .withApiVersion("widgets.example/v1")
            .withKind("StatefulSet")
            .withNewMetadata().withName("shared").withNamespace("default").endMetadata()
            .build()
        seed.genericKubernetesResources(rdc).inNamespace("default").resource(custom).create()

        val result = actions.deleteResource(
            "StatefulSet",
            "shared",
            "default",
            group = "widgets.example",
            version = "v1",
            plural = "statefulsets",
        )

        assertTrue(result.isSuccess, "the custom object must delete through the generic branch, got: ${result.exceptionOrNull()}")
        assertNull(
            seed.genericKubernetesResources(rdc).inNamespace("default").withName("shared").get(),
            "the custom StatefulSet must be gone",
        )
        assertNotNull(
            seed.apps().statefulSets().inNamespace("default").withName("shared").get(),
            "the built-in StatefulSet of the same name must survive a delete addressed to the custom one",
        )
    }
}
