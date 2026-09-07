package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.MockClusterProvider
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.client.Config
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

/**
 * `connectToCluster` numbers its attempts and the manager refuses a stale
 * one — but only if the number actually reaches it, through both the real
 * and the demo-cluster connect paths. This pins the plumbing: after each
 * connect the manager's watermark equals the attempt the view model minted.
 * The real path is faked through the manager's config seam against a
 * loopback mock server; the demo path boots a mock instance and is torn
 * down with the provider's hard shutdown, as the reconnect test does.
 * No kubeconfig, no real user state.
 */
class SessionViewModelAttemptPlumbingTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var liveConfig: Config
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        server = KubernetesMockServer(Context(), MockWebServer(), HashMap(), KubernetesCrudDispatcher(), false)
        server.init()
        liveConfig = server.createClient().use { it.configuration }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager(loadConfig = { liveConfig })
        reactiveClient = ReactiveKubeClient(scope, manager)
        viewModel = SessionViewModel(reactiveClient, scope)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "SessionViewModelAttemptPlumbingTest", manager = manager, servers = listOf(server))
        MockClusterProvider.forceShutdown()
    }

    @Test
    fun `every real connect hands its attempt number to the manager`() = runBlocking<Unit> {
        viewModel.connectToCluster("cluster-a")
        withTimeout(20_000) { viewModel.isConnected.first { it } }
        assertEquals(1L, manager.newestAttempt)

        viewModel.connectToCluster("cluster-b")
        withTimeout(20_000) { manager.connectionVersion.first { it >= 2L } }
        assertEquals(2L, manager.newestAttempt)
    }

    @Test
    fun `a demo-cluster connect hands its attempt number to the manager too`() = runBlocking<Unit> {
        viewModel.connectToCluster(DemoContext.MOCK_CONTEXT_NAME)
        withTimeout(30_000) { viewModel.isConnected.first { it } }

        assertEquals(1L, manager.newestAttempt)
    }
}
