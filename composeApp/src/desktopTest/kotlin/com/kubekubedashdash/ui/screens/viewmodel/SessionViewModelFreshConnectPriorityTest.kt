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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reconnect must never supersede an in-flight fresh connect. The fresh
 * attempt's tail is what lands the session (overview or restore target,
 * namespace scope, pane width); a reconnect that replaced it keeps the
 * screen it "never left" — here the Connecting page — and skips the rest.
 *
 * Reachable without a race: switch away from a dying cluster to one whose
 * connect outlives the scrim's countdown (an exec-plugin login), and the
 * countdown's reconnect fires while the fresh connect is still blocked.
 * Here the loss is injected through reportError and the reconnect through
 * Retry now, while the fresh connect is parked inside loadConfig. The
 * cluster is faked through KubeConnectionManager's loadConfig seam: no
 * kubeconfig, no real user state.
 */
class SessionViewModelFreshConnectPriorityTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var liveConfig: Config
    private lateinit var scope: CoroutineScope
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var viewModel: SessionViewModel

    /** While set, a loadConfig call parks on [slowRelease] before answering. */
    private val slow = AtomicBoolean(false)

    /** Counted down when a slow loadConfig has been entered (and holds connectLock). */
    private val slowEntered = CountDownLatch(1)

    /** Released by the test to let the slow connect finish (and succeed). */
    private val slowRelease = CountDownLatch(1)

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
                if (slow.get()) {
                    slowEntered.countDown()
                    slowRelease.await(15, TimeUnit.SECONDS)
                }
                liveConfig
            },
        )
        reactiveClient = ReactiveKubeClient(scope, manager)
        viewModel = SessionViewModel(reactiveClient, scope)
    }

    @AfterTest
    fun tearDown() {
        slowRelease.countDown()
        shutdownCleanly(scope, label = "SessionViewModelFreshConnectPriorityTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `a reconnect fired while a fresh connect is in flight must not supersede it`() = runBlocking<Unit> {
        viewModel.connectToCluster("cluster-b")
        withTimeout(20_000) { viewModel.isConnected.first { it } }
        withTimeout(5_000) { viewModel.currentScreen.first { it is Screen.Main.ClusterOverview } }

        // A fresh switch to a cluster whose connect takes a while: the screen
        // goes to Connecting and the attempt parks inside loadConfig.
        slow.set(true)
        viewModel.connectToCluster("cluster-c")
        assertTrue(slowEntered.await(10, TimeUnit.SECONDS), "the fresh connect must be inside its slow connect")
        withTimeout(5_000) { viewModel.currentScreen.first { it is Screen.Main.Connecting } }

        // The cluster being left dies underneath the switch: scrim + countdown.
        withTimeout(30_000) {
            while (!viewModel.reconnecting.value) {
                reactiveClient.reportError("injected connection loss")
                delay(50)
            }
        }

        // The reconnect a countdown would fire, or the user clicking Retry
        // now on the scrim: it must not take over from the fresh connect.
        viewModel.retryNow()
        delay(200)
        slow.set(false)
        slowRelease.countDown()

        withTimeout(20_000) { viewModel.isConnected.first { it } }
        // The fresh attempt's landing: the overview, not the Connecting page
        // a reconnect would have left in place.
        withTimeout(5_000) { viewModel.currentScreen.first { it is Screen.Main.ClusterOverview } }
        withTimeout(5_000) { viewModel.reconnecting.first { !it } }

        // 2.5 s of silence: no countdown, no error, nothing still connecting.
        delay(2_500)
        assertIs<Screen.Main.ClusterOverview>(viewModel.currentScreen.value)
        assertEquals(0, viewModel.retryCountdown.value, "no retry may be armed once the fresh connect landed")
        assertNull(viewModel.connectionError.value)
        assertFalse(viewModel.reconnecting.value)
        assertFalse(viewModel.isConnecting.value)
        assertTrue(viewModel.isConnected.value)
        assertEquals("cluster-c", viewModel.selectedContext.value)
        assertTrue(manager.isConnected, "the manager must hold the fresh attempt's client")
    }
}
