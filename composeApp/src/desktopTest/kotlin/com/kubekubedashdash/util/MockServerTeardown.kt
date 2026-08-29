package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val JOIN_TIMEOUT_MS = 30_000L
private const val SLOW_DESTROY_MS = 9_000L

/**
 * Deterministic teardown for tests that pair coroutine scopes with a
 * [KubernetesMockServer].
 *
 * `CoroutineScope.cancel()` only *requests* cancellation and returns at once, so
 * the older `scope.cancel(); manager.close(); server.destroy()` shape let
 * still-unwinding coroutines outlive the objects they use — they could observe
 * a half-closed [KubeConnectionManager] or a destroyed server. Joining closes
 * that ordering gap.
 *
 * Honest scope of the claim: no test failure has been *attributed* to that gap.
 * It was written while chasing an intermittent CI failure in which
 * `KubernetesMockServer.destroy()` exceeded fabric8's 10s shutdown budget, and
 * that diagnosis is still unconfirmed — `MockWebServer.shutdown()` has two such
 * awaits (`HttpServer.close()` and `Vertx.close()`) which throw the identical
 * exception, and the message string that would tell them apart is not in the
 * Gradle console output. Treat this as ordering hygiene, not as a proven fix.
 *
 * What the join does and does not buy: when this returns, no test-owned
 * coroutine is still issuing work. It does NOT guarantee the underlying sockets
 * are shut — fabric8's `informer.close()` and `KubernetesClient.close()` both
 * hand socket teardown to Vert.x without awaiting it.
 *
 * Call only from an ordinary test thread. `vm.viewModelScope` runs on
 * `Dispatchers.Main.immediate`, which resolves to the Swing EDT here; calling
 * this from the EDT would stall the join until [JOIN_TIMEOUT_MS] rather than
 * deadlock outright, since the timeout is driven by [runBlocking]'s own event
 * loop on the calling thread. Either way, don't.
 */
internal fun shutdownCleanly(
    vararg scopes: CoroutineScope,
    label: String,
    manager: KubeConnectionManager? = null,
    client: KubernetesClient? = null,
    servers: List<KubernetesMockServer> = emptyList(),
) {
    scopes.forEachIndexed { index, scope ->
        val job = scope.coroutineContext.job
        val startedAt = System.nanoTime()
        val joined = runBlocking { withTimeoutOrNull(JOIN_TIMEOUT_MS) { job.cancelAndJoin() } }
        if (joined == null) {
            // Diagnostic, deliberately not a failure: a slow unwind is not a
            // violated invariant of the code under test, and failing here would
            // blame this class for whatever is actually still running.
            System.err.println(
                "[shutdownCleanly] $label: scope #$index did not unwind within ${JOIN_TIMEOUT_MS}ms " +
                    "(${(System.nanoTime() - startedAt) / 1_000_000}ms elapsed); " +
                    "live children: ${job.children.toList()}",
            )
        }
    }
    // Every close and destroy gets its turn even if an earlier one throws, and
    // the first failure is rethrown at the end. Bailing out mid-list would
    // leave a mock server alive — its Vert.x event-loop groups and listening
    // socket would survive for the rest of the worker JVM, adding scheduling
    // pressure to every later test class and obscuring which teardown failed
    // first. Failures are still surfaced, never swallowed: a suppressed
    // shutdown timeout is exactly what makes this class of flake invisible.
    val failures = mutableListOf<Throwable>()
    manager?.let { runCatching { it.close() }.onFailure(failures::add) }
    client?.let { runCatching { it.close() }.onFailure(failures::add) }
    servers.forEach { server ->
        val startedAt = System.nanoTime()
        // A second destroy() of an already-destroyed server is a real no-op in
        // fabric8 7.8.0 (its `shutdown` flag short-circuits before the awaits),
        // so the one test that destroys its server mid-body needs no guard.
        runCatching { server.destroy() }.onFailure(failures::add)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        if (elapsedMs >= SLOW_DESTROY_MS) {
            System.err.println(
                "[shutdownCleanly] $label: server.destroy() took ${elapsedMs}ms " +
                    "(each of fabric8's two shutdown awaits gives up at 10000ms)",
            )
        }
    }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}
