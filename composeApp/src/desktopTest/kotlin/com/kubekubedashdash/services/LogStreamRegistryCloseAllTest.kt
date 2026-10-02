package com.kubekubedashdash.services

import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CaptureOptions
import com.kubekubedashdash.services.logcapture.CapturePhase
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import com.kubekubedashdash.services.logcapture.CaptureState
import com.kubekubedashdash.services.logcapture.FakeNamespaceLogCaptureGateway
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureEngine
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureTask
import com.kubekubedashdash.services.logtail.FakeNamespaceTailGateway
import com.kubekubedashdash.services.logtail.NamespaceTailEngine
import com.kubekubedashdash.services.logtail.NamespaceTailTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [LogStreamRegistry.closeTabs] / [LogStreamRegistry.restoreTabs] — the drawer's
 * "Close all" and its Undo — plus the retained tail factory they rely on. Every
 * test goes through the unit-testable seams ([LogStreamRegistry.openOrFocusStream],
 * [LogStreamRegistry.openOrFocusCaptureTab], [LogStreamRegistry.openOrFocusTailTab])
 * so none constructs a [com.kubekubedashdash.model.ClusterSession].
 */
class LogStreamRegistryCloseAllTest {

    /** Tail tasks run on this scope, never on `runBlocking`'s — see LogStreamRegistryTailTest. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        LogStreamRegistry.clearAll()
        scope.cancel()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    // A stream that never emits and records its own cancellation. [started] completes once the
    // collector is actually running: a collector cancelled before it starts never reaches the
    // `finally`, so a test that asserts on [cancelled] waits for [started] first.
    private fun parked(
        cancelled: CompletableDeferred<Unit>,
        started: CompletableDeferred<Unit> = CompletableDeferred(),
    ): Flow<String> = flow {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancelled.complete(Unit)
        }
    }

    private fun idFor(sessionId: String, podName: String) = LogStreamId(sessionId, podName, "ns-a", null)

    private fun tabOf(key: String): DrawerLogTab = LogStreamRegistry.tabs.value.getValue(key)

    private fun testContainer(name: String) = CaptureContainerSpec(
        name = name,
        kind = CaptureContainerKind.MAIN,
        restartCount = 0,
        started = true,
    )

    private fun testPod(name: String, vararg containers: CaptureContainerSpec) = CapturePodSpec(name = name, phase = "Running", containers = containers.toList())

    private fun testOptions(): CaptureOptions {
        val dir = createTempDirectory("logstream-registry-closeall-test-").toFile()
        tempDirs.add(dir)
        return CaptureOptions(
            sinceSeconds = null,
            includePrevious = false,
            destinationDir = dir.absolutePath,
        )
    }

    private suspend fun awaitTerminal(task: NamespaceLogCaptureTask, timeoutMs: Long = 5_000): CaptureState {
        val terminal =
            withTimeout(timeoutMs) {
                task.state.first {
                    it.phase is CapturePhase.Completed || it.phase is CapturePhase.Cancelled || it.phase is CapturePhase.Failed
                }
            }
        withTimeout(timeoutMs) { task.job.join() }
        return terminal
    }

    @Test
    fun `closeTabs closes the listed tabs and cancels their streams`() = runBlocking {
        val cancelledA = CompletableDeferred<Unit>()
        val cancelledB = CompletableDeferred<Unit>()
        val cancelledC = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val a = idFor("session-1", "web-0")
        val b = idFor("session-1", "web-1")
        val c = idFor("session-1", "web-2")
        LogStreamRegistry.openOrFocusStream(a, "web-0") { _, _ -> parked(cancelledA, startedA) }
        LogStreamRegistry.openOrFocusStream(b, "web-1") { _, _ -> parked(cancelledB, startedB) }
        LogStreamRegistry.openOrFocusStream(c, "web-2") { _, _ -> parked(cancelledC) }
        withTimeout(5_000) {
            startedA.await()
            startedB.await()
        }

        val closed = LogStreamRegistry.closeTabs(listOf(a.key, b.key, "not-open"))

        assertEquals(2, closed.size)
        assertEquals(setOf(c.key), LogStreamRegistry.tabs.value.keys)
        withTimeout(5_000) {
            cancelledA.await()
            cancelledB.await()
        }
        assertFalse(cancelledC.isCompleted)
    }

    @Test
    fun `restoreTabs restarts a pod stream in its old place with its options`() = runBlocking {
        val calls = AtomicInteger(0)
        var lastCall: Pair<String?, LogStreamOptions>? = null
        val a = idFor("session-1", "web-0")
        LogStreamRegistry.openOrFocusStream(a, "web-0") { container, options ->
            calls.incrementAndGet()
            lastCall = container to options
            parked(CompletableDeferred())
        }
        LogStreamRegistry.setOptions(a.key, LogStreamOptions(timestamps = true))
        val before = tabOf(a.key) as ActiveLogStream
        LogStreamRegistry.focus(a.key)
        val closed = LogStreamRegistry.closeTabs(listOf(a.key))
        val callsBeforeRestore = calls.get()

        val restored = LogStreamRegistry.restoreTabs(closed, setOf("session-1"))

        assertEquals(setOf(a.key), restored)
        val after = tabOf(a.key) as ActiveLogStream
        assertEquals(before.displayLabel, after.displayLabel)
        assertEquals(before.openedAt, after.openedAt)
        assertEquals(LogStreamOptions(timestamps = true), after.options)
        assertNotSame(before.lines, after.lines)
        assertEquals(null to LogStreamOptions(timestamps = true), lastCall)
        assertEquals(callsBeforeRestore + 1, calls.get())
        assertEquals(a.key, LogStreamRegistry.focusedKey.value)
    }

    @Test
    fun `restoreTabs skips a reopened tab and a session that left the window`() = runBlocking {
        val calls = AtomicInteger(0)
        val a = idFor("session-1", "web-0")
        val b = idFor("session-2", "web-0")
        LogStreamRegistry.openOrFocusStream(a, "web-0") { _, _ ->
            calls.incrementAndGet()
            parked(CompletableDeferred())
        }
        LogStreamRegistry.openOrFocusStream(b, "web-0") { _, _ ->
            calls.incrementAndGet()
            parked(CompletableDeferred())
        }
        val closed = LogStreamRegistry.closeTabs(listOf(a.key, b.key))
        LogStreamRegistry.openOrFocusStream(a, "web-0") { _, _ ->
            calls.incrementAndGet()
            parked(CompletableDeferred())
        }
        val callsBeforeRestore = calls.get()

        val restored = LogStreamRegistry.restoreTabs(closed, setOf("session-1"))

        assertEquals(emptySet(), restored)
        assertFalse(b.key in LogStreamRegistry.tabs.value)
        assertTrue(a.key in LogStreamRegistry.tabs.value)
        assertEquals(callsBeforeRestore, calls.get())
    }

    @Test
    fun `the application-log and port-forwards tabs come back as the same objects`() {
        LogStreamRegistry.openOrFocusAppLog()
        LogStreamRegistry.openOrFocusPortForwards()
        val appLog = tabOf(ActiveAppLog.APP_LOG_KEY)
        val forwards = tabOf(ActivePortForwards.PORT_FORWARDS_KEY)

        val closed = LogStreamRegistry.closeTabs(listOf(ActiveAppLog.APP_LOG_KEY, ActivePortForwards.PORT_FORWARDS_KEY))
        assertTrue(LogStreamRegistry.tabs.value.isEmpty())
        val restored = LogStreamRegistry.restoreTabs(closed, emptySet())

        assertEquals(setOf(ActiveAppLog.APP_LOG_KEY, ActivePortForwards.PORT_FORWARDS_KEY), restored)
        assertSame(appLog, tabOf(ActiveAppLog.APP_LOG_KEY))
        assertSame(forwards, tabOf(ActivePortForwards.PORT_FORWARDS_KEY))
    }

    @Test
    fun `a running capture is cancelled by closeTabs and comes back as the same tab`() = runBlocking {
        // The gate stays shut until after closeTabs: the fake's blocked read is not interruptible by
        // coroutine cancellation (see NamespaceLogCaptureEngine.copyStream), so the capture reaches
        // its terminal phase only once the read returns. The same shape as
        // LogStreamRegistryCaptureTest's "close cancels the running capture job" test.
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeNamespaceLogCaptureGateway(
            podsResult = Result.success(listOf(testPod("web-0", testContainer("app")))),
        )
        gateway.programGated("web-0", "app", false, gate, "line\n")
        val options = testOptions()
        val key = LogStreamRegistry.openOrFocusCaptureTab("session-1", "ns-a") {
            NamespaceLogCaptureEngine.start(this, gateway, "ns-a", options)
        }
        withTimeout(5_000) {
            while (gateway.currentHighWaterMark() < 1) yield()
        }
        val tab = tabOf(key) as ActiveCaptureTask
        assertTrue(tab.task.isRunning)

        val closed = LogStreamRegistry.closeTabs(listOf(key))
        assertFalse(key in LogStreamRegistry.tabs.value)
        gate.complete(Unit)
        val terminal = awaitTerminal(tab.task)
        assertTrue(terminal.phase is CapturePhase.Cancelled)

        val restored = LogStreamRegistry.restoreTabs(closed, setOf("session-1"))

        assertEquals(setOf(key), restored)
        assertSame(tab, tabOf(key))
        assertFalse(tab.task.isRunning)
    }

    @Test
    fun `a namespace tail restarts from its retained factory`() = runBlocking {
        var calls = 0
        val key = LogStreamRegistry.openOrFocusTailTab("session-1", "ns-a") {
            calls++
            NamespaceTailEngine.start(scope, FakeNamespaceTailGateway(), "ns-a")
        }
        val oldTask = (tabOf(key) as ActiveNamespaceTail).task
        assertEquals(setOf(key), LogStreamRegistry.retainedTailFactoryKeys)

        val closed = LogStreamRegistry.closeTabs(listOf(key))
        withTimeout(5_000) { oldTask.job.join() }
        assertEquals(emptySet(), LogStreamRegistry.retainedTailFactoryKeys)

        val restored = LogStreamRegistry.restoreTabs(closed, setOf("session-1"))

        assertEquals(setOf(key), restored)
        val newTask: NamespaceTailTask = (tabOf(key) as ActiveNamespaceTail).task
        assertNotSame(oldTask, newTask)
        assertTrue(newTask.isRunning)
        assertEquals(2, calls)
        assertEquals(setOf(key), LogStreamRegistry.retainedTailFactoryKeys)
    }

    @Test
    fun `a tail is not restored while its session runs another tail`() = runBlocking {
        val keyA = LogStreamRegistry.openOrFocusTailTab("session-1", "ns-a") {
            NamespaceTailEngine.start(scope, FakeNamespaceTailGateway(), "ns-a")
        }
        val closed = LogStreamRegistry.closeTabs(listOf(keyA))
        val keyB = LogStreamRegistry.openOrFocusTailTab("session-1", "ns-b") {
            NamespaceTailEngine.start(scope, FakeNamespaceTailGateway(), "ns-b")
        }

        val restored = LogStreamRegistry.restoreTabs(closed, setOf("session-1"))

        assertEquals(emptySet(), restored)
        assertFalse(keyA in LogStreamRegistry.tabs.value)
        assertTrue(keyB in LogStreamRegistry.tabs.value)
    }

    @Test
    fun `close and clearAll drop the retained tail factory`() = runBlocking {
        val key = LogStreamRegistry.openOrFocusTailTab("session-1", "ns-a") {
            NamespaceTailEngine.start(scope, FakeNamespaceTailGateway(), "ns-a")
        }
        assertEquals(setOf(key), LogStreamRegistry.retainedTailFactoryKeys)
        LogStreamRegistry.close(key)
        assertEquals(emptySet(), LogStreamRegistry.retainedTailFactoryKeys)

        LogStreamRegistry.openOrFocusTailTab("session-1", "ns-a") {
            NamespaceTailEngine.start(scope, FakeNamespaceTailGateway(), "ns-a")
        }
        assertEquals(setOf(key), LogStreamRegistry.retainedTailFactoryKeys)
        LogStreamRegistry.clearAll()
        assertEquals(emptySet(), LogStreamRegistry.retainedTailFactoryKeys)
    }

    @Test
    fun `restoreTabs does not move focus when the focused tab was not closed`() = runBlocking {
        val a = idFor("session-1", "web-0")
        val b = idFor("session-1", "web-1")
        LogStreamRegistry.openOrFocusStream(a, "web-0") { _, _ -> parked(CompletableDeferred()) }
        LogStreamRegistry.openOrFocusStream(b, "web-1") { _, _ -> parked(CompletableDeferred()) }
        LogStreamRegistry.focus(b.key)

        val closed = LogStreamRegistry.closeTabs(listOf(a.key))
        LogStreamRegistry.focus(b.key)
        val restored = LogStreamRegistry.restoreTabs(closed, setOf("session-1"))

        assertEquals(setOf(a.key), restored)
        assertEquals(b.key, LogStreamRegistry.focusedKey.value)
    }
}
