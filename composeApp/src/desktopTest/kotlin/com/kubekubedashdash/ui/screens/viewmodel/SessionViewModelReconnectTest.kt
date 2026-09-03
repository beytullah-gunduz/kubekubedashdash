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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the reconnect-overlay semantics: a connection loss on a previously
 * connected session must NOT navigate away or reset the namespace — the
 * overlay (driven by [SessionViewModel.reconnecting]) covers the stale
 * content instead, and a successful reconnect drops the user exactly where
 * they were. User-initiated connects keep the historical reset-everything
 * behaviour.
 *
 * Uses the demo-mock cluster so no test reads a kubeconfig. Loss is injected
 * via [ReactiveKubeClient.reportError] — the same entry the liveness probe
 * uses. The probe keeps re-reporting against the live mock, so
 * `connectionError` is not a stable assertion target after an injected
 * loss; `reconnectError` is.
 */
class SessionViewModelReconnectTest {

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
        shutdownCleanly(scope, label = "SessionViewModelReconnectTest", manager = manager)
        // A reconnect reattaches under the same mock label and gives the instance
        // a second handle that retirePrevious closes on a 1 s daemon thread; tests
        // run sequentially in one JVM, so a global hard shutdown is correct here.
        MockClusterProvider.forceShutdown()
    }

    private suspend fun connectAndAwait() {
        viewModel.connectToCluster(DemoContext.MOCK_CONTEXT_NAME)
        withTimeout(30_000) { viewModel.isConnected.first { it } }
        withTimeout(10_000) { viewModel.currentScreen.first { it == Screen.Main.ClusterOverview } }
        // Freeze the demo churn so clusterInfo stops re-emitting mid-assertion.
        // The mock server stays up; only the mutation loops stop.
        MockClusterProvider.simulators().forEach { it.stop() }
        delay(1_000)
    }

    /**
     * Injects failures until the reducer raises [SessionViewModel.reconnecting].
     * Loops because the liveness probe's concurrent reportSuccess can reset
     * the consecutive-failure counter between individual calls.
     */
    private suspend fun forceConnectionLoss() {
        withTimeout(30_000) {
            while (!viewModel.reconnecting.value) {
                reactiveClient.reportError("injected connection loss")
                delay(50)
            }
        }
    }

    @Test
    fun `connection loss preserves screen and namespace and raises the overlay flag`() = runBlocking<Unit> {
        connectAndAwait()
        viewModel.navigate(Screen.Main.Pods())
        viewModel.setSelectedNamespace("production")

        forceConnectionLoss()

        assertIs<Screen.Main.Pods>(viewModel.currentScreen.value, "loss must not swap the screen")
        assertEquals("production", viewModel.selectedNamespace.value)
        assertEquals("production", reactiveClient.selectedNamespace.value)
        assertFalse(viewModel.isConnected.value)
        assertTrue(viewModel.reconnecting.value)
        assertNotNull(viewModel.reconnectError.value)
        withTimeout(10_000) { viewModel.retryCountdown.first { it > 0 } }
    }

    @Test
    fun `retryNow reconnects without resetting screen or namespace`() = runBlocking<Unit> {
        connectAndAwait()
        viewModel.navigate(Screen.Main.Pods())
        viewModel.setSelectedNamespace("production")
        forceConnectionLoss()

        viewModel.retryNow()
        withTimeout(30_000) { viewModel.isConnected.first { it } }
        withTimeout(10_000) { viewModel.reconnecting.first { !it } }

        assertIs<Screen.Main.Pods>(viewModel.currentScreen.value, "reconnect must not navigate")
        assertEquals("production", viewModel.selectedNamespace.value)
        assertEquals("production", reactiveClient.selectedNamespace.value)
        assertNull(viewModel.reconnectError.value)
        assertEquals(0, viewModel.retryCountdown.value)
    }

    @Test
    fun `the automatic retry reconnects in place`() = runBlocking<Unit> {
        connectAndAwait()
        viewModel.navigate(Screen.Main.Nodes())
        forceConnectionLoss()
        withTimeout(10_000) { viewModel.retryCountdown.first { it > 0 } }

        // No user action: scheduleRetry's own timer (10 s) must reconnect
        // as a reconnect, not as a fresh connect.
        withTimeout(40_000) { viewModel.isConnected.first { it } }
        withTimeout(10_000) { viewModel.reconnecting.first { !it } }

        assertIs<Screen.Main.Nodes>(viewModel.currentScreen.value, "automatic reconnect must not navigate")
    }

    @Test
    fun `user-initiated connect still resets namespace and lands on the cluster overview`() = runBlocking<Unit> {
        connectAndAwait()
        viewModel.navigate(Screen.Main.Pods())
        viewModel.setSelectedNamespace("production")
        // An open, expanded pane from the old cluster must not outlive the switch.
        viewModel.navigate(Screen.Detail.ResourceDetail(kind = "Pod", name = "p1", namespace = "production"))
        viewModel.setExtraPaneExpanded(true)
        assertTrue(viewModel.extraPaneExpanded.value)

        // Same context, but user-initiated (no isReconnect flag): historical
        // reset-everything behaviour must be preserved.
        viewModel.connectToCluster(viewModel.selectedContext.value)
        withTimeout(30_000) { viewModel.currentScreen.first { it == Screen.Main.ClusterOverview } }
        // Sync on the namespace reset itself, not just the screen: the screen
        // transition is applied by the async reducer and must not be used as a
        // happens-before proxy for the namespace writes.
        withTimeout(10_000) { reactiveClient.selectedNamespace.first { it == null } }

        assertEquals("All Namespaces", viewModel.selectedNamespace.value)
        assertNull(reactiveClient.selectedNamespace.value)
        assertFalse(viewModel.reconnecting.value)
        assertNull(viewModel.extraPaneScreen.value)
        assertFalse(viewModel.extraPaneExpanded.value)
    }

    @Test
    fun `a user-initiated connect while reconnecting drops the overlay`() = runBlocking<Unit> {
        connectAndAwait()
        viewModel.navigate(Screen.Main.Pods())
        forceConnectionLoss()

        // The picker path: a user-initiated connect (here: the same mock) clears
        // the scrim state synchronously with its ConnectStarted.
        viewModel.connectToCluster(viewModel.selectedContext.value)
        withTimeout(10_000) { viewModel.reconnecting.first { !it } }
        withTimeout(30_000) { viewModel.currentScreen.first { it == Screen.Main.ClusterOverview } }

        assertNull(viewModel.reconnectError.value)
    }
}
