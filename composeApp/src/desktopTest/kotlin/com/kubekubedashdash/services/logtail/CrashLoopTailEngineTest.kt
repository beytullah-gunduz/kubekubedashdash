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
import kotlin.test.assertTrue

/**
 * Per-container re-attach (a restarted or late-starting container of an already
 * attached pod is streamed) and previous-run dumps (a pod-set tail reads the
 * last terminated run of a crash-looping container once, one dump at a time),
 * against [FakeNamespaceTailGateway]. State is flushed on a timer, so every
 * assertion awaits a predicate on [NamespaceTailTask.state].
 *
 * Negative dump assertions use the FIFO sentinel rule: the target includes
 * `example-pod-s`, the snapshot lists the pod under test first and
 * `example-pod-s` last with its `app` waiting on a previous run. Dump requests
 * are queued in snapshot order and served by one consumer, so once the
 * sentinel's dump has been requested every earlier request has been too.
 */
class CrashLoopTailEngineTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    private val ns = "example-namespace-a"

    private fun testContainer(
        name: String,
        kind: CaptureContainerKind = CaptureContainerKind.MAIN,
        restartCount: Int = 0,
        started: Boolean = true,
        runId: String? = null,
        previousRunId: String? = null,
        previousExit: String? = null,
    ) = CaptureContainerSpec(
        name = name,
        kind = kind,
        restartCount = restartCount,
        started = started,
        runId = runId,
        previousRunId = previousRunId,
        previousExit = previousExit,
    )

    private fun testPod(
        name: String,
        vararg containers: CaptureContainerSpec,
        phase: String = "Running",
    ) = CapturePodSpec(name = name, phase = phase, containers = containers.toList())

    /** A pod whose single `app` container is waiting between runs (crash loop). */
    private fun waitingPod(
        name: String,
        previousRunId: String,
        previousExit: String? = null,
        restartCount: Int = 1,
    ) = testPod(
        name,
        testContainer(
            "app",
            restartCount = restartCount,
            started = false,
            previousRunId = previousRunId,
            previousExit = previousExit,
        ),
    )

    private fun podSet(vararg names: String) = TailTarget.Pods(names.map { TailPodRef(ns, it) }.toSet())

    private fun startNamespaceTask(gateway: FakeNamespaceTailGateway) = NamespaceTailEngine.start(scope, gateway, ns, 10, 20)

    private fun startPodSetTask(gateway: FakeNamespaceTailGateway, target: TailTarget.Pods) = NamespaceTailEngine.start(scope, gateway, target, 10, 20)

    private suspend fun awaitState(
        task: NamespaceTailTask,
        timeoutMs: Long = 5_000,
        predicate: (TailState) -> Boolean,
    ): TailState = withTimeout(timeoutMs) { task.state.first(predicate) }

    private suspend fun awaitStream(gateway: FakeNamespaceTailGateway, podName: String, container: String?, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) {
            while (!gateway.hasStream(podName, container)) {
                yield()
            }
        }
    }

    /**
     * Waits for the nth `streamPodLogs` call. `hasStream` is sticky (a closed
     * channel stays registered), so a test that pushes onto a RE-attached
     * container must await its second call first: pushing earlier would land on
     * the old closed channel, a silent no-op.
     */
    private suspend fun awaitStreamCount(
        gateway: FakeNamespaceTailGateway,
        podName: String,
        container: String,
        count: Int,
        timeoutMs: Long = 5_000,
    ) {
        withTimeout(timeoutMs) {
            while (gateway.streamRequestCount(podName, container) < count) {
                yield()
            }
        }
    }

    private suspend fun awaitPreviousRequestCount(
        gateway: FakeNamespaceTailGateway,
        podName: String,
        container: String,
        count: Int,
        timeoutMs: Long = 5_000,
    ) {
        withTimeout(timeoutMs) {
            while (gateway.previousRequestCount(podName, container) < count) {
                yield()
            }
        }
    }

    private fun appStarted(pod: String) = "── $pod · app started ──"

    private fun TailState.hasNotice(text: String) = lines.any { it.notice && it.text == text }

    @Test
    fun `a restarted container beside a running sidecar is streamed again`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startNamespaceTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"), testContainer("sidecar"))))
        awaitState(task) { it.streamCount == 2 }
        awaitStreamCount(gateway, "example-pod-a", "app", 1)
        awaitStreamCount(gateway, "example-pod-a", "sidecar", 1)

        gateway.completeStream("example-pod-a", "app")
        val afterEnd = awaitState(task) { it.streamCount == 1 }
        assertTrue("example-pod-a" in afterEnd.attachedPods)

        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app", restartCount = 1), testContainer("sidecar"))))
        awaitStreamCount(gateway, "example-pod-a", "app", 2)
        awaitState(task) { it.streamCount == 2 }

        gateway.pushLine("example-pod-a", "app", "second-run")
        val state = awaitState(task) { s -> s.lines.any { !it.notice && it.text == "app second-run" } && s.hasNotice(appStarted("example-pod-a")) }
        assertEquals(2, gateway.streamRequestCount("example-pod-a", "app"))
        assertTrue(state.lines.any { !it.notice && it.podName == "example-pod-a" && it.text == "app second-run" })
    }

    @Test
    fun `a container that starts after its pod attached is streamed`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startNamespaceTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"), testContainer("sidecar", started = false))))
        awaitState(task) { it.streamCount == 1 }
        awaitStream(gateway, "example-pod-a", "app")
        assertFalse(gateway.hasStream("example-pod-a", "sidecar"))

        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app"), testContainer("sidecar"))))
        val state = awaitState(task) { it.streamCount == 2 && it.hasNotice("── example-pod-a · sidecar started ──") }
        awaitStreamCount(gateway, "example-pod-a", "sidecar", 1)
        assertEquals(2, state.streamCount)
        assertEquals(1, gateway.streamRequestCount("example-pod-a", "app"))
    }

    @Test
    fun `a container that just ended is not re-attached by snapshot churn`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startNamespaceTask(gateway)
        val pod = testPod("example-pod-a", testContainer("app"), testContainer("sidecar"))
        gateway.pushSnapshot(listOf(pod))
        awaitState(task) { it.streamCount == 2 }
        awaitStreamCount(gateway, "example-pod-a", "app", 1)

        gateway.completeStream("example-pod-a", "app")
        awaitState(task) { it.streamCount == 1 }

        // The sentinel's attach is the positive signal that this snapshot was applied.
        gateway.pushSnapshot(listOf(pod, testPod("example-pod-s", testContainer("app"))))
        val state = awaitState(task) { it.attachedPods.contains("example-pod-s") }
        assertEquals(1, gateway.streamRequestCount("example-pod-a", "app"))
        assertEquals(2, state.streamCount)
    }

    @Test
    fun `a restart seen while the old stream is still open is re-attached after it ends`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startNamespaceTask(gateway)
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app", restartCount = 0))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")

        // The restart shows up while the old stream is still open: the live container
        // is not re-attached, but the pod's highest observed count rises to 1. The
        // sentinel's attach is the positive signal that this snapshot was applied.
        gateway.pushSnapshot(
            listOf(
                testPod("example-pod-a", testContainer("app", restartCount = 1)),
                testPod("example-pod-s", testContainer("app")),
            ),
        )
        awaitState(task) { it.attachedPods.contains("example-pod-s") }
        assertEquals(1, gateway.streamRequestCount("example-pod-a", "app"))

        // The baseline is the count the pod was streamed at (0), not the count merely
        // observed (1), so the re-run after the stream ends re-attaches it.
        gateway.completeStream("example-pod-a", "app")
        awaitStreamCount(gateway, "example-pod-a", "app", 2)
        val state = awaitState(task) { s -> s.lines.count { it.notice && it.text == "── example-pod-a started ──" } == 2 }
        assertEquals(2, state.lines.count { it.notice && it.text == "── example-pod-a started ──" })
        assertTrue("example-pod-a" in state.attachedPods)
    }

    @Test
    fun `a late container attach respects the cap and yields to new pods`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startNamespaceTask(gateway)
        val fillers = (0..38).map { testPod("example-pod-%02d".format(it), testContainer("app")) }
        val xWithoutSidecar = testPod("example-pod-x", testContainer("app"), testContainer("sidecar", started = false))
        gateway.pushSnapshot(fillers + xWithoutSidecar)
        awaitState(task) { it.streamCount == 40 }

        val xWithSidecar = testPod("example-pod-x", testContainer("app"), testContainer("sidecar"))
        gateway.pushSnapshot(fillers + xWithSidecar + testPod("example-pod-z", testContainer("app")))
        val capped = awaitState(task) { it.capNotice == "Tailing 40 of 41 pods (stream limit) — Capture logs saves the full record." }
        assertEquals(40, capped.streamCount)
        assertFalse(gateway.hasStream("example-pod-x", "sidecar"))

        // A freed slot goes to the new pod first, not to the waiting sidecar.
        awaitStream(gateway, "example-pod-00", "app")
        gateway.completeStream("example-pod-00", "app")
        val afterNewPod = awaitState(task) { it.attachedPods.contains("example-pod-z") }
        assertEquals(40, afterNewPod.streamCount)
        assertFalse(gateway.hasStream("example-pod-x", "sidecar"))

        // The next freed slot takes the sidecar.
        awaitStream(gateway, "example-pod-01", "app")
        gateway.completeStream("example-pod-01", "app")
        awaitStream(gateway, "example-pod-x", "sidecar")
        val state = awaitState(task) { it.hasNotice("── example-pod-x · sidecar started ──") }
        assertEquals(40, state.streamCount)
    }

    @Test
    fun `a pod-set tail dumps the previous run of a waiting container once`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startPodSetTask(gateway, podSet("example-pod-a", "example-pod-s"))
        gateway.pushSnapshot(listOf(waitingPod("example-pod-a", "example-run-3", previousExit = "Error, exit 1", restartCount = 3)))
        awaitPreviousRequestCount(gateway, "example-pod-a", "app", 1)

        gateway.pushPreviousLine("example-pod-a", "app", "boom")
        val state = awaitState(task) { s ->
            s.hasNotice("── example-pod-a: previous run of app (Error, exit 1) ──") &&
                s.lines.any { !it.notice && it.podName == "example-pod-a" && it.text == "boom" } &&
                s.podStatus["example-pod-a"] == TailPodStatus.IDLE
        }
        assertEquals(TailPodStatus.IDLE, state.podStatus["example-pod-a"])

        // Consumer idle; the identical snapshot must not queue a second dump.
        gateway.completePreviousStream("example-pod-a", "app")
        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-a", "example-run-3", previousExit = "Error, exit 1", restartCount = 3),
                waitingPod("example-pod-s", "example-run-s"),
            ),
        )
        awaitPreviousRequestCount(gateway, "example-pod-s", "app", 1)
        assertEquals(1, gateway.previousRequestCount("example-pod-a", "app"))
    }

    @Test
    fun `a run already streamed live is not dumped again`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startPodSetTask(gateway, podSet("example-pod-a", "example-pod-s"))
        gateway.pushSnapshot(listOf(testPod("example-pod-a", testContainer("app", restartCount = 1, runId = "example-run-4"))))
        awaitState(task) { it.attachedPods.contains("example-pod-a") }
        awaitStream(gateway, "example-pod-a", "app")

        gateway.completeStream("example-pod-a", "app")
        awaitState(task) { !it.attachedPods.contains("example-pod-a") }

        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-a", "example-run-4", restartCount = 1),
                waitingPod("example-pod-s", "example-run-s"),
            ),
        )
        // The sentinel is IDLE only once this snapshot has been applied (it was GONE before).
        val state = awaitState(task) { it.podStatus["example-pod-s"] == TailPodStatus.IDLE }
        awaitPreviousRequestCount(gateway, "example-pod-s", "app", 1)
        assertEquals(0, gateway.previousRequestCount("example-pod-a", "app"))
        assertEquals(TailPodStatus.IDLE, state.podStatus["example-pod-a"])
    }

    @Test
    fun `a run missed while it was waiting is dumped when it appears later`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startPodSetTask(gateway, podSet("example-pod-a"))
        gateway.pushSnapshot(listOf(waitingPod("example-pod-a", "example-run-3")))
        awaitPreviousRequestCount(gateway, "example-pod-a", "app", 1)
        gateway.completePreviousStream("example-pod-a", "app")

        gateway.pushSnapshot(listOf(waitingPod("example-pod-a", "example-run-4", restartCount = 2)))
        awaitPreviousRequestCount(gateway, "example-pod-a", "app", 2)
        assertEquals(2, gateway.previousRequestCount("example-pod-a", "app"))
        assertTrue(task.isRunning)
    }

    @Test
    fun `previous-run dumps run one at a time`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startPodSetTask(gateway, podSet("example-pod-a", "example-pod-b"))
        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-a", "example-run-a"),
                waitingPod("example-pod-b", "example-run-b"),
            ),
        )
        awaitPreviousRequestCount(gateway, "example-pod-a", "app", 1)
        // Sound: the single consumer is blocked in a's open stream.
        assertEquals(0, gateway.previousRequestCount("example-pod-b", "app"))
        assertTrue(task.isRunning)

        gateway.completePreviousStream("example-pod-a", "app")
        awaitPreviousRequestCount(gateway, "example-pod-b", "app", 1)
        assertEquals(1, gateway.previousRequestCount("example-pod-a", "app"))
    }

    @Test
    fun `a namespace tail never dumps a previous run`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startNamespaceTask(gateway)
        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-a", "example-run-3", previousExit = "Error, exit 1"),
                testPod("example-pod-s", testContainer("app")),
            ),
        )
        // The sentinel attaches in the same snapshot pass that would have queued a dump.
        awaitState(task) { it.attachedPods.contains("example-pod-s") }
        assertEquals(0, gateway.previousRequestCount("example-pod-a", "app"))
        assertFalse(gateway.hasPreviousStream("example-pod-a", "app"))
    }

    @Test
    fun `a failed previous-run read leaves a notice and the other pods keep streaming`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startPodSetTask(gateway, podSet("example-pod-a", "example-pod-b"))
        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-a", "example-run-3"),
                testPod("example-pod-b", testContainer("app")),
            ),
        )
        awaitPreviousRequestCount(gateway, "example-pod-a", "app", 1)
        gateway.failPreviousStream("example-pod-a", "app", "boom")
        awaitState(task) { it.hasNotice("── example-pod-a: previous run of app unavailable ──") }

        awaitStream(gateway, "example-pod-b", "app")
        gateway.pushLine("example-pod-b", "app", "still-here")
        val state = awaitState(task) { s -> s.lines.any { !it.notice && it.podName == "example-pod-b" && it.text == "still-here" } }
        assertTrue(state.lines.any { !it.notice && it.podName == "example-pod-b" && it.text == "still-here" })
    }

    @Test
    fun `a queued dump for a run the pod has since moved past is skipped`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startPodSetTask(gateway, podSet("example-pod-a", "example-pod-b", "example-pod-s"))
        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-b", "example-run-b"),
                waitingPod("example-pod-a", "example-run-3", previousExit = "Error, exit 3"),
            ),
        )
        // The consumer is now blocked in b's open dump; a's run-3 request is queued behind it.
        awaitPreviousRequestCount(gateway, "example-pod-b", "app", 1)

        // The live sentinel attaches only once this snapshot has been applied, so the
        // pod's lastState has moved on to run 4 before b's dump is released.
        gateway.pushSnapshot(
            listOf(
                waitingPod("example-pod-b", "example-run-b"),
                waitingPod("example-pod-a", "example-run-4", previousExit = "Error, exit 4", restartCount = 2),
                testPod("example-pod-s", testContainer("app")),
            ),
        )
        awaitState(task) { it.attachedPods.contains("example-pod-s") }
        gateway.completePreviousStream("example-pod-b", "app")

        awaitPreviousRequestCount(gateway, "example-pod-a", "app", 1)
        val state = awaitState(task) { it.hasNotice("── example-pod-a: previous run of app (Error, exit 4) ──") }
        assertEquals(1, gateway.previousRequestCount("example-pod-a", "app"))
        assertTrue(state.lines.none { it.notice && it.text.contains("exit 3") })
    }
}
