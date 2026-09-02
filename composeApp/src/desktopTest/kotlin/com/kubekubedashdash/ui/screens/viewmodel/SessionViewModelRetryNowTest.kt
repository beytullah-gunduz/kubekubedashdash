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
import kotlin.test.assertTrue

/**
 * Pins [SessionViewModel.retryNow]: a user-initiated retry from the
 * connection-error screen cancels the automatic countdown and starts a fresh
 * attempt against the current context at once.
 *
 * Uses the demo-mock cluster so no test reads a kubeconfig. Loss is injected
 * via [ReactiveKubeClient.reportError] — the same entry the liveness probe
 * uses — until the consecutive-failure threshold trips HealthErrorReported,
 * which swaps the screen to ConnectionError and arms the countdown.
 */
class SessionViewModelRetryNowTest {

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
        // Repo convention (MockServerTeardown.kt): join the scope before closing
        // the manager so unwinding coroutines never see a half-closed handle.
        shutdownCleanly(scope, label = "SessionViewModelRetryNowTest", manager = manager)
        // shutdownCleanly/manager.close() only drop THIS handle; a retryNow()
        // reattach gives the instance a second handle that
        // KubeConnectionManager.retirePrevious closes on a 1 s daemon thread
        // (RETIRE_GRACE_MS), so the instance would otherwise survive into the
        // next test method. Tests run sequentially in one JVM (no
        // maxParallelForks), so a global hard shutdown is correct here.
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
     * Injects failures until the reducer swaps the screen to ConnectionError.
     * Loops because the liveness probe's concurrent reportSuccess can reset
     * the consecutive-failure counter between individual calls.
     */
    private suspend fun forceConnectionLoss() {
        withTimeout(30_000) {
            while (viewModel.currentScreen.value !is Screen.Main.ConnectionError) {
                reactiveClient.reportError("injected connection loss")
                delay(50)
            }
        }
    }

    @Test
    fun `retryNow is a no-op before any context is selected`() {
        viewModel.retryNow()
        assertFalse(viewModel.isConnecting.value)
        assertEquals(0, viewModel.retryCountdown.value)
        assertEquals(Screen.Main.Connecting, viewModel.currentScreen.value)
    }

    @Test
    fun `retryNow cancels the countdown and reconnects at once`() = runBlocking<Unit> {
        connectAndAwait()
        forceConnectionLoss()
        assertFalse(viewModel.isConnected.value, "injected loss must clear isConnected")
        // Wait for a HIGH countdown value: >= 8 means >= 7 s of automatic wait
        // still remain, so any reconnect inside the 5 s budget below can only
        // have come from retryNow() and not from scheduleRetry's own timer.
        withTimeout(10_000) { viewModel.retryCountdown.first { it >= 8 } }
        assertIs<Screen.Main.ConnectionError>(viewModel.currentScreen.value)

        viewModel.retryNow()

        // The whole point: reconnect lands well before the countdown would have.
        withTimeout(5_000) { viewModel.currentScreen.first { it == Screen.Main.ClusterOverview } }
        assertTrue(viewModel.isConnected.value)
        assertEquals(0, viewModel.retryCountdown.value)
        // The countdown job is really dead: while alive it writes _retryCountdown
        // once per second, so 2.5 s of silence proves the cancel took.
        delay(2_500)
        assertEquals(0, viewModel.retryCountdown.value)
        assertIs<Screen.Main.ClusterOverview>(viewModel.currentScreen.value)
    }
}
