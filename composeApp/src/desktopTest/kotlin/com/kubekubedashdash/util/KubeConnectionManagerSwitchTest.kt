package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for audit C1: switching the cluster on a live session
 * must not flash a connection error.
 *
 * The bug: `KubeConnectionManager.connect*` closed the old OkHttp client
 * synchronously *before* bumping `_connectionVersion`. ReactiveKubeClient's
 * informers only tear down once that bump propagates through
 * `flatMapLatest`, so in the gap the old fabric8 watch threads kept hitting
 * a dead client, threw, and called `reportError` / emitted
 * `ResourceState.Error` — an "Unable to connect" flash on every switch.
 *
 * The fix bumps the version first (cancelling the old informers) and
 * retires the previous client off-thread after a grace period, so
 * `informer.close()` runs against a still-open client.
 *
 * This subscribes several informer flows against mock server A, then
 * switches the same manager to mock server B (the `connectWithClient`
 * path shares the exact C1 code path). With the bug present the switch
 * sets `connectionError` / emits `Error`; with the fix both stay clean.
 */
class KubeConnectionManagerSwitchTest {

    private lateinit var serverA: KubernetesMockServer
    private lateinit var serverB: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

    private fun newServer(podName: String): KubernetesMockServer {
        val s = KubernetesMockServer(
            Context(),
            MockWebServer(),
            HashMap(),
            KubernetesCrudDispatcher(),
            false,
        )
        s.init()
        val seed = s.createClient()
        try {
            seed.pods().inNamespace("default").resource(
                PodBuilder().withNewMetadata().withName(podName).withNamespace("default").endMetadata().build(),
            ).create()
        } finally {
            seed.close()
        }
        return s
    }

    @BeforeTest
    fun setUp() {
        serverA = newServer("pod-a")
        serverB = newServer("pod-b")
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(serverA.createClient(), "cluster-a").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        manager.close()
        serverA.destroy()
        serverB.destroy()
    }

    @Test
    fun `switching cluster on a live session does not flash a connection error`() = runBlocking {
        val observedErrors = CopyOnWriteArrayList<String?>()
        val podStates = CopyOnWriteArrayList<ResourceState<*>>()

        val errorCollector = scope.launch {
            manager.connectionError.collect { observedErrors.add(it) }
        }
        // Several informer flows so a switch produces several simultaneous
        // flatMapLatest cancellations against the old client (the C1 trigger).
        val collectors = listOf(
            scope.launch { client.pods.collect { podStates.add(it) } },
            scope.launch { client.deployments.collect {} },
            scope.launch { client.services.collect {} },
            scope.launch { client.replicaSets.collect {} },
            scope.launch { client.statefulSets.collect {} },
        )

        // Settle on cluster A.
        withTimeout(15_000) {
            client.pods.first { s -> s is ResourceState.Success && s.data.any { it.name == "pod-a" } }
        }
        delay(500)

        // The switch: same manager, new backing client (shares the C1 path).
        manager.connectWithClient(serverB.createClient(), "cluster-b").getOrThrow()

        // New cluster's data must propagate.
        withTimeout(15_000) {
            client.pods.first { s -> s is ResourceState.Success && s.data.any { it.name == "pod-b" } }
        }
        // Let any mis-classified cancellations / dead-client errors flush
        // (also covers the retire grace window).
        delay(1_500)

        val nonNullErrors = observedErrors.filterNotNull()
        assertEquals(
            emptyList<String>(),
            nonNullErrors,
            "cluster switch must never set connectionError (saw: $nonNullErrors)",
        )
        val errorStates = podStates.filterIsInstance<ResourceState.Error>()
        assertEquals(
            emptyList<ResourceState.Error>(),
            errorStates,
            "pods flow must not surface ResourceState.Error across a cluster switch (saw: $errorStates)",
        )
        // Sanity: the switch actually happened.
        assertTrue(manager.connectionVersion.value >= 2, "expected >=2 connects, got ${manager.connectionVersion.value}")

        collectors.forEach { it.cancel() }
        errorCollector.cancel()
    }
}
