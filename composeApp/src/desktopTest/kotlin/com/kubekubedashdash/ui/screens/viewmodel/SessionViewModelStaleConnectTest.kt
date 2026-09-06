package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.Screen
import com.kubekubedashdash.util.KubeConnectionManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A connect attempt that was superseded by a newer one must not apply its
 * outcome. `connectToCluster` cancels the previous job, but a blocking
 * `connect()` cannot be interrupted: the old attempt finishes later, and its
 * fold used to emit a stale ConnectFailed — arming the 10 s retry countdown
 * against the context the NEWER attempt had just connected, and flipping the
 * screen back to the error page on every tick.
 *
 * The cluster is faked through KubeConnectionManager's constructor seams: a
 * context-keyed `loadConfig` that can block (a slow connect) and then return
 * either a dead loopback address (failure) or the mock server's config
 * (success). No kubeconfig, no DataStore, no real user state.
 */
class SessionViewModelStaleConnectTest {

    private enum class Answer { LIVE, SLOW_THEN_FAIL }

    private lateinit var server: KubernetesMockServer
    private lateinit var liveConfig: Config
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var viewModel: SessionViewModel

    /** What the next connect for any context should get; flipped by the test between attempts. */
    private val answer = AtomicReference(Answer.LIVE)

    /** Counted down when a SLOW_THEN_FAIL attempt has entered loadConfig (and holds connectLock). */
    private var slowEntered = CountDownLatch(1)

    /** Released by the test to let the slow attempt finish (and fail). */
    private var slowRelease = CountDownLatch(1)

    private fun deadConfig(): Config = Config.empty().apply {
        masterUrl = "http://127.0.0.1:1"
        requestRetryBackoffLimit = 0
        requestTimeout = 2_000
    }

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
        liveConfig = server.createClient().use { it.configuration }
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager(
            loadConfig = {
                when (answer.get()) {
                    Answer.LIVE -> liveConfig

                    Answer.SLOW_THEN_FAIL -> {
                        slowEntered.countDown()
                        slowRelease.await(15, TimeUnit.SECONDS)
                        deadConfig()
                    }
                }
            },
        )
        reactiveClient = ReactiveKubeClient(scope, manager)
        viewModel = SessionViewModel(reactiveClient, scope)
    }

    @AfterTest
    fun tearDown() {
        slowRelease.countDown()
        shutdownCleanly(scope, label = "SessionViewModelStaleConnectTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `a superseded user connect that fails later must not arm a retry against the newer cluster`() = runBlocking<Unit> {
        // Attempt 1: a slow connect that will fail. It enters loadConfig and
        // holds the connect lock.
        answer.set(Answer.SLOW_THEN_FAIL)
        viewModel.connectToCluster("slow-context")
        assertTrue(slowEntered.await(10, TimeUnit.SECONDS), "attempt 1 must be inside its slow connect")

        // Attempt 2 supersedes it while it is still blocked, and will succeed.
        answer.set(Answer.LIVE)
        viewModel.connectToCluster("cluster-b")
        assertEquals("cluster-b", viewModel.selectedContext.value)
        delay(200)
        slowRelease.countDown()

        withTimeout(20_000) { viewModel.isConnected.first { it } }
        withTimeout(5_000) { viewModel.currentScreen.first { it is Screen.Main.ClusterOverview } }

        // A stale ConnectFailed would have armed scheduleRetry(), which writes
        // retryCountdown once per second and rewrites the screen to the error
        // page on every tick: 2.5 s of silence proves it never happened.
        delay(2_500)
        assertEquals(0, viewModel.retryCountdown.value, "no retry may be armed by a superseded attempt")
        assertIs<Screen.Main.ClusterOverview>(viewModel.currentScreen.value)
        assertNull(viewModel.connectionError.value, "a superseded failure must not surface as the session's error")
        assertTrue(viewModel.isConnected.value)
        assertFalse(viewModel.isConnecting.value)
        assertEquals("cluster-b", viewModel.selectedContext.value)
        assertTrue(manager.isConnected, "the manager must hold the newer cluster's client")
    }

    @Test
    fun `a superseded reconnect that fails later must not arm a retry after the newer reconnect landed`() = runBlocking<Unit> {
        // Reach a connected state, then lose the cluster the way the liveness
        // probe would report it, so the reconnect overlay and its countdown arm.
        viewModel.connectToCluster("cluster-b")
        withTimeout(20_000) { viewModel.isConnected.first { it } }
        withTimeout(5_000) { viewModel.currentScreen.first { it is Screen.Main.ClusterOverview } }
        withTimeout(30_000) {
            while (!viewModel.reconnecting.value) {
                reactiveClient.reportError("injected connection loss")
                delay(50)
            }
        }
        assertTrue(viewModel.reconnecting.value)

        // Retry now: attempt A is a slow reconnect that will fail; it holds the lock.
        answer.set(Answer.SLOW_THEN_FAIL)
        viewModel.retryNow()
        assertTrue(slowEntered.await(10, TimeUnit.SECONDS), "attempt A must be inside its slow reconnect")

        // Retry now again: attempt B supersedes A and will succeed.
        answer.set(Answer.LIVE)
        viewModel.retryNow()
        delay(200)
        slowRelease.countDown()

        withTimeout(20_000) { viewModel.reconnecting.first { !it } }
        withTimeout(5_000) { viewModel.isConnected.first { it } }

        // A's stale ConnectFailed(isReconnect = true) would have armed
        // scheduleRetry(true), which counts retryCountdown down from 10 and
        // then reconnects the already-healthy cluster: 2.5 s of a zero
        // countdown proves the superseded outcome was dropped.
        delay(2_500)
        assertEquals(0, viewModel.retryCountdown.value, "no retry may be armed by a superseded reconnect")
        assertFalse(viewModel.reconnecting.value)
        assertTrue(viewModel.isConnected.value)
        assertFalse(viewModel.isConnecting.value)
        assertIs<Screen.Main.ClusterOverview>(viewModel.currentScreen.value)
    }
}
