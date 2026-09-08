package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.DeleteOptions
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.http.RecordedRequest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the propagation policy the six controller deletes send (review
 * follow-up F5). Deployment, StatefulSet, DaemonSet, ReplicaSet, Job and
 * CronJob are deleted with `propagationPolicy: Foreground` so their
 * dependants go with them — the "click Delete → it's gone" expectation, and
 * for Job the opposite of kubectl's orphaning default. The CRUD mock has no
 * garbage collector, so [ClusterActionsDeleteKindsTest] stays green with the
 * policy dropped (fabric8's own default is Background, so the change would be
 * silent); this test reads the recorded DELETE body instead. One control
 * shows a plain delete does not carry Foreground, so the assertion
 * discriminates, and one case shows an explicit policy wins over the default.
 * Loopback only; no real user state is touched.
 */
class ClusterActionsDeletePropagationTest {

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
            label = "ClusterActionsDeletePropagationTest",
            manager = manager,
            client = seed,
            servers = listOf(server),
        )
    }

    /** Every request the server has recorded so far, without blocking. */
    private fun drainRecorded(): List<RecordedRequest> {
        val requests = mutableListOf<RecordedRequest>()
        while (true) {
            requests += server.takeRequest(0, TimeUnit.MILLISECONDS) ?: break
        }
        return requests
    }

    /**
     * Seeds one object, deletes it through [ClusterActions.deleteResource] and
     * returns the DeleteOptions the one recorded DELETE carried.
     */
    private fun deleteOptionsSent(kind: String, name: String, policy: DeletionPropagation? = null, seedIt: () -> Unit): DeleteOptions {
        seedIt()
        drainRecorded() // the seeding POST and the connect probe are not under test
        val result = actions.deleteResource(kind, name, "default", propagationPolicy = policy)
        assertTrue(result.isSuccess, "$kind must delete on the CRUD mock, got: ${result.exceptionOrNull()}")
        val deletes = drainRecorded().filter { it.method == "DELETE" }
        assertEquals(1, deletes.size, "$kind: exactly one DELETE expected, recorded paths: ${deletes.map { it.path }}")
        val delete = deletes.single()
        assertTrue(delete.path.orEmpty().endsWith("/$name"), "$kind: the DELETE must address '$name', got path ${delete.path}")
        val body = delete.utf8Body
        assertTrue(body.isNotBlank(), "$kind: the DELETE must carry a DeleteOptions body")
        return Serialization.unmarshal(body, DeleteOptions::class.java)
    }

    private fun assertForeground(kind: String, options: DeleteOptions) = assertEquals(
        DeletionPropagation.FOREGROUND.toString(),
        options.propagationPolicy,
        "$kind must be deleted with Foreground propagation so its dependants go with it",
    )

    @Test
    fun `Deployment is deleted with Foreground propagation`() {
        val options = deleteOptionsSent("Deployment", "dep") {
            seed.apps().deployments().inNamespace("default").resource(
                DeploymentBuilder().withNewMetadata().withName("dep").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertForeground("Deployment", options)
    }

    @Test
    fun `StatefulSet is deleted with Foreground propagation`() {
        val options = deleteOptionsSent("StatefulSet", "ss") {
            seed.apps().statefulSets().inNamespace("default").resource(
                StatefulSetBuilder().withNewMetadata().withName("ss").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertForeground("StatefulSet", options)
    }

    @Test
    fun `DaemonSet is deleted with Foreground propagation`() {
        val options = deleteOptionsSent("DaemonSet", "ds") {
            seed.apps().daemonSets().inNamespace("default").resource(
                DaemonSetBuilder().withNewMetadata().withName("ds").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertForeground("DaemonSet", options)
    }

    @Test
    fun `ReplicaSet is deleted with Foreground propagation`() {
        val options = deleteOptionsSent("ReplicaSet", "rs") {
            seed.apps().replicaSets().inNamespace("default").resource(
                ReplicaSetBuilder().withNewMetadata().withName("rs").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertForeground("ReplicaSet", options)
    }

    @Test
    fun `Job is deleted with Foreground propagation, not kubectl's orphaning default`() {
        val options = deleteOptionsSent("Job", "job") {
            seed.batch().v1().jobs().inNamespace("default").resource(
                JobBuilder().withNewMetadata().withName("job").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertForeground("Job", options)
    }

    @Test
    fun `CronJob is deleted with Foreground propagation`() {
        val options = deleteOptionsSent("CronJob", "cj") {
            seed.batch().v1().cronjobs().inNamespace("default").resource(
                CronJobBuilder().withNewMetadata().withName("cj").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertForeground("CronJob", options)
    }

    @Test
    fun `control - a plain delete does not carry Foreground, so the pin discriminates`() {
        val options = deleteOptionsSent("Service", "svc") {
            seed.services().inNamespace("default").resource(
                ServiceBuilder().withNewMetadata().withName("svc").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertNotEquals(
            DeletionPropagation.FOREGROUND.toString(),
            options.propagationPolicy,
            "a kind without a foreground case must not send Foreground, or the six assertions above prove nothing",
        )
    }

    @Test
    fun `an explicit propagation policy wins over the Foreground default`() {
        val options = deleteOptionsSent("Deployment", "dep-bg", policy = DeletionPropagation.BACKGROUND) {
            seed.apps().deployments().inNamespace("default").resource(
                DeploymentBuilder().withNewMetadata().withName("dep-bg").withNamespace("default").endMetadata().build(),
            ).create()
        }
        assertEquals(DeletionPropagation.BACKGROUND.toString(), options.propagationPolicy, "the caller's policy must reach the request")
    }
}
