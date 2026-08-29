package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
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
 * Tests for [ReactiveKubeClient.scaleWorkload].
 *
 * Signature: `fun scaleWorkload(kind: String, name: String, namespace: String, replicas: Int): Result<Unit>`
 *
 * The method internally lowercases `kind`, so callers may pass any casing.
 * It uses `.edit {}` / PATCH on the main resource — fully supported by
 * [KubernetesCrudDispatcher] without subresource stubs.
 */
class ReactiveKubeClientScaleWorkloadTest {

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
            // Seed a Deployment, StatefulSet, and ReplicaSet — all in `default`, all replicas=2.
            seed.apps().deployments().inNamespace("default").resource(
                DeploymentBuilder()
                    .withNewMetadata().withName("app").withNamespace("default").endMetadata()
                    .withNewSpec().withReplicas(2).endSpec()
                    .build(),
            ).create()

            seed.apps().statefulSets().inNamespace("default").resource(
                StatefulSetBuilder()
                    .withNewMetadata().withName("app").withNamespace("default").endMetadata()
                    .withNewSpec().withReplicas(2).endSpec()
                    .build(),
            ).create()

            seed.apps().replicaSets().inNamespace("default").resource(
                ReplicaSetBuilder()
                    .withNewMetadata().withName("app").withNamespace("default").endMetadata()
                    .withNewSpec().withReplicas(2).endSpec()
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
        shutdownCleanly(scope, label = "ReactiveKubeClientScaleWorkloadTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `scaleWorkload scales a Deployment to the requested replica count`() {
        val result = client.actions.scaleWorkload("deployment", "app", "default", 5)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val updated = verify.apps().deployments().inNamespace("default").withName("app").get()
            assertEquals(5, updated?.spec?.replicas, "deployment replicas after scale")
        } finally {
            verify.close()
        }
    }

    @Test
    fun `scaleWorkload scales a StatefulSet to the requested replica count`() {
        val result = client.actions.scaleWorkload("statefulset", "app", "default", 3)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val updated = verify.apps().statefulSets().inNamespace("default").withName("app").get()
            assertEquals(3, updated?.spec?.replicas, "statefulset replicas after scale")
        } finally {
            verify.close()
        }
    }

    @Test
    fun `scaleWorkload scales a ReplicaSet to the requested replica count`() {
        val result = client.actions.scaleWorkload("replicaset", "app", "default", 1)
        assertTrue(result.isSuccess, "expected success, got: ${result.exceptionOrNull()}")

        val verify = server.createClient()
        try {
            val updated = verify.apps().replicaSets().inNamespace("default").withName("app").get()
            assertEquals(1, updated?.spec?.replicas, "replicaset replicas after scale")
        } finally {
            verify.close()
        }
    }

    @Test
    fun `scaleWorkload returns failure when replicas is negative`() {
        // The require(replicas >= 0) guard inside scaleWorkload must produce a failure.
        val result = client.actions.scaleWorkload("deployment", "app", "default", -1)
        assertTrue(result.isFailure, "negative replicas must yield Result.failure")
    }

    @Test
    fun `scaleWorkload returns failure for an unsupported kind`() {
        // DaemonSet is not in the when-branch — must throw IllegalArgumentException.
        val result = client.actions.scaleWorkload("daemonset", "app", "default", 2)
        assertTrue(result.isFailure, "unsupported kind must yield Result.failure")
    }
}
