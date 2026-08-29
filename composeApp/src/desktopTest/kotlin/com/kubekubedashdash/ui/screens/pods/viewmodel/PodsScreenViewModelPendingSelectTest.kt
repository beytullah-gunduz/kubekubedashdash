package com.kubekubedashdash.ui.screens.pods.viewmodel

import androidx.lifecycle.viewModelScope
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression tests for the jump-to-pod pending selection
 * ([PodsScreenViewModel.setParams] with a uid): both scenarios were observed
 * as "lands on the Pods screen but never selects the pod" against a real
 * cluster, where informer timing differs from the ever-churning demo cluster.
 *
 * 1. A quiet cluster: the informer replays its snapshot when the screen
 *    subscribes — BEFORE setParams runs — and then never emits again, so a
 *    pending uid that only reacts to future emissions waits forever.
 * 2. A racing snapshot: an emission that does not yet contain the pod (a
 *    pre-namespace-switch replay, a not-yet-synced store) arrives after
 *    setParams; consuming the pending uid against it drops the selection
 *    that the very next snapshot would have resolved.
 */
class PodsScreenViewModelPendingSelectTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var vm: PodsScreenViewModel
    private var stateCollector: Job? = null
    private lateinit var targetUid: String

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
            val created = seed.pods().inNamespace("ns-b").resource(
                PodBuilder()
                    .withNewMetadata().withName("target").withNamespace("ns-b").endMetadata()
                    .build(),
            ).create()
            targetUid = created.metadata.uid
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
        vm = PodsScreenViewModel(client)
        // Keep the VM's state upstream collected for the whole test, the way
        // the screen's collectAsState does — processPodUpdate only runs while
        // the shared flow is active.
        stateCollector = scope.launch { vm.state.collect {} }
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            label = "PodsScreenViewModelPendingSelectTest",
            manager = manager,
            servers = listOf(server),
        )
    }

    private suspend fun awaitSnapshot(predicate: (List<com.kubekubedashdash.models.PodInfo>) -> Boolean) {
        withTimeout(10_000) {
            vm.state.first { it is ResourceState.Success && predicate(it.data) }
        }
    }

    @Test
    fun `pending uid resolves from the snapshot already in hand on a quiet cluster`() = runBlocking {
        client.setSelectedNamespace("ns-b")
        awaitSnapshot { pods -> pods.any { it.uid == targetUid } }

        // No cluster activity after this point: resolution must not depend on
        // a further informer emission.
        vm.setParams(targetUid)

        val selected = withTimeout(5_000) { vm.selectedPod.first { it != null } }
        assertNotNull(selected)
        assertEquals("target", selected.name)
    }

    @Test
    fun `pending uid survives a snapshot that does not yet contain the pod`() = runBlocking {
        // Screen subscribed while a namespace WITHOUT the pod is selected.
        client.setSelectedNamespace("ns-a")
        awaitSnapshot { pods -> pods.isEmpty() }

        vm.setParams(targetUid)

        // A snapshot lacking the target lands after setParams (the racing
        // pre-switch replay in the real sequence).
        val decoy = server.createClient()
        try {
            decoy.pods().inNamespace("ns-a").resource(
                PodBuilder()
                    .withNewMetadata().withName("decoy").withNamespace("ns-a").endMetadata()
                    .build(),
            ).create()
        } finally {
            decoy.close()
        }
        awaitSnapshot { pods -> pods.any { it.name == "decoy" } }

        // Now the namespace switch completes and the pod appears.
        client.setSelectedNamespace("ns-b")

        val selected = withTimeout(5_000) { vm.selectedPod.first { it != null } }
        assertNotNull(selected)
        assertEquals("target", selected.name)
    }
}
