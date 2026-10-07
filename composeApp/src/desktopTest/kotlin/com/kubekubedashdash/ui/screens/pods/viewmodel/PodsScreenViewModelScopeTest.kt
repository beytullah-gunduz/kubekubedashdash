package com.kubekubedashdash.ui.screens.pods.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Pods that leave the list because the namespace selection stopped covering
 * them were not deleted: they must not linger as stale "ghost" rows. Seeded
 * cluster: one pod each in ns-a, ns-b and ns-c.
 */
class PodsScreenViewModelScopeTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var vm: PodsScreenViewModel
    private var stateCollector: Job? = null
    private var staleCollector: Job? = null

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
            for ((ns, name) in listOf("ns-a" to "a-0", "ns-b" to "b-0", "ns-c" to "c-0")) {
                seed.pods().inNamespace(ns).resource(
                    PodBuilder()
                        .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                        .build(),
                ).create()
            }
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
        vm = PodsScreenViewModel(client)
        // Keep the VM's upstreams collected for the whole test, the way the
        // screen's collectAsState does — processPodUpdate only runs while the
        // shared flow is active, and stalePods is shared while subscribed.
        stateCollector = scope.launch { vm.state.collect {} }
        staleCollector = scope.launch { vm.stalePods.collect {} }
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            label = "PodsScreenViewModelScopeTest",
            manager = manager,
            servers = listOf(server),
        )
    }

    private suspend fun awaitNames(vararg expected: String) {
        val wanted = expected.toSet()
        withTimeout(10_000) {
            vm.state.first { it is ResourceState.Success && it.data.mapTo(HashSet(), PodInfo::name) == wanted }
        }
    }

    @Test
    fun `a namespace dropped from a multi-selection leaves no ghost rows`() = runBlocking {
        client.setNamespaceScope(NamespaceScope.of(listOf("ns-a", "ns-b")))
        awaitNames("a-0", "b-0")

        client.setNamespaceScope(NamespaceScope.of(listOf("ns-a", "ns-c")))
        awaitNames("a-0", "c-0")

        // b-0 left the list because ns-b left the selection, not because it
        // was deleted, so it must not linger as a stale row.
        assertNull(withTimeoutOrNull(1_000) { vm.stalePods.first { it.isNotEmpty() } })
    }
}
