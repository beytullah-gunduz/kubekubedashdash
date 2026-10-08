package com.kubekubedashdash.util

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.Node
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.StatusBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Queue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An RBAC refusal is not a connection failure. A namespace-limited account
 * with All Namespaces selected gets a 403 on every cluster-scope list; each
 * denied informer counted toward the shared failure threshold, so a screen
 * with two of them crossed it on one Retry and the reconnect overlay showed
 * fabric8's raw refusal text, which names the caller. The factory is driven
 * without ReactiveKubeClient, so no liveness probe resets the counter between
 * attempts. Loopback mock only; the answers are canned on the list request.
 */
class ReactiveInformerFactoryForbiddenTest {

    private val podsPath = "/api/v1/pods?resourceVersion=0"
    private val nodesPath = "/api/v1/nodes?resourceVersion=0"

    /** Four attempts: one past the manager's three-consecutive-failures threshold. */
    private val attempts = 4

    private lateinit var responses: MutableMap<ServerRequest, Queue<ServerResponse>>
    private lateinit var server: KubernetesMockServer
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var factory: ReactiveInformerFactory
    private val observedErrors = CopyOnWriteArrayList<String?>()
    private val collectors = mutableListOf<Job>()

    @BeforeTest
    fun setUp() {
        responses = HashMap()
        server = KubernetesMockServer(Context(), MockWebServer(), responses, KubernetesMixedDispatcher(responses), false)
        server.init()
        // No request retries: fabric8 retries a 5xx for ~100 s by default.
        val config = server.createClient().use { ConfigBuilder(it.configuration).withRequestRetryBackoffLimit(0).build() }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(KubernetesClientBuilder().withConfig(config).build(), "cluster-a").getOrThrow()
        factory = ReactiveInformerFactory(scope, manager, MutableStateFlow<NamespaceScope>(NamespaceScope.All))
        collectors += scope.launch { manager.connectionError.collect { observedErrors += it } }
    }

    @AfterTest
    fun tearDown() {
        collectors.forEach { it.cancel() }
        shutdownCleanly(scope, label = "ReactiveInformerFactoryForbiddenTest", manager = manager, servers = listOf(server))
    }

    private fun answer(path: String, code: Int, reason: String, message: String) {
        server.expect().get().withPath(path)
            .andReturn(code, StatusBuilder().withCode(code).withReason(reason).withMessage(message).build())
            .always()
    }

    private fun deny(path: String, resource: String) = answer(path, 403, "Forbidden", """$resource is forbidden: User "test-user" cannot list resource "$resource" in API group "" at the cluster scope""")

    private fun podsInEveryNamespace() = factory.namespacedInformer<Pod, String>(
        inform = { k, _, h -> k.pods().inAnyNamespace().runnableInformer(0L).addEventHandler(h) },
        mapper = { it.metadata.name },
    )

    private fun nodes() = factory.informer<Node, String>(
        inform = { k, h -> k.nodes().runnableInformer(0L).addEventHandler(h) },
        mapper = { it.metadata.name },
    )

    /** Waits for the next request to [path], up to five seconds. */
    private fun awaitRequest(path: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val request = server.takeRequest(200, TimeUnit.MILLISECONDS) ?: continue
            if (request.path == path) return true
        }
        return false
    }

    /**
     * Collects [list] and makes it fail [attempts] times: the first list,
     * then a restart (a screen's Retry) after each Error. Returns the last Error.
     */
    private suspend fun failRepeatedly(list: StateFlow<ResourceState<List<String>>>, path: String): ResourceState.Error {
        collectors += scope.launch { list.collect {} }
        repeat(attempts) { attempt ->
            if (attempt > 0) assertTrue(restartListFlow(list), "the list is restartable")
            assertTrue(awaitRequest(path), "attempt ${attempt + 1} listed")
            withTimeout(5_000) { while (list.value !is ResourceState.Error) delay(20) }
            // The answer is immediate on loopback; let the failure path finish
            // before the next restart cancels it.
            delay(100)
        }
        return assertIs<ResourceState.Error>(list.value)
    }

    private fun assertNeverSetConnectionError() {
        val nonNull = observedErrors.filterNotNull()
        assertTrue(nonNull.isEmpty(), "an RBAC refusal must never set connectionError (saw: $nonNull)")
    }

    @Test
    fun `a forbidden list across every namespace never sets connectionError, however often it is retried`() = runBlocking {
        deny(podsPath, "pods")

        val error = failRepeatedly(podsInEveryNamespace(), podsPath)
        delay(300)

        assertTrue(isForbidden(error.message), "the screen still gets the refusal as its own Error: ${error.message}")
        assertNeverSetConnectionError()
    }

    @Test
    fun `a forbidden cluster-scoped list never sets connectionError, however often it is retried`() = runBlocking {
        deny(nodesPath, "nodes")

        val error = failRepeatedly(nodes(), nodesPath)
        delay(300)

        assertTrue(isForbidden(error.message), "the screen still gets the refusal as its own Error: ${error.message}")
        assertNeverSetConnectionError()
    }

    @Test
    fun `a list failure that is not a refusal still counts toward connectionError`() = runBlocking {
        answer(podsPath, 500, "InternalError", "etcdserver: request timed out")

        val error = failRepeatedly(podsInEveryNamespace(), podsPath)

        assertTrue(!isForbidden(error.message), "precondition: not read as a refusal: ${error.message}")
        val tripped = withTimeout(10_000) { manager.connectionError.first { it != null } }
        assertEquals(error.message, tripped, "the threshold reports the failure's own message")
    }
}
