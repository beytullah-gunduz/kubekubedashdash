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
 * `Dispatchers.Main.immediate`, which resolves to the Swing EDT here, and
 * [runBlocking] on the EDT would deadlock.
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
    manager?.close()
    client?.close()
    servers.forEach { server ->
        val startedAt = System.nanoTime()
        // Deliberately NOT wrapped in runCatching: fabric8 gives up after 10s and
        // throws, and swallowing that would turn a genuinely red build green.
        // A second destroy() of an already-destroyed server is a real no-op in
        // fabric8 7.8.0 (its `shutdown` flag short-circuits), so the one test
        // that destroys its server mid-body needs no guard here.
        server.destroy()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        if (elapsedMs >= SLOW_DESTROY_MS) {
            System.err.println(
                "[shutdownCleanly] $label: server.destroy() took ${elapsedMs}ms " +
                    "(fabric8 abandons the shutdown at 10000ms)",
            )
        }
    }
}
