package com.kubekubedashdash.ui.screens.events.viewmodel

import androidx.lifecycle.viewModelScope
import com.kubekubedashdash.models.EventInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.util.KubeConnectionManager
import com.kubekubedashdash.util.ReactiveKubeClient
import com.kubekubedashdash.util.shutdownCleanly
import io.fabric8.kubernetes.api.model.EventBuilder
import io.fabric8.kubernetes.api.model.EventSourceBuilder
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the jump-to-event pending selection
 * ([EventsScreenViewModel.setParams] with a uid) — the same two races the
 * Pods screen hit against a real cluster (PodsScreenViewModelPendingSelectTest),
 * plus the filter contract a jump carries: the target row must be visible.
 *
 * 1. A quiet cluster: the informer replays its snapshot when the screen
 *    subscribes — BEFORE setParams runs — and then never emits again.
 * 2. A racing snapshot: an emission that lacks the event arrives after
 *    setParams; consuming the pending uid against it drops the selection.
 * 3. An allowlist left over from an earlier visit that would hide the target
 *    is cleared on resolution; one that already shows it is kept.
 */
class EventsScreenViewModelPendingSelectTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope
    private lateinit var vm: EventsScreenViewModel
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
            targetUid = seed.createEvent(name = "target", namespace = "ns-b", host = "node-1")
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
        vm = EventsScreenViewModel(client)
        // Keep the VM's state upstream collected for the whole test, the way
        // the screen's collectAsState does — resolvePending only runs while
        // the shared flow is active.
        stateCollector = scope.launch { vm.state.collect {} }
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(
            scope,
            vm.viewModelScope,
            label = "EventsScreenViewModelPendingSelectTest",
            manager = manager,
            servers = listOf(server),
        )
    }

    // The CRUD mock assigns the uid; mapEvent needs a timestamp or it drops
    // the event, and the source host is what the node filter keys on.
    private fun KubernetesClient.createEvent(name: String, namespace: String, host: String): String = v1().events().inNamespace(namespace).resource(
        EventBuilder()
            .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
            .withType("Normal")
            .withReason("Scheduled")
            .withMessage("m")
            .withLastTimestamp("2026-01-01T00:00:00Z")
            .withInvolvedObject(ObjectReferenceBuilder().withKind("Pod").withName("web-0").withNamespace(namespace).build())
            .withSource(EventSourceBuilder().withHost(host).build())
            .build(),
    ).create().metadata.uid

    private suspend fun awaitSnapshot(predicate: (List<EventInfo>) -> Boolean) {
        withTimeout(10_000) {
            vm.state.first { it is ResourceState.Success && predicate(it.data) }
        }
    }

    private suspend fun awaitSelected(): EventInfo = withTimeout(5_000) { vm.selected.first { it != null } }!!

    @Test
    fun `pending uid resolves from the snapshot already in hand on a quiet cluster`() = runBlocking {
        client.setSelectedNamespace("ns-b")
        awaitSnapshot { events -> events.any { it.uid == targetUid } }

        // No cluster activity after this point: resolution must not depend on
        // a further informer emission.
        vm.setParams(targetUid)

        val selected = awaitSelected()
        assertNotNull(selected)
        assertEquals("Scheduled", selected.reason)
        assertEquals(targetUid, selected.uid)
    }

    @Test
    fun `pending uid survives a snapshot that does not yet contain the event`() = runBlocking {
        // Screen subscribed while a namespace WITHOUT the event is selected.
        client.setSelectedNamespace("ns-a")
        awaitSnapshot { events -> events.isEmpty() }

        vm.setParams(targetUid)

        // A snapshot lacking the target lands after setParams (the racing
        // pre-switch replay in the real sequence).
        val decoy = server.createClient()
        try {
            decoy.createEvent(name = "decoy", namespace = "ns-a", host = "node-1")
        } finally {
            decoy.close()
        }
        awaitSnapshot { events -> events.any { it.uid != targetUid } }
        assertNull(vm.selected.value, "a non-containing snapshot must not consume the pending uid")

        // Now the namespace switch completes and the event appears.
        client.setSelectedNamespace("ns-b")

        assertEquals(targetUid, awaitSelected().uid)
    }

    @Test
    fun `resolving a jump clears the type and node allowlists that would hide the event`() = runBlocking {
        client.setSelectedNamespace("ns-b")
        awaitSnapshot { events -> events.any { it.uid == targetUid } }
        // Left over from an earlier visit: the target is Normal on node-1.
        vm.setTypeFilter(setOf("Warning"))
        vm.setNodeFilter(setOf("node-2"))

        vm.setParams(targetUid)
        awaitSelected()

        assertNull(vm.typeFilter.value)
        assertNull(vm.nodeFilter.value)
    }

    @Test
    fun `resolving a jump keeps allowlists that already show the event`() = runBlocking {
        client.setSelectedNamespace("ns-b")
        awaitSnapshot { events -> events.any { it.uid == targetUid } }
        vm.setTypeFilter(setOf("Normal", "Warning"))
        vm.setNodeFilter(setOf("node-1"))

        vm.setParams(targetUid)
        awaitSelected()

        assertEquals(setOf("Normal", "Warning"), vm.typeFilter.value)
        assertEquals(setOf("node-1"), vm.nodeFilter.value)
    }

    @Test
    fun `a manual row click dismisses an unresolved jump`() = runBlocking {
        client.setSelectedNamespace("ns-a")
        awaitSnapshot { events -> events.isEmpty() }
        vm.setParams(targetUid)

        // The user clicks another row before the jump target ever shows up;
        // its later arrival must not hijack the selection.
        vm.dismissPendingSelection()
        client.setSelectedNamespace("ns-b")
        awaitSnapshot { events -> events.any { it.uid == targetUid } }

        assertNull(vm.selected.value)
    }

    @Test
    fun `filterHides is false for no allowlist and for a listed value`() {
        assertFalse(filterHides(null, "Normal"))
        assertFalse(filterHides(setOf("Normal"), "Normal"))
        assertTrue(filterHides(setOf("Warning"), "Normal"))
        assertTrue(filterHides(emptySet(), "Normal"))
    }
}
