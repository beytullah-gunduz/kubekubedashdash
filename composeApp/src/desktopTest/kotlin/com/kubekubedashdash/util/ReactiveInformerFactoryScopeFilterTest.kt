package com.kubekubedashdash.util

import com.kubekubedashdash.models.NamespaceScope
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
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertTrue

/**
 * A namespaced list follows the factory's [NamespaceScope]. One namespace is
 * still watched server-side; two or more watch every namespace and the store is
 * filtered here, so growing or shrinking such a selection re-filters in place
 * instead of restarting the informer.
 *
 * Faked at the factory's own seam with dynamic-proxy informers whose store
 * always holds one pod in each of ns-a, ns-b and ns-c (like a cluster-wide
 * list), so nothing here opens a socket or reads any real user state.
 */
class ReactiveInformerFactoryScopeFilterTest {

    private class FakeInformer(private val items: List<Pod>) {
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
    private lateinit var selection: MutableStateFlow<NamespaceScope>
    private lateinit var factory: ReactiveInformerFactory
    private val informers = CopyOnWriteArrayList<FakeInformer>()
    private val namespacesSeen = CopyOnWriteArrayList<String?>()

    private fun unusedClient(): KubernetesClient = KubernetesClientBuilder()
        .withConfig(Config.empty().apply { masterUrl = "http://127.0.0.1:1" })
        .build()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(unusedClient(), "cluster-a").getOrThrow()
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveInformerFactoryScopeFilterTest", manager = manager)
    }

    private fun pod(name: String, namespace: String): Pod = PodBuilder().withNewMetadata().withName(name).withNamespace(namespace).endMetadata().build()

    private val clusterWidePods = listOf(pod("a-0", "ns-a"), pod("b-0", "ns-b"), pod("c-0", "ns-c"))

    /** A factory following [initial]; the selection stays reachable as [selection]. */
    private fun factoryFor(initial: NamespaceScope): StateFlow<ResourceState<List<String>>> {
        selection = MutableStateFlow(initial)
        factory = ReactiveInformerFactory(scope, manager, selection)
        return factory.namespacedInformer<Pod, String>(
            inform = { _, ns, _ ->
                namespacesSeen += ns
                FakeInformer(clusterWidePods).also { informers += it }.proxy
            },
            mapper = { it.metadata.name },
        )
    }

    private suspend fun StateFlow<ResourceState<List<String>>>.awaitNames(vararg expected: String) {
        val wanted = expected.toSet()
        withTimeout(10_000) { first { it is ResourceState.Success && it.data.toSet() == wanted } }
    }

    @Test
    fun `two namespaces watch every namespace and keep the selected ones`() = runBlocking {
        val flow = factoryFor(NamespaceScope.of(listOf("ns-a", "ns-b")))
        val collector = scope.launch { flow.collect {} }

        flow.awaitNames("a-0", "b-0")

        assertEquals(listOf<String?>(null), namespacesSeen, "two namespaces are watched cluster-wide")
        collector.cancel()
    }

    @Test
    fun `ticking a third namespace re-filters without a restart`() = runBlocking {
        val flow = factoryFor(NamespaceScope.of(listOf("ns-a", "ns-b")))
        val collector = scope.launch { flow.collect {} }
        flow.awaitNames("a-0", "b-0")

        selection.value = NamespaceScope.of(listOf("ns-a", "ns-b", "ns-c"))

        flow.awaitNames("a-0", "b-0", "c-0")
        assertEquals(listOf<String?>(null), namespacesSeen, "the server-side namespace did not change")
        assertEquals(1, informers.size, "no second informer was built")
        assertEquals(1L, informers[0].closed.count, "the informer was not closed")
        collector.cancel()
    }

    @Test
    fun `narrowing to one namespace restarts on that namespace`() = runBlocking {
        val flow = factoryFor(NamespaceScope.of(listOf("ns-a", "ns-b")))
        val collector = scope.launch { flow.collect {} }
        flow.awaitNames("a-0", "b-0")

        selection.value = NamespaceScope.single("ns-a")

        flow.awaitNames("a-0")
        assertEquals(listOf<String?>(null, "ns-a"), namespacesSeen)
        assertTrue(informers[0].closed.await(10, TimeUnit.SECONDS), "the cluster-wide informer is replaced")
        collector.cancel()
    }

    @Test
    fun `All passes every namespace`() = runBlocking {
        val flow = factoryFor(NamespaceScope.All)
        val collector = scope.launch { flow.collect {} }

        flow.awaitNames("a-0", "b-0", "c-0")

        assertEquals(listOf<String?>(null), namespacesSeen)
        collector.cancel()
    }
}
