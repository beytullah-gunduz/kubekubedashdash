package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.DemoContext
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.MockClusterProvider
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
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
import kotlin.test.assertNull

/**
 * Pins [SessionViewModel.prepareRestore]: the next successful connect lands
 * on the restored namespace, screen and pane width instead of the connect
 * defaults, and only once. The screen change is the LAST write of the
 * connect path, so awaiting it proves the namespace and width already
 * landed. Uses the demo-mock cluster; no kubeconfig.
 */
class SessionViewModelRestoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var viewModel: SessionViewModel

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        reactiveClient = ReactiveKubeClient(scope, manager)
        viewModel = SessionViewModel(reactiveClient, scope)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "SessionViewModelRestoreTest", manager = manager)
        MockClusterProvider.forceShutdown()
    }

    @Test
    fun `a prepared restore lands on its namespace, screen and pane width`() = runBlocking<Unit> {
        viewModel.prepareRestore(SessionViewModel.RestoreTarget("production", Screen.Main.Pods(), 600f))
        viewModel.connectToCluster(DemoContext.MOCK_CONTEXT_NAME)
        withTimeout(30_000) { viewModel.currentScreen.first { it == Screen.Main.Pods() } }

        assertEquals("production", viewModel.selectedNamespace.value)
        assertEquals("production", reactiveClient.selectedNamespace.value)
        assertEquals(600f, viewModel.extraPaneWidth.value)
    }

    @Test
    fun `the restore target is consumed once — a later connect uses the defaults`() = runBlocking<Unit> {
        viewModel.prepareRestore(SessionViewModel.RestoreTarget("production", Screen.Main.Pods(), 600f))
        viewModel.connectToCluster(DemoContext.MOCK_CONTEXT_NAME)
        withTimeout(30_000) { viewModel.currentScreen.first { it == Screen.Main.Pods() } }

        // Same context, user-initiated: historical reset-everything behaviour.
        viewModel.connectToCluster(viewModel.selectedContext.value)
        withTimeout(30_000) { viewModel.currentScreen.first { it == Screen.Main.ClusterOverview } }

        assertEquals("All Namespaces", viewModel.selectedNamespace.value)
        assertNull(reactiveClient.selectedNamespace.value)
    }

    @Test
    fun `the pane width is clamped like a drag`() = runBlocking<Unit> {
        viewModel.prepareRestore(SessionViewModel.RestoreTarget("All Namespaces", Screen.Main.Nodes(), 5_000f))
        viewModel.connectToCluster(DemoContext.MOCK_CONTEXT_NAME)
        withTimeout(30_000) { viewModel.currentScreen.first { it == Screen.Main.Nodes() } }

        assertEquals(1200f, viewModel.extraPaneWidth.value)
        assertNull(reactiveClient.selectedNamespace.value)
    }
}
