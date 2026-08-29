package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterization tests for [SessionViewModel]'s reactive connection-state
 * derivation — the part audit A6 flags as "computed 3 ways" and that the
 * multi-paragraph comment in `observeClusterInfoHealth` documents as
 * race-sensitive.
 *
 * Pins the connect direction (clusterInfo Success ⇒ connected), the
 * deliberate "Loading is ignored" rule, AND the disconnect direction.
 *
 * History (A6): the disconnect test was written first and FAILED against
 * the then-current code — after connecting, neither `server.destroy()` nor
 * `manager.close()` flipped `isConnected` within 20s, because fabric8
 * informers park in `awaitCancellation()` and keep stale store contents on
 * connection loss (so `clusterInfo` never errored and parked informers
 * never `reportError`'d). That exposed a latent silent-disconnect bug
 * (.docs/a6-connection-state-finding-2026-05-16.md). The fix added
 * `ReactiveKubeClient.isReachable` — a `/version` liveness probe that keeps
 * `reportError`/`reportSuccess` honest while connected, so the existing
 * `observeConnectionHealth` path detects a dead cluster. The disconnect
 * test below now passes and guards that fix.
 */
class SessionViewModelConnectionTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var reactiveClient: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: SessionViewModel

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
            seed.namespaces().resource(
                NamespaceBuilder().withNewMetadata().withName("ns-a").endMetadata().build(),
            ).create()
            seed.pods().inNamespace("ns-a").resource(
                PodBuilder().withNewMetadata().withName("p1").withNamespace("ns-a").endMetadata().build(),
            ).create()
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        reactiveClient = ReactiveKubeClient(scope, manager)
        viewModel = SessionViewModel(reactiveClient, scope)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "SessionViewModelConnectionTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `starts disconnected — clusterInfo Loading does not mark connected`() {
        // Right after construction the only clusterInfo emission possible is
        // the initial Loading, which observeClusterInfoHealth ignores by
        // design (reacting to it would race connectToCluster's explicit
        // writes / connection-version bumps).
        assertFalse(viewModel.isConnected.value, "should not be connected before clusterInfo syncs")
        assertEquals(null, viewModel.connectionError.value, "no error before any failure")
    }

    @Test
    fun `clusterInfo reaching Success marks the session connected`() = runBlocking {
        withTimeout(15_000) { viewModel.isConnected.first { it } }
        assertTrue(viewModel.isConnected.value)
        assertEquals(null, viewModel.connectionError.value)
    }

    @Test
    fun `silent cluster loss flips isConnected back to false (A6 fix)`() = runBlocking {
        // Reach the connected state first.
        withTimeout(15_000) { viewModel.isConnected.first { it } }
        // Cluster silently goes away — informers just park with stale data.
        // The isReachable /version probe must keep reporting errors so the
        // ≥3-failure threshold trips connectionError and observeConnection-
        // Health drops isConnected. Probe interval 5s, threshold 3 ⇒ ~15s
        // worst case; allow generous margin.
        server.destroy()
        withTimeout(45_000) { viewModel.isConnected.first { !it } }
        assertFalse(viewModel.isConnected.value)
    }
}
