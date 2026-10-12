package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The ReplicaSet rows carry the creation time the history charts mark rollouts with. */
class ReactiveKubeClientReplicaSetsTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var storedTimestamp: String

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        val seed = server.createClient()
        try {
            val created = seed.apps().replicaSets().inNamespace("default").resource(
                ReplicaSetBuilder()
                    .withNewMetadata()
                    .withName("redis-cache-5f7a3b1d0")
                    .withNamespace("default")
                    .withCreationTimestamp("2026-10-12T08:00:00Z")
                    .addToAnnotations("deployment.kubernetes.io/revision", "3")
                    .withOwnerReferences(
                        OwnerReferenceBuilder().withApiVersion("apps/v1").withKind("Deployment").withName("redis-cache").withUid("uid-deploy-1").withController(true).build(),
                    )
                    .endMetadata()
                    .withNewSpec().withReplicas(1).endSpec()
                    .build(),
            ).create()
            // The CRUD mock may stamp its own time: the row must carry whatever the server stored.
            storedTimestamp = created.metadata.creationTimestamp.orEmpty()
        } finally {
            seed.close()
        }

        val config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(KubernetesClientBuilder().withConfig(config).build(), "example-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientReplicaSetsTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `a ReplicaSet row carries the creation time the server stored, beside its owners and annotations`() = runBlocking<Unit> {
        val state = withTimeout(15_000) { client.replicaSets.first { it is ResourceState.Success && it.data.isNotEmpty() } }

        val row = (state as ResourceState.Success).data.single()
        assertTrue(storedTimestamp.isNotBlank(), "the mock stored no creation time")
        assertEquals(storedTimestamp, row.creationTimestamp)
        assertEquals("3", row.annotations["deployment.kubernetes.io/revision"])
        assertEquals(listOf("uid-deploy-1"), row.owners.map { it.uid })
    }
}
