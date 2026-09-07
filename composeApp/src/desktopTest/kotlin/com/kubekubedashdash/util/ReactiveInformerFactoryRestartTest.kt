package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A list the factory built can be restarted on its own. A list parked on
 * `Error` — a start failure, a sync failure, a mapping failure — used to
 * have no way back until the next connection-version bump restarted every
 * flow (review follow-up F3); the screens' Retry button only rebuilt the
 * view model around the same parked flow. `restartListFlow` closes the
 * current informer through the flow's own finally, builds a fresh one and
 * runs it; a flow the factory did not build is left alone and reports so.
 *
 * Faked at the factory's own seam with dynamic-proxy informers, so nothing
 * here opens a socket or reads any real user state.
 */
class ReactiveInformerFactoryRestartTest {

    private class FakeInformer(
        private val runFailure: Exception? = null,
        private val items: List<Pod> = emptyList(),
    ) {
        val closed = CountDownLatch(1)

        @Volatile private var started = false

        private val store: Store<Pod> = newProxy(Store::class.java) { method, _ ->
            when (method.name) {
                "list" -> items
                "listKeys" -> emptyList<String>()
                else -> null
            }
        }

        val proxy: SharedIndexInformer<Pod> = newProxy(SharedIndexInformer::class.java) { method, _ ->
            when (method.name) {
                "run" -> {
                    runFailure?.let { throw it }
                    started = true
                    proxy
                }

                "hasSynced" -> started

                "isRunning" -> started

                "getStore" -> store

                "close", "stop" -> {
                    closed.countDown()
                    null
                }

                else -> error("unexpected informer call: ${method.name}")
            }
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            private fun <T> newProxy(type: Class<*>, handler: (java.lang.reflect.Method, Array<Any?>?) -> Any?): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                when (method.name) {
                    "toString" -> "Fake${type.simpleName}"
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    else -> handler(method, args)
                }
            } as T
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var selectedNamespace: MutableStateFlow<String?>
    private lateinit var factory: ReactiveInformerFactory
    private val informers = CopyOnWriteArrayList<FakeInformer>()

    private fun unusedClient(): KubernetesClient = KubernetesClientBuilder()
        .withConfig(Config.empty().apply { masterUrl = "http://127.0.0.1:1" })
        .build()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(unusedClient(), "cluster-a").getOrThrow()
        selectedNamespace = MutableStateFlow("ns-a")
        factory = ReactiveInformerFactory(scope, manager, selectedNamespace)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveInformerFactoryRestartTest", manager = manager)
    }

    private fun pod(name: String): Pod = PodBuilder().withNewMetadata().withName(name).withNamespace("default").endMetadata().build()

    /** The first attempt fails at start; every later one serves [items]. */
    private fun failThenServe(items: List<Pod>): () -> SharedIndexInformer<Pod> = {
        val fake = if (informers.isEmpty()) FakeInformer(runFailure = IllegalStateException("watch failed at start")) else FakeInformer(items = items)
        informers += fake
        fake.proxy
    }

    @Test
    fun `a cluster-scoped list parked on a start failure restarts into a fresh informer`() = runBlocking {
        val next = failThenServe(listOf(pod("p1")))
        val flow = factory.informer<Pod, String>(inform = { _, _ -> next() }, mapper = { it.metadata.name })
        val collector = scope.launch { flow.collect {} }
        assertEquals(ResourceState.Error("watch failed at start"), withTimeout(10_000) { flow.first { it is ResourceState.Error } })

        assertTrue(restartListFlow(flow), "a factory-built list is restartable")

        assertEquals(ResourceState.Success(listOf("p1")), withTimeout(10_000) { flow.first { it is ResourceState.Success } })
        assertEquals(2, informers.size, "the restart built a fresh informer")
        assertTrue(informers[0].closed.await(10, TimeUnit.SECONDS), "the failed informer was closed by its own exit")
        collector.cancel()
    }

    @Test
    fun `a namespaced list restarts in the same namespace`() = runBlocking {
        val next = failThenServe(listOf(pod("p1")))
        val namespacesSeen = CopyOnWriteArrayList<String?>()
        val flow = factory.namespacedInformer<Pod, String>(
            inform = { _, ns, _ ->
                namespacesSeen += ns
                next()
            },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }
        withTimeout(10_000) { flow.first { it is ResourceState.Error } }

        assertTrue(restartListFlow(flow))

        assertEquals(ResourceState.Success(listOf("p1")), withTimeout(10_000) { flow.first { it is ResourceState.Success } })
        assertEquals(listOf<String?>("ns-a", "ns-a"), namespacesSeen, "a restart keeps the selected namespace")
        collector.cancel()
    }

    @Test
    fun `a list parked on a mapping failure restarts and maps the fresh store`() = runBlocking {
        val flow = factory.informer<Pod, String>(
            inform = { _, _ ->
                val fake = if (informers.isEmpty()) FakeInformer(items = listOf(pod("bad"))) else FakeInformer(items = listOf(pod("good")))
                informers += fake
                fake.proxy
            },
            mapper = { if (it.metadata.name == "bad") error("cannot map bad") else it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }
        assertEquals(ResourceState.Error("cannot map bad"), withTimeout(10_000) { flow.first { it is ResourceState.Error } })

        assertTrue(restartListFlow(flow))

        assertEquals(ResourceState.Success(listOf("good")), withTimeout(10_000) { flow.first { it is ResourceState.Success } })
        assertTrue(informers[0].closed.await(10, TimeUnit.SECONDS))
        collector.cancel()
    }

    @Test
    fun `restarting a healthy list closes its informer and serves the fresh one`() = runBlocking {
        val flow = factory.informer<Pod, String>(
            inform = { _, _ ->
                val fake = if (informers.isEmpty()) FakeInformer(items = listOf(pod("old"))) else FakeInformer(items = listOf(pod("new")))
                informers += fake
                fake.proxy
            },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }
        assertEquals(ResourceState.Success(listOf("old")), withTimeout(10_000) { flow.first { it is ResourceState.Success } })

        assertTrue(restartListFlow(flow))

        assertEquals(ResourceState.Success(listOf("new")), withTimeout(10_000) { flow.first { it == ResourceState.Success(listOf("new")) } })
        assertTrue(informers[0].closed.await(10, TimeUnit.SECONDS), "the replaced informer is closed")
        assertEquals(1L, informers[1].closed.count, "the fresh one stays open")
        collector.cancel()
    }

    @Test
    fun `a flow the factory did not build is not restartable`() {
        val plain = MutableStateFlow<ResourceState<List<String>>>(ResourceState.Loading)

        assertFalse(restartListFlow(plain))
    }
}
