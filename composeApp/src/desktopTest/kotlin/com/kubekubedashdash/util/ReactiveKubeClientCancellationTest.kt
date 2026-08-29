package com.kubekubedashdash.util

import com.kubekubedashdash.models.ResourceState
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * Regression test for the namespace-switch reconnect loop.
 *
 * The bug: namespaced informer / polling flows in [ReactiveKubeClient] used
 * `flatMapLatest(_selectedNamespace)`. Switching namespace cancelled the
 * previous inner flow, which threw `kotlinx.coroutines.flow.internal.ChildCancelledException`
 * ("Child of the scoped flow was cancelled"). The catch blocks caught
 * `Exception` (which includes `CancellationException`) and called
 * `KubeConnectionManager.reportError` for each one. With multiple flows
 * subscribed, the shared `_consecutiveFailures` counter blew past its `>= 3`
 * threshold within milliseconds, setting `connectionError`. The UI saw the
 * error, scheduled a reconnect, the reconnect bumped `connectionVersion`,
 * which cancelled the flows again — infinite loop with the screen stuck
 * on "Unable to connect / Retrying in 8s".
 *
 * The fix re-throws `CancellationException` from the affected catch blocks
 * so cancellation propagates structurally instead of being misclassified.
 *
 * This test reproduces the original trigger conditions: ≥ 3 namespaced flows
 * subscribed simultaneously, then a single `setSelectedNamespace` call. With
 * the bug present, that one switch trips `connectionError` to a non-null
 * cancellation message; with the fix, it stays null.
 */
class ReactiveKubeClientCancellationTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var manager: KubeConnectionManager
    private lateinit var client: ReactiveKubeClient
    private lateinit var scope: CoroutineScope

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
            seed.namespaces().resource(
                NamespaceBuilder().withNewMetadata().withName("ns-b").endMetadata().build(),
            ).create()
            seed.pods().inNamespace("ns-a").resource(
                PodBuilder().withNewMetadata().withName("pod-a").withNamespace("ns-a").endMetadata().build(),
            ).create()
            seed.pods().inNamespace("ns-b").resource(
                PodBuilder().withNewMetadata().withName("pod-b").withNamespace("ns-b").endMetadata().build(),
            ).create()
        } finally {
            seed.close()
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        manager = KubeConnectionManager()
        manager.connectWithClient(server.createClient(), "test-cluster").getOrThrow()
        client = ReactiveKubeClient(scope, manager)
    }

    @AfterTest
    fun tearDown() {
        shutdownCleanly(scope, label = "ReactiveKubeClientCancellationTest", manager = manager, servers = listOf(server))
    }

    @Test
    fun `namespace switch with multiple subscribed flows does not register a connection error`() = runBlocking {
        client.setSelectedNamespace("ns-a")

        // Capture every value `connectionError` ever takes on, not just its final
        // value. After a buggy cancellation trips the failure counter, the very
        // next informer sync immediately calls reportSuccess() which clears the
        // error — so checking `connectionError.value` once at the end is racy
        // and would mask the bug. Recording the full sequence catches any
        // transient non-null value that briefly flashes through.
        val observedErrors = CopyOnWriteArrayList<String?>()
        val errorCollector = scope.launch {
            manager.connectionError.collect { observedErrors.add(it) }
        }

        // Subscribe to six namespaced informer flows so a single namespace switch
        // produces six simultaneous flatMapLatest cancellations. The connection-
        // error threshold is `>= 3` consecutive failures, so this is well above
        // it — without the fix, the first switch trips connectionError to a
        // cancellation message.
        val collectors = listOf(
            scope.launch { client.pods.collect {} },
            scope.launch { client.deployments.collect {} },
            scope.launch { client.services.collect {} },
            scope.launch { client.replicaSets.collect {} },
            scope.launch { client.statefulSets.collect {} },
            scope.launch { client.daemonSets.collect {} },
        )

        // Wait for the initial sync to settle on ns-a.
        withTimeout(15_000) {
            client.pods.first { it is ResourceState.Success && it.data.any { p -> p.namespace == "ns-a" } }
        }
        delay(500)

        // The trigger: switch namespace. Every subscribed flow's `flatMapLatest`
        // cancels its inner flow and starts a new one for ns-b. Pre-fix, each
        // cancellation called reportError; six in quick succession tripped the
        // failure threshold and set connectionError.
        client.setSelectedNamespace("ns-b")

        // Wait until the new namespace's data has propagated.
        withTimeout(15_000) {
            client.pods.first { state ->
                state is ResourceState.Success && state.data.any { it.namespace == "ns-b" }
            }
        }
        // Flush any in-flight cancellations from the other five flows.
        delay(1_000)

        // The collector itself emits the StateFlow's initial null on subscribe;
        // any subsequent non-null value is the bug.
        val nonNullErrors = observedErrors.filterNotNull()
        assertEquals(
            emptyList<String>(),
            nonNullErrors,
            "namespace switch must never set connectionError (saw: $nonNullErrors)",
        )

        collectors.forEach { it.cancel() }
        errorCollector.cancel()
    }
}
