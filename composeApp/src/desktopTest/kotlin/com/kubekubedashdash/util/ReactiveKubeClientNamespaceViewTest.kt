package com.kubekubedashdash.util

import com.kubekubedashdash.models.GenericResourceInfo
import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.StatusBuilder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Queue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The namespace picker's names and the Namespaces table's rows come from
 * one informer (review follow-up F17). Before, two informers watched the
 * same resource: a denied list parked both, and the table's Retry revived
 * only its own. Loopback mock only; the denial is a canned 403 on the
 * informer's list request.
 */
class ReactiveKubeClientNamespaceViewTest {

    private val listPath = "/api/v1/namespaces?resourceVersion=0"

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var seed: KubernetesClient
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private val collectors = mutableListOf<Job>()

    @BeforeTest
    fun setUp() {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        seed = server.createClient()
        for (name in listOf("ns-a", "ns-b")) {
            seed.namespaces().resource(NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build()).create()
        }
        drainRequests()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "cluster-a").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        collectors.forEach { it.cancel() }
        shutdownCleanly(scope, label = "ReactiveKubeClientNamespaceViewTest", manager = manager, client = seed, servers = listOf(server))
    }

    private fun collectBoth() {
        collectors += scope.launch { client.namespaces.collect {} }
        collectors += scope.launch { client.namespaceNames.collect {} }
    }

    private fun drainRequests(): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val request = server.takeRequest(0, TimeUnit.MILLISECONDS) ?: break
            paths += request.path ?: ""
        }
        return paths
    }

    /** Waits for the next list request the informer sends, up to five seconds. */
    private fun awaitListRequest(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (request.path == listPath) return true
        }
        return false
    }

    private fun denyList(times: Int) {
        server.expect().get().withPath(listPath)
            .andReturn(403, StatusBuilder().withCode(403).withReason("Forbidden").withMessage("namespaces is forbidden").build())
            .times(times)
    }

    private fun namesOf(state: ResourceState<*>): Set<String> = (assertIs<ResourceState.Success<*>>(state).data as List<*>).map { if (it is GenericResourceInfo) it.name else it as String }.toSet()

    @Test
    fun `the picker's names are a view of the table's list, served by one informer`() = runBlocking {
        collectBoth()
        val rows = withTimeout(10_000) { client.namespaces.first { it is ResourceState.Success } }
        val names = withTimeout(10_000) { client.namespaceNames.first { it is ResourceState.Success } }
        delay(300)

        assertEquals(namesOf(rows), namesOf(names))
        assertTrue("ns-a" in namesOf(names) && "ns-b" in namesOf(names))
        assertEquals(1, drainRequests().count { it == listPath }, "one informer, one list request")
    }

    @Test
    fun `restarting the names view restarts the informer both views follow`() = runBlocking {
        collectBoth()
        withTimeout(10_000) { client.namespaceNames.first { it is ResourceState.Success } }
        delay(300)
        drainRequests()

        assertTrue(restartListFlow(client.namespaceNames), "the names view is restartable")

        assertTrue(awaitListRequest(), "the restart listed again")
        val names = withTimeout(10_000) { client.namespaceNames.first { it is ResourceState.Success } }
        assertTrue("ns-a" in namesOf(names))
    }

    @Test
    fun `a denied list parks both views and the table's retry revives both`() = runBlocking {
        // Two denials: enough to park both informers as they were before the
        // fix; with one informer, its first retry meets the second denial and
        // the next retry succeeds. Only the picker following the table is asserted.
        denyList(times = 2)
        collectBoth()
        assertIs<ResourceState.Error>(withTimeout(10_000) { client.namespaces.first { it !is ResourceState.Loading } })
        assertIs<ResourceState.Error>(withTimeout(10_000) { client.namespaceNames.first { it !is ResourceState.Loading } })
        drainRequests()

        var attempts = 0
        while (client.namespaces.value !is ResourceState.Success && attempts < 4) {
            attempts++
            assertTrue(restartListFlow(client.namespaces), "the table's list is restartable")
            assertTrue(awaitListRequest(), "restart $attempts listed again")
            withTimeout(5_000) { while (client.namespaces.value is ResourceState.Loading) delay(50) }
        }
        assertIs<ResourceState.Success<*>>(client.namespaces.value, "the table recovered within $attempts restarts")

        delay(300)
        val names = client.namespaceNames.value
        assertIs<ResourceState.Success<*>>(names, "the picker's view recovered with the table")
        assertEquals(namesOf(client.namespaces.value), namesOf(names))
    }
}
