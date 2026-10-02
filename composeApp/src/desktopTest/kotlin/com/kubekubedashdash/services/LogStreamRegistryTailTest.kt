package com.kubekubedashdash.services

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import com.kubekubedashdash.services.logtail.FakeNamespaceTailGateway
import com.kubekubedashdash.services.logtail.NamespaceTailEngine
import com.kubekubedashdash.services.logtail.NamespaceTailTask
import com.kubekubedashdash.services.logtail.TailPodRef
import com.kubekubedashdash.services.logtail.TailState
import com.kubekubedashdash.services.logtail.TailTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    private fun podSet(namespace: String, vararg names: String) = TailTarget.Pods(names.map { TailPodRef(namespace, it) }.toSet())

    private fun tailTab(target: TailTarget) = ActiveNamespaceTail(
        sessionId = "session-a",
        task = NamespaceTailTask(target, MutableStateFlow(TailState()), Job()),
        openedAt = 0L,
    )

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

    @Test
    fun `opening the same pod set again focuses the existing tab without invoking the factory`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        gateway.pushSnapshot(listOf(testPod("pod-a", testContainer("app")), testPod("pod-b", testContainer("app"))))
        val target = podSet("example-namespace-a", "pod-a", "pod-b")

        val opened = LogStreamRegistry.openOrFocusTailTab("session-a", target) {
            NamespaceTailEngine.start(scope, gateway, target)
        }
        awaitStream(gateway, "pod-a", "app")
        val originalTask = activeTask(opened.key)
        assertTrue(originalTask.isRunning)
        assertTrue(opened.replaced.isEmpty())

        // Same set, different selection order.
        var factoryInvoked = false
        val again = LogStreamRegistry.openOrFocusTailTab("session-a", podSet("example-namespace-a", "pod-b", "pod-a")) {
            factoryInvoked = true
            error("factory must not be invoked while the existing pod-set tail is still running")
        }

        assertEquals(opened.key, again.key)
        assertTrue(again.replaced.isEmpty())
        assertFalse(factoryInvoked)
        assertEquals(opened.key, LogStreamRegistry.focusedKey.value)
        assertTrue(activeTask(again.key).isRunning)

        LogStreamRegistry.close(opened.key)
        withTimeout(5_000) { originalTask.job.join() }
        Unit
    }

    @Test
    fun `opening a different pod set replaces the running one and reports its label`() = runBlocking {
        val gatewayA = FakeNamespaceTailGateway()
        gatewayA.pushSnapshot(listOf(testPod("pod-a", testContainer("app")), testPod("pod-b", testContainer("app"))))
        val gatewayB = FakeNamespaceTailGateway()
        gatewayB.pushSnapshot(listOf(testPod("pod-a", testContainer("app")), testPod("pod-c", testContainer("app"))))
        val setA = podSet("example-namespace-a", "pod-a", "pod-b")
        val setB = TailTarget.Pods(setOf(TailPodRef("example-namespace-a", "pod-a"), TailPodRef("example-namespace-b", "pod-c")))

        val openedA = LogStreamRegistry.openOrFocusTailTab("session-a", setA) {
            NamespaceTailEngine.start(scope, gatewayA, setA)
        }
        awaitStream(gatewayA, "pod-a", "app")
        val taskA = activeTask(openedA.key)

        val openedB = LogStreamRegistry.openOrFocusTailTab("session-a", setB) {
            NamespaceTailEngine.start(scope, gatewayB, setB)
        }
        awaitStream(gatewayB, "pod-c", "app")

        assertNotEquals(openedA.key, openedB.key)
        assertEquals(listOf("Tail · 2 pods · example-namespace-a"), openedB.replaced)
        val tabs = LogStreamRegistry.tabs.value
        assertFalse(openedA.key in tabs)
        assertEquals("Tail · 2 pods · 2 namespaces", tabs.getValue(openedB.key).displayLabel)
        assertEquals(1, tabs.values.filterIsInstance<ActiveNamespaceTail>().count { it.sessionId == "session-a" })

        withTimeout(5_000) { taskA.job.join() }
        assertFalse(taskA.isRunning)
        assertTrue(activeTask(openedB.key).isRunning)
    }

    @Test
    fun `a pod-set tail replaces a namespace tail of its session and leaves another session's tail alone`() = runBlocking {
        val gatewayNamespace = FakeNamespaceTailGateway()
        gatewayNamespace.pushSnapshot(listOf(testPod("pod-a", testContainer("app"))))
        val gatewayOther = FakeNamespaceTailGateway()
        gatewayOther.pushSnapshot(listOf(testPod("pod-c", testContainer("app"))))
        val gatewayPods = FakeNamespaceTailGateway()
        gatewayPods.pushSnapshot(listOf(testPod("pod-b", testContainer("app"))))
        val otherSet = podSet("example-namespace-a", "pod-c", "pod-d")
        val podsTarget = podSet("example-namespace-a", "pod-b", "pod-e")

        val namespaceKey = LogStreamRegistry.openOrFocusTailTab("session-a", "example-namespace-a") {
            NamespaceTailEngine.start(scope, gatewayNamespace, "example-namespace-a")
        }
        awaitStream(gatewayNamespace, "pod-a", "app")
        val namespaceTask = activeTask(namespaceKey)

        val other = LogStreamRegistry.openOrFocusTailTab("session-b", otherSet) {
            NamespaceTailEngine.start(scope, gatewayOther, otherSet)
        }
        awaitStream(gatewayOther, "pod-c", "app")

        val opened = LogStreamRegistry.openOrFocusTailTab("session-a", podsTarget) {
            NamespaceTailEngine.start(scope, gatewayPods, podsTarget)
        }
        awaitStream(gatewayPods, "pod-b", "app")

        assertEquals(listOf("Tail · example-namespace-a"), opened.replaced)
        val tabs = LogStreamRegistry.tabs.value
        assertFalse(namespaceKey in tabs)
        assertTrue(opened.key in tabs)
        assertTrue(other.key in tabs)
        withTimeout(5_000) { namespaceTask.job.join() }
        assertFalse(namespaceTask.isRunning)
        assertTrue(activeTask(other.key).isRunning)
    }

    @Test
    fun `tailTabKey is order-independent for a pod set and unchanged for a namespace`() {
        val a = TailPodRef("example-namespace-a", "pod-a")
        val b = TailPodRef("example-namespace-b", "pod-b")

        assertEquals(tailTabKey("session-a", TailTarget.Pods(setOf(a, b))), tailTabKey("session-a", TailTarget.Pods(setOf(b, a))))
        assertEquals(
            "tail|session-a|pods|example-namespace-a/pod-a,example-namespace-b/pod-b",
            tailTabKey("session-a", TailTarget.Pods(setOf(b, a))),
        )
        assertNotEquals(tailTabKey("session-a", TailTarget.Pods(setOf(a, b))), tailTabKey("session-a", TailTarget.Pods(setOf(a))))
        assertNotEquals(tailTabKey("session-a", TailTarget.Pods(setOf(a))), tailTabKey("session-b", TailTarget.Pods(setOf(a))))
        assertEquals("tail|session-a|example-namespace-a", tailTabKey("session-a", TailTarget.Namespace("example-namespace-a")))
    }

    @Test
    fun `tail tab keys and labels follow the target`() {
        val namespaceTab = tailTab(TailTarget.Namespace("example-namespace-a"))
        assertEquals("tail|session-a|example-namespace-a", namespaceTab.key)
        assertEquals("Tail · example-namespace-a", namespaceTab.displayLabel)

        val oneNamespace = tailTab(podSet("example-namespace-a", "pod-a", "pod-b", "pod-c"))
        assertEquals("Tail · 3 pods · example-namespace-a", oneNamespace.displayLabel)

        val twoNamespaces = tailTab(TailTarget.Pods(setOf(TailPodRef("example-namespace-a", "pod-a"), TailPodRef("example-namespace-b", "pod-a"))))
        assertEquals("Tail · 2 pods · 2 namespaces", twoNamespaces.displayLabel)
        assertEquals("tail|session-a|pods|example-namespace-a/pod-a,example-namespace-b/pod-a", twoNamespaces.key)
    }
}
