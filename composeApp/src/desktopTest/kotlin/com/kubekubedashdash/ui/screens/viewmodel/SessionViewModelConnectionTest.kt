package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * These pin the CURRENT observable behavior of the `clusterInfo` →
 * `isConnected` projection: the connect direction works (clusterInfo
 * Success ⇒ connected) and the deliberate "Loading is ignored" rule
 * holds.
 *
 * CHARACTERIZATION FINDING (A6): the DISCONNECT direction is intentionally
 * NOT asserted here. Empirically, after the session is connected, neither
 * `server.destroy()` nor `manager.close()` flips `isConnected` back to
 * false within 20s. fabric8 SharedIndexInformers keep their stale store
 * contents on connection loss and stay parked in `awaitCancellation()`, so
 * the informer-backed `clusterInfo` never emits `ResourceState.Error` and
 * no `reportError` fires from the parked informers. The
 * `observeClusterInfoHealth` Error branch and `observeConnectionHealth`
 * are therefore effectively unreachable on silent connection loss — the
 * code comment's "ring tracks reachability within one poll tick" describes
 * the pre-informer polling design and is no longer accurate. This is a
 * likely latent disconnect-detection bug, separate from (and larger than)
 * A6's "tidy the triple-truth" scope; pinning it would enshrine a bug, so
 * it is documented rather than asserted.
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
        scope.cancel()
        manager.close()
        runCatching { server.destroy() }
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

    // NOTE: a "connection loss flips isConnected=false" test deliberately
    // omitted — see the class KDoc. The current code does not exhibit that
    // behavior on silent loss (a latent bug to be triaged on its own), and
    // a characterization test must pin real behavior, not aspirational.
}
