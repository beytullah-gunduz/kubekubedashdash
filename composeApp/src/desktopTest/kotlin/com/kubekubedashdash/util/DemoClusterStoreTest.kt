package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.VersionInfo
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The demo's store: what fabric8's mixed dispatcher does, plus a way to give a seeded object an age. */
class DemoClusterStoreTest {

    private lateinit var store: DemoClusterStore
    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    @BeforeTest
    fun setUp() {
        val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
        store = DemoClusterStore(responses)
        server = KubernetesMockServer(
            Context(),
            MockWebServer(),
            responses,
            store,
            false,
            VersionInfo.Builder().withMajor("1").withMinor("30").withGitVersion("v1.30.2").build(),
        ).also { it.init() }
        client = server.createClient().use { seed ->
            KubernetesClientBuilder().withConfig(ConfigBuilder(seed.configuration).withRequestRetryBackoffLimit(0).withRequestRetryBackoffInterval(Config.DEFAULT_REQUEST_RETRY_BACKOFFINTERVAL).build()).build()
        }
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(label = "DemoClusterStoreTest", client = client, servers = listOf(server))
    }

    private fun replicaSet(name: String) = ReplicaSetBuilder()
        .withNewMetadata().withNamespace("production").withName(name).endMetadata()
        .withNewSpec().withReplicas(1).endSpec()
        .build()

    private val path = "/apis/apps/v1/namespaces/production/replicasets"

    @Test
    fun `the recorded version answers before the store does`() {
        assertEquals("v1.30.2", client.kubernetesVersion.gitVersion)
    }

    @Test
    fun `an object created in the store is stamped with the current time`() {
        val created = client.apps().replicaSets().inNamespace("production").resource(replicaSet("redis-cache-5f7a3b1d0")).create()

        assertEquals(created.metadata.creationTimestamp, client.apps().replicaSets().inNamespace("production").withName("redis-cache-5f7a3b1d0").get().metadata.creationTimestamp)
        assertNotEquals("2026-01-01T00:00:00Z", created.metadata.creationTimestamp)
    }

    @Test
    fun `a backdated object keeps its identity and shows the new creation time to every read`() {
        val created = client.apps().replicaSets().inNamespace("production").resource(replicaSet("redis-cache-5f7a3b1d0")).create()
        val other = client.apps().replicaSets().inNamespace("production").resource(replicaSet("redis-cache-4c8d2e6b9")).create()

        store.backdate("$path/redis-cache-5f7a3b1d0", "2026-01-01T00:00:00Z")

        val byName = client.apps().replicaSets().inNamespace("production").withName("redis-cache-5f7a3b1d0").get()
        assertEquals("2026-01-01T00:00:00Z", byName.metadata.creationTimestamp)
        assertEquals(created.metadata.uid, byName.metadata.uid)
        assertEquals(1, byName.spec.replicas)
        val listed = client.apps().replicaSets().inNamespace("production").list().items.associateBy { it.metadata.name }
        assertEquals(setOf("redis-cache-5f7a3b1d0", "redis-cache-4c8d2e6b9"), listed.keys)
        assertEquals("2026-01-01T00:00:00Z", listed.getValue("redis-cache-5f7a3b1d0").metadata.creationTimestamp)
        assertEquals(other.metadata.creationTimestamp, listed.getValue("redis-cache-4c8d2e6b9").metadata.creationTimestamp, "another object keeps its stamp")
    }

    @Test
    fun `a backdated object stays backdated after an update`() {
        client.apps().replicaSets().inNamespace("production").resource(replicaSet("redis-cache-5f7a3b1d0")).create()
        store.backdate("$path/redis-cache-5f7a3b1d0", "2026-01-01T00:00:00Z")

        val scaled = client.apps().replicaSets().inNamespace("production").withName("redis-cache-5f7a3b1d0").scale(3)

        assertEquals("2026-01-01T00:00:00Z", scaled.metadata.creationTimestamp)
    }

    @Test
    fun `backdating an object that is not stored fails`() {
        assertFailsWith<IllegalStateException> { store.backdate("$path/redis-cache-5f7a3b1d0", "2026-01-01T00:00:00Z") }
    }
}
