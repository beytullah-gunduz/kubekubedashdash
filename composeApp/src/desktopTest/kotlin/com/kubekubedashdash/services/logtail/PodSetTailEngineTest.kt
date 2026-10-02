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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [NamespaceTailEngine] with a [TailTarget.Pods] target against
 * [FakeNamespaceTailGateway]. Every test either pushes without a namespace
 * (wildcard: the one namespace the target names) or passes namespaces
 * explicitly (multi-namespace targets), never both for the same namespace.
 * As in [NamespaceTailEngineTest], state is flushed on a timer, so assertions
 * await a predicate on [NamespaceTailTask.state].
 */
class PodSetTailEngineTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanup() {
        scope.cancel()
    }

    private val nsA = "example-namespace-a"
    private val nsB = "example-namespace-b"

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

    private fun podSet(namespace: String, vararg names: String) = TailTarget.Pods(names.map { TailPodRef(namespace, it) }.toSet())

    private fun target(vararg refs: TailPodRef) = TailTarget.Pods(refs.toSet())

    private fun startTask(
        gateway: FakeNamespaceTailGateway,
        target: TailTarget.Pods,
        flushIntervalMs: Long = 10,
        retryDelayMs: Long = 20,
    ) = NamespaceTailEngine.start(scope, gateway, target, flushIntervalMs, retryDelayMs)

    private suspend fun awaitState(
        task: NamespaceTailTask,
        timeoutMs: Long = 5_000,
        predicate: (TailState) -> Boolean,
    ): TailState = withTimeout(timeoutMs) { task.state.first(predicate) }

    private suspend fun awaitStream(
        gateway: FakeNamespaceTailGateway,
        podName: String,
        container: String?,
        namespace: String? = null,
        timeoutMs: Long = 5_000,
    ) {
        withTimeout(timeoutMs) {
            while (!gateway.hasStream(podName, container, namespace)) {
                yield()
            }
        }
    }

    private fun startedNotice(key: String) = "── $key started ──"

    private fun endedNotice(key: String) = "── $key stream ended ──"

    private fun TailState.noticeCount(text: String) = lines.count { it.notice && it.text == text }

    @Test
    fun `a non-selected pod in the same namespace never gets a stream`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, podSet(nsA, "web-0", "web-1"))
        gateway.pushSnapshot(
            listOf(
                testPod("web-0", testContainer("app")),
                testPod("web-1", testContainer("app")),
                testPod("other-0", testContainer("app")),
            ),
        )

        val state = awaitState(task) { it.attachedPods.containsAll(listOf("web-0", "web-1")) }
        awaitStream(gateway, "web-0", "app")
        awaitStream(gateway, "web-1", "app")

        assertEquals(listOf("web-0", "web-1"), state.attachedPods)
        assertEquals(2, state.streamCount)
        assertEquals(setOf("web-0", "web-1"), state.podStatus.keys)
        assertFalse(gateway.hasStream("other-0", "app"))
    }

    @Test
    fun `same pod name in two namespaces keeps two distinct keyed streams`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, target(TailPodRef(nsA, "web-0"), TailPodRef(nsB, "web-0")))
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app"))), nsA)
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app"))), nsB)

        awaitState(task) { it.attachedPods == listOf("$nsA/web-0", "$nsB/web-0") }
        // Each stream was opened with its own namespace: the fake keys streams by it.
        awaitStream(gateway, "web-0", "app", nsA)
        awaitStream(gateway, "web-0", "app", nsB)

        gateway.pushLine("web-0", "app", "from-a", nsA)
        gateway.pushLine("web-0", "app", "from-b", nsB)

        val state = awaitState(task) { s -> s.lines.any { it.text == "from-a" } && s.lines.any { it.text == "from-b" } }
        assertTrue(state.lines.any { it.podName == "$nsA/web-0" && it.text == "from-a" })
        assertTrue(state.lines.any { it.podName == "$nsB/web-0" && it.text == "from-b" })
        assertEquals(1, state.noticeCount(startedNotice("$nsA/web-0")))
        assertEquals(1, state.noticeCount(startedNotice("$nsB/web-0")))
        assertEquals(setOf("$nsA/web-0", "$nsB/web-0"), state.podStatus.keys)
    }

    @Test
    fun `a terminal selected pod is dumped once and never re-attached`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, podSet(nsA, "job-0", "sentinel-1", "sentinel-2"))
        gateway.pushSnapshot(listOf(testPod("job-0", testContainer("app"), phase = "Succeeded")))

        awaitState(task) { it.attachedPods.contains("job-0") }
        awaitStream(gateway, "job-0", "app")
        gateway.pushLine("job-0", "app", "final-line")
        gateway.completeStream("job-0", "app")

        val ended = awaitState(task) { it.noticeCount(endedNotice("job-0")) == 1 && it.podStatus["job-0"] == TailPodStatus.ENDED }
        assertTrue(ended.lines.any { it.podName == "job-0" && it.text == "final-line" })
        assertFalse("job-0" in ended.attachedPods)

        // Two more identical snapshots must not re-attach it. A sentinel pod's
        // attach is the positive signal that each snapshot was applied.
        gateway.pushSnapshot(
            listOf(
                testPod("job-0", testContainer("app"), phase = "Succeeded"),
                testPod("sentinel-1", testContainer("app")),
            ),
        )
        var state = awaitState(task) { it.attachedPods.contains("sentinel-1") }
        assertFalse("job-0" in state.attachedPods)

        gateway.pushSnapshot(
            listOf(
                testPod("job-0", testContainer("app"), phase = "Succeeded"),
                testPod("sentinel-1", testContainer("app")),
                testPod("sentinel-2", testContainer("app")),
            ),
        )
        state = awaitState(task) { it.attachedPods.contains("sentinel-2") }
        assertFalse("job-0" in state.attachedPods)
        assertEquals(1, state.noticeCount(startedNotice("job-0")))
        assertEquals(1, state.noticeCount(endedNotice("job-0")))
        assertEquals(TailPodStatus.ENDED, state.podStatus["job-0"])
    }

    @Test
    fun `a pending selected pod waits and then attaches when a container starts`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, podSet(nsA, "web-0"))
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app", started = false), phase = "Pending")))

        val waiting = awaitState(task) { it.podStatus["web-0"] == TailPodStatus.WAITING }
        assertTrue(waiting.attachedPods.isEmpty())
        assertEquals(0, waiting.streamCount)

        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app"))))
        val streaming = awaitState(task) { it.podStatus["web-0"] == TailPodStatus.STREAMING }
        assertEquals(listOf("web-0"), streaming.attachedPods)
        awaitStream(gateway, "web-0", "app")
    }

    @Test
    fun `a crash-looping pod between runs is idle, not ended, and re-attaches on a higher restart count`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, podSet(nsA, "web-0", "sentinel"))
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app", restartCount = 0))))
        awaitState(task) { it.podStatus["web-0"] == TailPodStatus.STREAMING }
        awaitStream(gateway, "web-0", "app")

        // The run crashes: the stream hits EOF while the pod stays Running.
        gateway.completeStream("web-0", "app")
        awaitState(task) { it.podStatus["web-0"] == TailPodStatus.IDLE }

        // The container is now waiting out its back-off (started = false). A
        // sentinel's attach proves this snapshot was applied.
        gateway.pushSnapshot(
            listOf(
                testPod("web-0", testContainer("app", restartCount = 0, started = false)),
                testPod("sentinel", testContainer("app")),
            ),
        )
        val idle = awaitState(task) { it.attachedPods.contains("sentinel") }
        assertEquals(TailPodStatus.IDLE, idle.podStatus["web-0"])
        assertFalse("web-0" in idle.attachedPods)

        // The next run starts: a higher restart count re-qualifies the pod.
        gateway.pushSnapshot(
            listOf(
                testPod("web-0", testContainer("app", restartCount = 1)),
                testPod("sentinel", testContainer("app")),
            ),
        )
        val state = awaitState(task) { it.podStatus["web-0"] == TailPodStatus.STREAMING && it.noticeCount(startedNotice("web-0")) == 2 }
        assertTrue("web-0" in state.attachedPods)
    }

    @Test
    fun `a deleted selected pod is gone and a same-named pod that reappears is re-attached`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, podSet(nsA, "web-0"))
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app"))))
        awaitState(task) { it.podStatus["web-0"] == TailPodStatus.STREAMING }
        awaitStream(gateway, "web-0", "app")

        gateway.pushSnapshot(emptyList())
        val gone = awaitState(task) { it.podStatus["web-0"] == TailPodStatus.GONE && it.noticeCount(endedNotice("web-0")) == 1 }
        assertTrue(gone.attachedPods.isEmpty())
        assertEquals(0, gone.streamCount)

        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app"))))
        val back = awaitState(task) { it.podStatus["web-0"] == TailPodStatus.STREAMING && it.noticeCount(startedNotice("web-0")) == 2 }
        assertEquals(listOf("web-0"), back.attachedPods)
    }

    @Test
    fun `forty-one selected pods attach forty, mark one capped and use the pod-set cap text`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val names = (0..NamespaceTailEngine.MAX_STREAMS).map { i -> "example-pod-%02d".format(i) }
        val task = startTask(gateway, podSet(nsA, *names.toTypedArray()))
        gateway.pushSnapshot(names.map { testPod(it, testContainer("app")) })

        val state = awaitState(task) { it.capNotice != null }
        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.attachedPods.size)
        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.streamCount)
        assertEquals("Tailing 40 of 41 selected pods (limit: 40 container streams).", state.capNotice)
        assertEquals(1, state.podStatus.values.count { it == TailPodStatus.CAPPED })
        assertEquals(NamespaceTailEngine.MAX_STREAMS, state.podStatus.values.count { it == TailPodStatus.STREAMING })
        assertEquals(TailPodStatus.CAPPED, state.podStatus["example-pod-40"])
    }

    @Test
    fun `one namespace failing discovery sets the error but keeps running streams alive`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, target(TailPodRef(nsA, "web-0"), TailPodRef(nsB, "web-1")))
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app"))), nsA)
        gateway.pushSnapshot(listOf(testPod("web-1", testContainer("app"))), nsB)
        awaitState(task) { it.attachedPods.size == 2 }
        awaitStream(gateway, "web-0", "app", nsA)
        awaitStream(gateway, "web-1", "app", nsB)

        gateway.failDiscovery("rbac denied", nsB)
        awaitState(task) { it.error == "rbac denied" }

        // The other namespace's already-running stream keeps delivering.
        gateway.pushLine("web-0", "app", "still-flowing", nsA)
        val during = awaitState(task) { s -> s.lines.any { it.text == "still-flowing" } }
        assertTrue("$nsA/web-0" in during.attachedPods)
        assertTrue("$nsB/web-1" in during.attachedPods)

        // Recovery: the failing namespace emits again and the error clears.
        gateway.pushSnapshot(listOf(testPod("web-1", testContainer("app"))), nsB)
        val recovered = awaitState(task) { it.error == null }
        assertNull(recovered.error)
        assertEquals(listOf("$nsA/web-0", "$nsB/web-1"), recovered.attachedPods)
        assertEquals(1, recovered.noticeCount(startedNotice("$nsA/web-0")))
    }

    @Test
    fun `a single-namespace pod set uses bare pod names as keys`() = runBlocking {
        val gateway = FakeNamespaceTailGateway()
        val task = startTask(gateway, podSet(nsA, "web-0", "web-1"))
        gateway.pushSnapshot(listOf(testPod("web-0", testContainer("app")), testPod("web-1", testContainer("app"))))

        awaitState(task) { it.attachedPods == listOf("web-0", "web-1") }
        awaitStream(gateway, "web-0", "app")
        gateway.pushLine("web-0", "app", "hello")

        val state = awaitState(task) { s -> s.lines.any { it.text == "hello" } }
        assertTrue(state.lines.any { it.podName == "web-0" && it.text == "hello" })
        assertEquals(setOf("web-0", "web-1"), state.podStatus.keys)
        assertTrue(state.attachedPods.none { '/' in it })
        assertEquals(1, state.noticeCount(startedNotice("web-0")))
    }

    @Test
    fun `target keys, file stems and the non-empty rule`() {
        val oneNs = target(TailPodRef(nsA, "web-0"), TailPodRef(nsA, "web-1"))
        val twoNs = target(TailPodRef(nsA, "web-0"), TailPodRef(nsB, "web-0"), TailPodRef(nsB, "web-1"))
        val namespace = TailTarget.Namespace(nsA)

        assertEquals("web-0", oneNs.keyFor(nsA, "web-0"))
        assertEquals("$nsB/web-0", twoNs.keyFor(nsB, "web-0"))
        assertEquals("web-0", namespace.keyFor(nsA, "web-0"))
        assertEquals(listOf(nsA, nsB), twoNs.namespaces)
        assertEquals("tail-$nsA", namespace.fileStem())
        assertEquals("tail-2-pods", oneNs.fileStem())
        assertEquals("tail-3-pods", twoNs.fileStem())
        assertFailsWith<IllegalArgumentException> { TailTarget.Pods(emptySet()) }
    }
}
