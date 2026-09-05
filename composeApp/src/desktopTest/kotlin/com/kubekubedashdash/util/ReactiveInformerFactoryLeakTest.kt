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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lifecycle contract of [ReactiveInformerFactory]: an informer the factory
 * started is closed on EVERY exit from its inner flow — a cancellation (a
 * namespace or cluster switch, or the session closing) that lands while the
 * flow is still waiting for the informer to sync, a sync failure, and a
 * mapping failure — not only the steady-state `awaitCancellation()` exit.
 *
 * On fabric8 7.7 `inform()` returns only after list AND watch are up, so
 * `hasSynced()` is already true and the waiting-for-sync window does not
 * occur there today; the contract is pinned independent of that ordering
 * (an informer handed back unsynced — a future fabric8, or a
 * `runnableInformer()`/`start()` shape — must not leak either).
 *
 * The cluster is faked at the factory's own seam: the `inform` lambda
 * returns a dynamic-proxy [SharedIndexInformer] and can block to stand in for
 * a slow LIST, so nothing here opens a socket or reads any real user state.
 */
class ReactiveInformerFactoryLeakTest {

    /** One fake informer: what the factory can observe, and what it must call. */
    private class FakeInformer(
        synced: Boolean,
        running: Boolean,
        private val items: List<Pod> = emptyList(),
    ) {
        val closed = CountDownLatch(1)
        private val syncedFlag = AtomicBoolean(synced)
        private val runningFlag = AtomicBoolean(running)

        private val store: Store<Pod> = newProxy(Store::class.java) { method, _ ->
            when (method.name) {
                "list" -> items
                "listKeys" -> emptyList<String>()
                else -> null
            }
        }

        val proxy: SharedIndexInformer<Pod> = newProxy(SharedIndexInformer::class.java) { method, _ ->
            when (method.name) {
                "hasSynced" -> syncedFlag.get()

                "isRunning" -> runningFlag.get()

                "getStore" -> store

                "close", "stop" -> {
                    closed.countDown()
                    null
                }

                else -> error("unexpected informer call: ${method.name}")
            }
        }

        val isClosed: Boolean get() = closed.count == 0L

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
        shutdownCleanly(scope, label = "ReactiveInformerFactoryLeakTest", manager = manager)
    }

    private fun pod(name: String): Pod = PodBuilder().withNewMetadata().withName(name).withNamespace("default").endMetadata().build()

    @Test
    fun `cluster-scoped informer is closed when the session ends while the flow is still waiting for sync`() = runBlocking {
        val listStarted = CountDownLatch(1)
        val listReleased = CountDownLatch(1)
        val flow = factory.informer<Pod, String>(
            inform = { _, _ ->
                // Recorded at entry, then a slow LIST: block until the test
                // says the list finished, and only then hand back an informer
                // that has NOT synced yet, so the flow must wait on it.
                val fake = FakeInformer(synced = false, running = true).also { informers += it }
                listStarted.countDown()
                listReleased.await(10, TimeUnit.SECONDS)
                fake.proxy
            },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }
        assertTrue(listStarted.await(10, TimeUnit.SECONDS), "the inform lambda must have been entered")

        // The session goes away while the LIST is still in flight.
        collector.cancel()
        scope.cancel()
        listReleased.countDown()

        assertTrue(informers.single().closed.await(10, TimeUnit.SECONDS), "an informer returned into a cancelled flow must still be closed")
    }

    @Test
    fun `namespaced informer is closed when the namespace changes while the flow is still waiting for sync`() = runBlocking {
        val firstListStarted = CountDownLatch(1)
        val listReleased = CountDownLatch(1)
        val flow = factory.namespacedInformer<Pod, String>(
            inform = { _, _, _ ->
                // Recorded at entry: flatMapLatest joins the cancelled attempt
                // before starting the next one, so informers[0] is always the
                // attempt that was cancelled mid-LIST. Handed back unsynced so
                // the flow must wait on it (the second attempt simply keeps
                // waiting until teardown).
                val fake = FakeInformer(synced = false, running = true).also { informers += it }
                firstListStarted.countDown()
                listReleased.await(10, TimeUnit.SECONDS)
                fake.proxy
            },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }
        assertTrue(firstListStarted.await(10, TimeUnit.SECONDS), "the inform lambda must have been entered")

        // A namespace switch restarts the inner flow while the first LIST is
        // still running; the first informer comes back into a cancelled flow.
        selectedNamespace.value = "ns-b"
        listReleased.countDown()

        // Both attempts (the cancelled one and the restarted one) return an informer.
        withTimeout(10_000) {
            while (informers.size < 2) kotlinx.coroutines.delay(20)
        }
        val first = informers[0]
        assertTrue(first.closed.await(10, TimeUnit.SECONDS), "the informer of the cancelled attempt must be closed")
        collector.cancel()
    }

    @Test
    fun `an informer that stops before syncing is closed and the flow reports an error`() = runBlocking {
        val flow = factory.informer<Pod, String>(
            inform = { _, _ -> FakeInformer(synced = false, running = false).also { informers += it }.proxy },
            mapper = { it.metadata.name },
        )
        val collector = scope.launch { flow.collect {} }
        val state = withTimeout(10_000) { flow.first { it is ResourceState.Error } }
        assertTrue(state is ResourceState.Error, "a stopped informer must surface as Error, got $state")
        assertTrue(informers.single().closed.await(10, TimeUnit.SECONDS), "a stopped informer must still be closed")
        collector.cancel()
    }

    @Test
    fun `an informer whose mapper throws is closed and the flow reports an error`() = runBlocking {
        val flow = factory.informer<Pod, String>(
            inform = { _, _ -> FakeInformer(synced = true, running = true, items = listOf(pod("p1"))).also { informers += it }.proxy },
            mapper = { throw IllegalStateException("mapper failed on ${it.metadata.name}") },
        )
        val collector = scope.launch { flow.collect {} }
        val state = withTimeout(10_000) { flow.first { it is ResourceState.Error } }
        assertEquals(ResourceState.Error("mapper failed on p1"), state)
        assertTrue(informers.single().closed.await(10, TimeUnit.SECONDS), "an informer whose mapping failed must still be closed")
        collector.cancel()
    }
}
