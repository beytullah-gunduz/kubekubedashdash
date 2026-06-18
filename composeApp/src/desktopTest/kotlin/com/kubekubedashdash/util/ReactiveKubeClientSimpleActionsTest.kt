package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.NodeBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobTemplateSpecBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the core mutating actions in [ReactiveKubeClient]:
 *
 * - `deleteResource(kind, name, namespace, ...): Result<Unit>`
 * - `cordonNode(name, unschedulable): Result<Unit>`
 * - `setCronJobSuspend(name, namespace, suspend): Result<Unit>`
 * - `restartWorkload(kind, name, namespace): Result<Unit>`
 *
 * All operations use main-resource GET/PATCH paths fully covered by
 * [KubernetesCrudDispatcher] — no subresource stubs required.
 */
class ReactiveKubeClientSimpleActionsTest {

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

        val seed = server.createClient()
        try {
            // Pod for namespace-scoped delete test.
            seed.pods().inNamespace("default").resource(
                PodBuilder()
                    .withNewMetadata().withName("p").withNamespace("default").endMetadata()
                    .build(),
            ).create()

            // Namespace for cluster-scoped delete test.
            seed.namespaces().resource(
                NamespaceBuilder().withNewMetadata().withName("ns").endMetadata().build(),
            ).create()

            // Node for cordon test.
            seed.nodes().resource(
                NodeBuilder()
                    .withNewMetadata().withName("node-a").endMetadata()
                    .withNewSpec().withUnschedulable(false).endSpec()
                    .build(),
            ).create()

            // CronJob for suspend test.
            seed.batch().v1().cronjobs().inNamespace("default").resource(
                CronJobBuilder()
                    .withNewMetadata().withName("nightly").withNamespace("default").endMetadata()
                    .withNewSpec()
                    .withSuspend(false)
                    .withJobTemplate(
                        JobTemplateSpecBuilder()
                            .withNewSpec()
                            .withNewTemplate()
                            .withNewSpec()
                            .addNewContainer().withName("w").withImage("busybox").endContainer()
                            .withRestartPolicy("Never")
                            .endSpec()
                            .endTemplate()
                            .endSpec()
                            .build(),
                    )
                    .endSpec()
                    .build(),
            ).create()

            // Deployment for restart test.
            seed.apps().deployments().inNamespace("default").resource(
                DeploymentBuilder()
                    .withNewMetadata().withName("app").withNamespace("default").endMetadata()
                    .withNewSpec()
                    .withReplicas(1)
                    .withNewTemplate()
                    .withNewMetadata().endMetadata()
                    .withNewSpec()
                    .addNewContainer().withName("main").withImage("nginx").endContainer()
                    .endSpec()
                    .endTemplate()
                    .endSpec()
                    .build(),
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

    // ── deleteResource ────────────────────────────────────────────────────────────

    @Test
    fun `deleteResource deletes a namespaced pod and it is gone`() {
        val result = client.actions.deleteResource("pod", "p", "default")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val pod = verify.pods().inNamespace("default").withName("p").get()
            assertNull(pod, "pod 'p' must not exist after deleteResource")
        } finally {
            verify.close()
        }
    }

    @Test
    fun `deleteResource deletes a cluster-scoped namespace`() {
        val result = client.actions.deleteResource("namespace", "ns", null)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val ns = verify.namespaces().withName("ns").get()
            assertNull(ns, "namespace 'ns' must not exist after deleteResource")
        } finally {
            verify.close()
        }
    }

    // ── cordonNode ────────────────────────────────────────────────────────────────

    @Test
    fun `cordonNode sets unschedulable=true then unschedulable=false`() {
        val cordonResult = client.actions.cordonNode("node-a", true)
        assertTrue(cordonResult.isSuccess, "cordon expected success, got: ${cordonResult.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val cordoned = verify.nodes().withName("node-a").get()
            assertNotNull(cordoned, "node-a must still exist")
            assertEquals(true, cordoned.spec?.unschedulable, "node must be unschedulable after cordon")
        } finally {
            verify.close()
        }

        val uncordonResult = client.actions.cordonNode("node-a", false)
        assertTrue(uncordonResult.isSuccess, "uncordon expected success, got: ${uncordonResult.exceptionOrNull()}")

        val verify2 = server.createClient()
        try {
            val uncordoned = verify2.nodes().withName("node-a").get()
            assertNotNull(uncordoned, "node-a must still exist after uncordon")
            // fabric8 NodeSpec.unschedulable is nullable Boolean; false and null are
            // both "schedulable" — accept either.
            val isUnschedulable = uncordoned.spec?.unschedulable == true
            assertEquals(false, isUnschedulable, "node must be schedulable after uncordon")
        } finally {
            verify2.close()
        }
    }

    // ── setCronJobSuspend ─────────────────────────────────────────────────────────

    @Test
    fun `setCronJobSuspend suspends a CronJob`() {
        val result = client.actions.setCronJobSuspend("nightly", "default", true)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val cj = verify.batch().v1().cronjobs().inNamespace("default").withName("nightly").get()
            assertNotNull(cj, "CronJob must still exist")
            assertEquals(true, cj.spec?.suspend, "CronJob spec.suspend must be true")
        } finally {
            verify.close()
        }
    }

    // ── restartWorkload ───────────────────────────────────────────────────────────

    @Test
    fun `restartWorkload patches the kubectl restart annotation on a Deployment`() {
        val result = client.actions.restartWorkload("deployment", "app", "default")
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val deploy = verify.apps().deployments().inNamespace("default").withName("app").get()
            assertNotNull(deploy, "deployment must still exist")
            val templateAnnotations = deploy.spec?.template?.metadata?.annotations ?: emptyMap()
            assertTrue(
                templateAnnotations.containsKey("kubectl.kubernetes.io/restartedAt"),
                "template annotations must contain 'kubectl.kubernetes.io/restartedAt' after restart",
            )
        } finally {
            verify.close()
        }
    }
}
