package com.kubekubedashdash.services

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import com.kubekubedashdash.services.logtail.FakeNamespaceTailGateway
import com.kubekubedashdash.services.logtail.NamespaceTailEngine
import com.kubekubedashdash.services.logtail.NamespaceTailTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the tail-tab variant of [LogStreamRegistry] — session scoping,
 * the running-task-focus short circuit in [LogStreamRegistry.openOrFocusTailTab],
 * that [LogStreamRegistry.close] genuinely cancels the underlying tail job,
 * and the at-most-one-tail-per-session eviction. Every test calls
 * [LogStreamRegistry.openOrFocusTailTab] directly — never
 * [LogStreamRegistry.openOrFocusTail] — so no test constructs a
 * [com.kubekubedashdash.model.ClusterSession]. Real [NamespaceTailTask]s are
 * built via [NamespaceTailEngine.start] against [FakeNamespaceTailGateway].
 */
class LogStreamRegistryTailTest {

    /**
     * Tail tasks must NOT be started on the `runBlocking` scope of a test: the
     * engine's job runs until cancelled, and `runBlocking` does not return
     * until every child completes, so any task the test does not explicitly
     * close would deadlock the test method — and `@AfterTest` never runs,
     * because the method never returns. A dedicated scope torn down here keeps
     * that failure mode impossible.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanup() {
        LogStreamRegistry.clearAll()
        scope.cancel()
    }

    private fun testContainer(name: String) = CaptureContainerSpec(
        name = name,
        kind = CaptureContainerKind.MAIN,
        restartCount = 0,
        started = true,
    )

    private fun testPod(name: String, vararg containers: CaptureContainerSpec) = CapturePodSpec(name = name, phase = "Running", containers = containers.toList())

    private fun activeTask(key: String): NamespaceTailTask = (LogStreamRegistry.tabs.value.getValue(key) as ActiveNamespaceTail).task

    private suspend fun awaitStream(gateway: FakeNamespaceTailGateway, podName: String, container: String?, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) {
            while (!gateway.hasStream(podName, container)) yield()
        }
    }

    @Test
    fun `tail tab is removed by closeAllForSession for its own session`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()

        val key = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-a") {
            NamespaceTailEngine.start(scope, gateway, "example-namespace-a")
        }
        assertTrue(key in LogStreamRegistry.tabs.value)

        LogStreamRegistry.closeAllForSession(SessionId("session-a"))

        assertFalse(key in LogStreamRegistry.tabs.value)
    }

    @Test
    fun `openOrFocusTailTab focuses a running task without invoking the factory`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        gateway.pushSnapshot(listOf(testPod("pod-a", testContainer("app"))))

        val key = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-a") {
            NamespaceTailEngine.start(scope, gateway, "example-namespace-a")
        }
        awaitStream(gateway, "pod-a", "app")
        val originalTask = activeTask(key)
        assertTrue(originalTask.isRunning)

        var factoryInvoked = false
        val key2 = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-a") {
            factoryInvoked = true
            error("factory must not be invoked while the existing tail task is still running")
        }

        assertEquals(key, key2)
        assertFalse(factoryInvoked)
        assertTrue(activeTask(key2).isRunning)

        LogStreamRegistry.close(key)
        withTimeout(5_000) { originalTask.job.join() }
        Unit
    }

    @Test
    fun `close cancels the running tail job`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        gateway.pushSnapshot(listOf(testPod("pod-a", testContainer("app"))))

        val key = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-a") {
            NamespaceTailEngine.start(scope, gateway, "example-namespace-a")
        }
        awaitStream(gateway, "pod-a", "app")
        val task = activeTask(key)
        assertTrue(task.isRunning)

        LogStreamRegistry.close(key)
        assertFalse(key in LogStreamRegistry.tabs.value)

        withTimeout(5_000) { task.job.join() }
        assertFalse(task.isRunning)
    }

    @Test
    fun `opening a tail for a second namespace replaces the first in the same session`() = runBlocking {
        val gatewayA = FakeNamespaceTailGateway()
        gatewayA.pushSnapshot(listOf(testPod("pod-a", testContainer("app"))))
        val gatewayB = FakeNamespaceTailGateway()
        gatewayB.pushSnapshot(listOf(testPod("pod-b", testContainer("app"))))
        val gatewayOther = FakeNamespaceTailGateway()
        gatewayOther.pushSnapshot(listOf(testPod("pod-c", testContainer("app"))))

        val keyA = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-a") {
            NamespaceTailEngine.start(scope, gatewayA, "example-namespace-a")
        }
        awaitStream(gatewayA, "pod-a", "app")
        val taskA = activeTask(keyA)

        val keyOther = LogStreamRegistry.openOrFocusTailTab("session-b", "example-namespace-a") {
            NamespaceTailEngine.start(scope, gatewayOther, "example-namespace-a")
        }
        awaitStream(gatewayOther, "pod-c", "app")

        val keyB = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-b") {
            NamespaceTailEngine.start(scope, gatewayB, "example-namespace-b")
        }
        awaitStream(gatewayB, "pod-b", "app")

        val tabs = LogStreamRegistry.tabs.value
        val tailsForSessionA = tabs.values.filterIsInstance<ActiveNamespaceTail>().filter { it.sessionId == "session-a" }
        assertEquals(1, tailsForSessionA.size)
        assertEquals(keyB, tailsForSessionA.single().key)
        assertFalse(keyA in tabs)

        withTimeout(5_000) { taskA.job.join() }
        assertFalse(taskA.isRunning)

        // A tail under a different session id is untouched by the eviction.
        assertTrue(keyOther in tabs)
    }
}
