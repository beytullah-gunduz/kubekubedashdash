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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The factory owns the whole informer lifecycle, including its START. The
 * `inform` lambda hands back an informer that has NOT been run; the factory
 * runs it inside the same try/finally that closes it, so an informer whose
 * initial list-and-watch fails is stopped like any other exit. Before this,
 * the lambda called fabric8's `inform(handler)` — build and run in one —
 * and a start failure threw out of it with no handle to stop: fabric8 only
 * registers an informer with the client for closing once it has started,
 * and its reflector's "will stop" branch completes two futures but cancels
 * neither the repeating watch-timeout task scheduled before the watch was
 * answered nor the processor's executor (review follow-up F2).
 *
 * Faked at the factory's own seam with a dynamic-proxy informer, so nothing
 * here opens a socket or reads any real user state.
 */
class ReactiveInformerFactoryStartFailureTest {

    /** One fake informer: `run()` succeeds or throws; what the factory must call is recorded. */
    private class FakeInformer(
        private val runFailure: Exception? = null,
        private val items: List<Pod> = emptyList(),
    ) {
        val runCalls = AtomicInteger()
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
                    runCalls.incrementAndGet()
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

    /** The factory only needs `isConnected`; the fake `inform` never touches the client. */
    private fun unusedClient(): KubernetesClient = KubernetesClientBuilder()
        .withConfig(Config.empty().apply { masterUrl = "http://127.0.0.1:1" })
        .build()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(unusedClient(), "cluster-a").getOrThrow()
        selectedNamespace = MutableStateFlow(null)
        factory = ReactiveInformerFactory(scope, manager, selectedNamespace)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveInformerFactoryStartFailureTest", manager = manager)
    }

    private fun pod(name: String): Pod = PodBuilder().withNewMetadata().withName(name).withNamespace("default").endMetadata().build()

    @Test
    fun `a cluster-scoped informer whose start fails is stopped and the flow reports the start error`() = runBlocking {
        val flow = factory.informer<Pod, String>(
            inform = { _, _ -> FakeInformer(runFailure = IllegalStateException("watch failed at start")).also { informers += it }.proxy },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }

        val state = withTimeout(10_000) { flow.first { it is ResourceState.Error } }

        assertEquals(ResourceState.Error("watch failed at start"), state, "the start failure itself is what the flow reports")
        val fake = informers.single()
        assertEquals(1, fake.runCalls.get(), "the factory runs the informer it was handed, exactly once")
        assertTrue(fake.closed.await(10, TimeUnit.SECONDS), "an informer whose start failed must be stopped, or its reflector's timeout task and processor leak")
        collector.cancel()
    }

    @Test
    fun `a namespaced informer whose start fails is stopped and the flow reports the start error`() = runBlocking {
        val flow = factory.namespacedInformer<Pod, String>(
            inform = { _, _, _ -> FakeInformer(runFailure = IllegalStateException("list forbidden")).also { informers += it }.proxy },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }

        val state = withTimeout(10_000) { flow.first { it is ResourceState.Error } }

        assertEquals(ResourceState.Error("list forbidden"), state)
        val fake = informers.single()
        assertEquals(1, fake.runCalls.get())
        assertTrue(fake.closed.await(10, TimeUnit.SECONDS))
        collector.cancel()
    }

    @Test
    fun `an informer that starts is run once, serves its store, and is closed when the flow ends`() = runBlocking {
        val flow = factory.informer<Pod, String>(
            inform = { _, _ -> FakeInformer(items = listOf(pod("p1"), pod("p2"))).also { informers += it }.proxy },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }

        val state = withTimeout(10_000) { flow.first { it is ResourceState.Success } }

        assertEquals(ResourceState.Success(listOf("p1", "p2")), state)
        val fake = informers.single()
        assertEquals(1, fake.runCalls.get(), "run exactly once — the lambda no longer runs it")
        assertEquals(1L, fake.closed.count, "a live informer stays open while the flow is collected")
        collector.cancel()
        scope.cancel()
        assertTrue(fake.closed.await(10, TimeUnit.SECONDS), "closed on the steady-state exit as before")
    }
}
