package com.kubekubedashdash.services.logtail

import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [NamespaceTailEngine] against [FakeNamespaceTailGateway] with no
 * cluster involved. State updates are coalesced behind [flushIntervalMs], so
 * every assertion awaits a predicate on [NamespaceTailTask.state] instead of
 * reading `.value` right after a push.
 */
class NamespaceTailEngineTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    private fun testContainer(
        name: String,
        kind: CaptureContainerKind = CaptureContainerKind.MAIN,
        restartCount: Int = 0,
        started: Boolean = true,
    ) = CaptureContainerSpec(name = name, kind = kind, restartCount = restartCount, started = started)

    private fun testPod(
        name: String,
        vararg containers: CaptureContainerSpec,
        phase: String = "Running",
    ) = CapturePodSpec(name = name, phase = phase, containers = containers.toList())

    private fun startedNotice(pod: String) = "── $pod started ──"

    private fun endedNotice(pod: String) = "── $pod stream ended ──"

    private fun startTask(
        gateway: FakeNamespaceTailGateway,
        namespace: String = "example-namespace",
        flushIntervalMs: Long = 10,
        retryDelayMs: Long = 20,
    ) = NamespaceTailEngine.start(scope, gateway, namespace, flushIntervalMs, retryDelayMs)

    private suspend fun awaitState(
        task: NamespaceTailTask,
        timeoutMs: Long = 5_000,
        predicate: (TailState) -> Boolean,
    ): TailState = withTimeout(timeoutMs) { task.state.first(predicate) }

    /**
     * Polls until the engine's attach coroutine has actually called
     * [FakeNamespaceTailGateway.streamPodLogs] for this pod/container.
     * Attaching is asynchronous relative to the `attachedPods` state update,
     * so pushing a line right after seeing the pod attached would race a
     * still-unregistered channel and be silently dropped.
     */
    private suspend fun awaitStream(gateway: FakeNamespaceTailGateway, podName: String, container: String?, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) {
            while (!gateway.hasStream(podName, container)) {
                yield()
            }
        }
    }

    @Test
    fun `merges lines from multiple pods into one buffer`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app")),
                testPod("example-pod-b", testContainer("app")),
            ),
        )
        awaitState(task) { it.attachedPods.containsAll(listOf("example-pod-a", "example-pod-b")) }
        awaitStream(gateway, "example-pod-a", "app")
        awaitStream(gateway, "example-pod-b", "app")

        gateway.pushLine("example-pod-a", "app", "hello-a")
        gateway.pushLine("example-pod-b", "app", "hello-b")

        val state = awaitState(task) { s -> s.lines.any { it.text == "hello-a" } && s.lines.any { it.text == "hello-b" } }
        val logLines = state.lines.filterNot { it.notice }
        assertEquals(2, logLines.size)
        assertTrue(logLines.any { it.podName == "example-pod-a" && it.text == "hello-a" })
        assertTrue(logLines.any { it.podName == "example-pod-b" && it.text == "hello-b" })
    }

    @Test
    fun `tags each line with its own pod name`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app")),
                testPod("example-pod-b", testContainer("app")),
            ),
        )
        awaitState(task) { it.attachedPods.containsAll(listOf("example-pod-a", "example-pod-b")) }
        awaitStream(gateway, "example-pod-a", "app")
        awaitStream(gateway, "example-pod-b", "app")

        gateway.pushLine("example-pod-a", "app", "first")
        gateway.pushLine("example-pod-b", "app", "second")
        gateway.pushLine("example-pod-a", "app", "third")

        val state = awaitState(task) { s -> s.lines.count { !it.notice } == 3 }
        val logLines = state.lines.filterNot { it.notice }
        // Lines from different pods are appended by independent collector
        // coroutines and may interleave in any order; only per-pod order is
        // guaranteed (each pod's own channel is FIFO).
        assertEquals(listOf("first", "third"), logLines.filter { it.podName == "example-pod-a" }.map { it.text })
        assertEquals(listOf("second"), logLines.filter { it.podName == "example-pod-b" }.map { it.text })
    }

    @Test
    fun `attaches and emits a started notice when a snapshot adds a pod`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"))))

        // The `lines` flush is on its own timer, independent of the
        // `attachedPods` update — await the notice's actual arrival in
        // `lines`, not just the attach flag.
        val state = awaitState(task) { it.lines.any { l -> l.notice && l.text == startedNotice("example-pod-a") } }
        assertTrue(state.lines.any { it.notice && it.podName == "" && it.text == startedNotice("example-pod-a") })
    }

    @Test
    fun `detaches and emits an ended notice when a snapshot drops a pod`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }

        gateway.pushSnapshot(emptyList())
        val state = awaitState(task) { it.lines.any { l -> l.notice && l.text == endedNotice("example-pod-a") } }
        assertTrue(state.lines.any { it.notice && it.text == endedNotice("example-pod-a") })
        assertFalse("example-pod-a" in state.attachedPods)
    }

    @Test
    fun `emits an ended notice when a stream completes normally`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")

        gateway.completeStream("example-pod-a", "app")
        val state = awaitState(task) { it.lines.any { l -> l.notice && l.text == endedNotice("example-pod-a") } }
        assertTrue(state.lines.any { it.notice && it.text == endedNotice("example-pod-a") })
        assertFalse("example-pod-a" in state.attachedPods)
    }

    @Test
    fun `one pod stream failing leaves the others running`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app")),
                testPod("example-pod-b", testContainer("app")),
            ),
        )
        awaitState(task) { it.attachedPods.containsAll(listOf("example-pod-a", "example-pod-b")) }
        awaitStream(gateway, "example-pod-a", "app")
        awaitStream(gateway, "example-pod-b", "app")

        gateway.failStream("example-pod-a", "app", "boom")
        awaitState(task) { !it.attachedPods.contains("example-pod-a") }

        gateway.pushLine("example-pod-b", "app", "still-alive")
        val state = awaitState(task) { s -> s.lines.any { it.text == "still-alive" } }
        assertTrue("example-pod-b" in state.attachedPods)
    }

    @Test
    fun `a discovery failure sets error and recovers on the next emission`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)

        gateway.failDiscovery("rbac denied")
        awaitState(task) { it.error == "rbac denied" }

        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"))))
        val state = awaitState(task) { it.error == null && it.attachedPods.contains("example-pod-a") }
        assertNull(state.error)
    }

    @Test
    fun `caps at forty streams and sets a cap notice`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        val pods = (0..NamespaceTailEngine.MAX_STREAMS).map { i ->
            testPod("example-pod-%02d".format(i), testContainer("app"))
        }
        gateway.pushSnapshot(pods)

        val state = awaitState(task) { it.capNotice != null }
        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.attachedPods.size)
        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.streamCount)
        assertEquals(
            "Tailing ${NamespaceTailEngine.MAX_STREAMS} of ${NamespaceTailEngine.MAX_STREAMS + 1} pods (stream limit) — Capture logs saves the full record.",
            state.capNotice,
        )
    }

    @Test
    fun `an attached pod keeps its slot when a new pod appears over the cap`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        val podNames = (0 until NamespaceTailEngine.MAX_STREAMS).map { i -> "example-pod-%02d".format(i) }
        val pods = podNames.map { name -> testPod(name, testContainer("app")) }
        gateway.pushSnapshot(pods)
        awaitState(task) { it.attachedPods.size == NamespaceTailEngine.MAX_STREAMS }

        val overflowPod = testPod("example-pod-40", testContainer("app"))
        gateway.pushSnapshot(pods + overflowPod)
        val state = awaitState(task) { it.capNotice != null }

        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.attachedPods.size)
        assertTrue(podNames.all { it in state.attachedPods })
        assertFalse("example-pod-40" in state.attachedPods)
        assertEquals(
            "Tailing 40 of 41 pods (stream limit) — Capture logs saves the full record.",
            state.capNotice,
        )
    }

    @Test
    fun `freed slots are filled by the next fitting pod in name order`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        val fillNames = (0 until NamespaceTailEngine.MAX_STREAMS).map { i -> "example-pod-%02d".format(i) }
        val fillPods = fillNames.map { name -> testPod(name, testContainer("app")) }
        val wideBlocked = testPod("example-pod-40", testContainer("app"), testContainer("sidecar"))
        val narrowWaiting = testPod("example-pod-41", testContainer("app"))

        gateway.pushSnapshot(fillPods + listOf(wideBlocked, narrowWaiting))
        awaitState(task) { it.attachedPods.size == NamespaceTailEngine.MAX_STREAMS && it.capNotice != null }
        awaitStream(gateway, "example-pod-00", "app")

        gateway.completeStream("example-pod-00", "app")

        val state = awaitState(task) { it.attachedPods.contains("example-pod-41") }
        assertFalse("example-pod-00" in state.attachedPods)
        assertFalse("example-pod-40" in state.attachedPods)
        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.attachedPods.size)
        assertEquals(
            "Tailing 40 of 41 pods (stream limit) — Capture logs saves the full record.",
            state.capNotice,
        )
    }

    @Test
    fun `ring buffer evicts oldest lines and counts them as dropped`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")

        // 1 started notice + this many log lines overshoots the cap by exactly 5.
        val logLineCount = NamespaceTailEngine.MAX_BUFFERED_LINES + 4
        for (i in 0 until logLineCount) {
            gateway.pushLine("example-pod-a", "app", "line-%05d".format(i))
        }

        val state = awaitState(task, timeoutMs = 20_000) { it.droppedLines == 5L }
        assertEquals(NamespaceTailEngine.MAX_BUFFERED_LINES, state.lines.size)
        assertEquals("line-00004", state.lines.first().text)
        assertEquals("line-%05d".format(logLineCount - 1), state.lines.last().text)
    }

    @Test
    fun `init and ephemeral containers are never tailed`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        val ineligiblePod = testPod(
            "example-pod-a",
            testContainer("setup", kind = CaptureContainerKind.INIT),
            testContainer("debugger", kind = CaptureContainerKind.EPHEMERAL),
        )
        val eligiblePod = testPod("example-pod-b", testContainer("app"))
        gateway.pushSnapshot(listOf(ineligiblePod, eligiblePod))

        val state = awaitState(task) { it.attachedPods.contains("example-pod-b") }
        assertFalse("example-pod-a" in state.attachedPods)
        assertEquals(1, state.streamCount)
    }

    @Test
    fun `terminal pods are never tailed`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        val succeeded = testPod("example-pod-a", testContainer("app"), phase = "Succeeded")
        val failed = testPod("example-pod-b", testContainer("app"), phase = "Failed")
        val running = testPod("example-pod-c", testContainer("app"))
        gateway.pushSnapshot(listOf(succeeded, failed, running))

        val state = awaitState(task) { it.attachedPods.contains("example-pod-c") }
        assertFalse("example-pod-a" in state.attachedPods)
        assertFalse("example-pod-b" in state.attachedPods)
        assertEquals(1, state.streamCount)
    }

    @Test
    fun `container name is included only for multi-container pods`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        val single = testPod("example-pod-a", testContainer("app"))
        val multi = testPod("example-pod-b", testContainer("app"), testContainer("sidecar"))
        gateway.pushSnapshot(listOf(single, multi))
        awaitState(task) { it.attachedPods.containsAll(listOf("example-pod-a", "example-pod-b")) }
        awaitStream(gateway, "example-pod-a", "app")
        awaitStream(gateway, "example-pod-b", "app")
        awaitStream(gateway, "example-pod-b", "sidecar")

        gateway.pushLine("example-pod-a", "app", "solo-line")
        gateway.pushLine("example-pod-b", "app", "app-line")
        gateway.pushLine("example-pod-b", "sidecar", "sidecar-line")

        val state = awaitState(task) { s ->
            s.lines.any { it.text == "solo-line" } &&
                s.lines.any { it.text == "app app-line" } &&
                s.lines.any { it.text == "sidecar sidecar-line" }
        }
        assertTrue(state.lines.any { it.podName == "example-pod-a" && it.text == "solo-line" })
        assertTrue(state.lines.any { it.podName == "example-pod-b" && it.text == "app app-line" })
        assertTrue(state.lines.any { it.podName == "example-pod-b" && it.text == "sidecar sidecar-line" })
    }

    @Test
    fun `stop cancels every collector without emitting ended notices`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app")),
                testPod("example-pod-b", testContainer("app")),
            ),
        )
        awaitState(task) { it.attachedPods.size == 2 }

        task.stop()
        withTimeout(5_000) { task.job.join() }

        assertFalse(task.isRunning)
        val finalLines = task.state.value.lines
        assertTrue(finalLines.none { it.notice && it.text.endsWith("stream ended ──") })
    }

    @Test
    fun `one container ending does not detach a multi-container pod`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"), testContainer("sidecar"))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") && it.streamCount == 2 }
        awaitStream(gateway, "example-pod-a", "app")

        gateway.completeStream("example-pod-a", "app")
        val partial = awaitState(task) { it.streamCount == 1 }
        assertTrue("example-pod-a" in partial.attachedPods)
        assertTrue(partial.lines.none { it.notice && it.text == endedNotice("example-pod-a") })

        awaitStream(gateway, "example-pod-a", "sidecar")
        gateway.completeStream("example-pod-a", "sidecar")
        val full = awaitState(task) { it.lines.any { l -> l.notice && l.text == endedNotice("example-pod-a") } }
        assertTrue(full.lines.any { it.notice && it.text == endedNotice("example-pod-a") })
        assertFalse("example-pod-a" in full.attachedPods)
    }

    @Test
    fun `a pod reattaches when its restart count increases`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app", restartCount = 0))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")

        gateway.completeStream("example-pod-a", "app")
        awaitState(task) { !it.attachedPods.contains("example-pod-a") }

        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app", restartCount = 1))))
        val state = awaitState(task) { it.lines.count { l -> l.notice && l.text == startedNotice("example-pod-a") } == 2 }
        assertEquals(2, state.lines.count { it.notice && it.text == startedNotice("example-pod-a") })
    }

    @Test
    fun `a pod whose stream failed does not reattach on snapshot churn alone`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app", restartCount = 2))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")

        gateway.pushLine("example-pod-a", "app", "Error: connection reset")
        gateway.completeStream("example-pod-a", "app")
        awaitState(task) { !it.attachedPods.contains("example-pod-a") }

        // Churn 1: unchanged restart count must not reattach it. A sentinel
        // pod's attach is the positive signal that this snapshot was applied.
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app", restartCount = 2)),
                testPod("example-pod-sentinel-1", testContainer("app")),
            ),
        )
        var state = awaitState(task) { it.attachedPods.contains("example-pod-sentinel-1") }
        assertFalse("example-pod-a" in state.attachedPods)

        // Churn 2: same story, different sentinel.
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app", restartCount = 2)),
                testPod("example-pod-sentinel-1", testContainer("app")),
                testPod("example-pod-sentinel-2", testContainer("app")),
            ),
        )
        state = awaitState(task) { it.attachedPods.contains("example-pod-sentinel-2") }
        assertFalse("example-pod-a" in state.attachedPods)
    }

    @Test
    fun `an ended notice is emitted once when a pod is deleted mid-stream`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")
        gateway.pushLine("example-pod-a", "app", "still-running")

        gateway.pushSnapshot(emptyList())
        var state = awaitState(task) { it.lines.count { l -> l.notice && l.text == endedNotice("example-pod-a") } == 1 }
        assertEquals(1, state.lines.count { it.notice && it.text == endedNotice("example-pod-a") })
        assertFalse("example-pod-a" in state.attachedPods)

        // A further, unrelated snapshot event must not duplicate the notice.
        gateway.pushSnapshot(listOf(testPod("example-pod-sentinel", testContainer("app"))))
        state = awaitState(task) { it.attachedPods.contains("example-pod-sentinel") }
        assertEquals(1, state.lines.count { it.notice && it.text == endedNotice("example-pod-a") })
    }

    @Test
    fun `a pod recreated under the same name attaches again`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-0", testContainer("app", restartCount = 3))))
        awaitState(task) { it.attachedPods.contains("example-pod-0") }
        awaitStream(gateway, "example-pod-0", "app")

        gateway.completeStream("example-pod-0", "app")
        awaitState(task) { !it.attachedPods.contains("example-pod-0") }

        // Disappears — this clears the recorded restart-count baseline. A
        // sentinel pod's attach proves this snapshot was actually applied.
        gateway.pushSnapshot(listOf(testPod("example-pod-sentinel", testContainer("app"))))
        awaitState(task) { it.attachedPods.contains("example-pod-sentinel") }

        // Recreated at restartCount 0 — below the old baseline of 3, but the
        // baseline is gone, so it must attach again from scratch.
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-sentinel", testContainer("app")),
                testPod("example-pod-0", testContainer("app", restartCount = 0)),
            ),
        )
        val state = awaitState(task) { it.lines.count { l -> l.notice && l.text == startedNotice("example-pod-0") } == 2 }
        assertEquals(2, state.lines.count { it.notice && it.text == startedNotice("example-pod-0") })
    }
}
