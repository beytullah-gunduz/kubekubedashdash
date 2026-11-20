package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Queue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Secrets list rows against a loopback mock server: counts and type survive, the manifest annotation does not. */
class ReactiveKubeClientSecretsListTest {

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var seed: KubernetesClient
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private val collectors = mutableListOf<Job>()

    private fun startClient() {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        seed = server.createClient()
        seed.secrets().inNamespace("example-namespace").resource(
            SecretBuilder()
                .withNewMetadata()
                .withName("example-secret")
                .withNamespace("example-namespace")
                .addToAnnotations(SecretSummaryStore.LAST_APPLIED_ANNOTATION, """{"data":{"a":"c2VjcmV0"}}""")
                .addToAnnotations("example.com/keep", "yes")
                .endMetadata()
                .withType("Opaque")
                .addToData("a", "c2VjcmV0")
                .addToData("b", "c2VjcmV0Mg==")
                .build(),
        ).create()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        collectors.forEach { it.cancel() }
        shutdownCleanly(scope, label = "ReactiveKubeClientSecretsListTest", manager = manager, client = seed, servers = listOf(server))
    }

    @Test
    fun `the Secrets list row keeps Type and the Data count and drops the last-applied annotation`() = runBlocking {
        startClient()

        collectors += scope.launch { client.secrets.collect {} }
        val state = withTimeout(10_000) { client.secrets.first { it is ResourceState.Success } }
        val rows = assertIs<ResourceState.Success<List<GenericResourceInfo>>>(state).data

        val row = rows.single { it.name == "example-secret" }
        assertEquals("2", row.extraColumns["Data"])
        assertEquals("Opaque", row.extraColumns["Type"])
        assertTrue(SecretSummaryStore.LAST_APPLIED_ANNOTATION !in row.annotations)
        assertEquals("yes", row.annotations["example.com/keep"])
    }
}
