package com.kubekubedashdash.services

import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.services.logcapture.CaptureContainerKind
import com.kubekubedashdash.services.logcapture.CaptureContainerSpec
import com.kubekubedashdash.services.logcapture.CaptureOptions
import com.kubekubedashdash.services.logcapture.CapturePhase
import com.kubekubedashdash.services.logcapture.CapturePodSpec
import com.kubekubedashdash.services.logcapture.CaptureState
import com.kubekubedashdash.services.logcapture.FakeNamespaceLogCaptureGateway
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureEngine
import com.kubekubedashdash.services.logcapture.NamespaceLogCaptureTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the capture-tab variant of [LogStreamRegistry] — session scoping,
 * the running-vs-finished replace logic in [LogStreamRegistry.openOrFocusCaptureTab],
 * and that [LogStreamRegistry.close] genuinely cancels the underlying capture
 * job. Every test calls [LogStreamRegistry.openOrFocusCaptureTab] directly —
 * never [LogStreamRegistry.openOrFocusCapture] — so no test constructs a
 * [com.kubekubedashdash.model.ClusterSession].
 */
class LogStreamRegistryCaptureTest {

    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File {
        val dir = createTempDirectory("logstream-registry-capture-test-").toFile()
        tempDirs.add(dir)
        return dir
    }

    @AfterTest
    fun cleanup() {
        LogStreamRegistry.clearAll()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun testContainer(name: String) = CaptureContainerSpec(
        name = name,
        kind = CaptureContainerKind.MAIN,
        restartCount = 0,
        started = true,
    )

    private fun testPod(name: String, vararg containers: CaptureContainerSpec) = CapturePodSpec(name = name, phase = "Running", containers = containers.toList())

    private fun testOptions() = CaptureOptions(
        sinceSeconds = null,
        includePrevious = false,
        destinationDir = newTempDir().absolutePath,
    )

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

    private fun activeTask(key: String): NamespaceLogCaptureTask = (LogStreamRegistry.tabs.value.getValue(key) as ActiveCaptureTask).task

    @Test
    fun `capture tab is removed by closeAllForSession for its own session`() = runBlocking {
        val gateway = FakeNamespaceLogCaptureGateway()
        val options = testOptions()

        val key = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        }
        awaitTerminal(activeTask(key))
        assertTrue(key in LogStreamRegistry.tabs.value)

        LogStreamRegistry.closeAllForSession(SessionId("session-a"))

        assertFalse(key in LogStreamRegistry.tabs.value)
    }

    @Test
    fun `capture tab for another session survives closeAllForSession`() = runBlocking {
        val gatewayA = FakeNamespaceLogCaptureGateway()
        val gatewayB = FakeNamespaceLogCaptureGateway()
        val optionsA = testOptions()
        val optionsB = testOptions()

        val keyA = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            NamespaceLogCaptureEngine.start(this, gatewayA, "example-namespace", optionsA)
        }
        val keyB = LogStreamRegistry.openOrFocusCaptureTab("session-b", "example-namespace") {
            NamespaceLogCaptureEngine.start(this, gatewayB, "example-namespace", optionsB)
        }
        awaitTerminal(activeTask(keyA))
        awaitTerminal(activeTask(keyB))

        LogStreamRegistry.closeAllForSession(SessionId("session-a"))

        val remaining = LogStreamRegistry.tabs.value
        assertFalse(keyA in remaining)
        assertTrue(keyB in remaining)
    }

    @Test
    fun `app log tab has a null session id`() {
        LogStreamRegistry.openOrFocusAppLog()

        val tab = LogStreamRegistry.tabs.value.getValue(ActiveAppLog.APP_LOG_KEY)
        assertNull(tab.sessionId)
    }

    @Test
    fun `openOrFocusCapture focuses a running task without invoking the factory`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeNamespaceLogCaptureGateway(
            podsResult = Result.success(listOf(testPod("pod-a", testContainer("app")))),
        )
        gateway.programGated("pod-a", "app", false, gate, "line\n")
        val options = testOptions()

        val key = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        }

        withTimeout(5_000) {
            while (gateway.currentHighWaterMark() < 1) yield()
        }
        val originalTask = activeTask(key)
        assertTrue(originalTask.isRunning)

        var factoryInvoked = false
        val key2 = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            factoryInvoked = true
            error("factory must not be invoked while the existing capture task is still running")
        }

        assertEquals(key, key2)
        assertFalse(factoryInvoked)
        assertTrue(activeTask(key2).isRunning)

        // Let the still-running capture finish so this runBlocking scope can return.
        gate.complete(Unit)
        awaitTerminal(originalTask)
        Unit
    }

    @Test
    fun `openOrFocusCapture replaces a finished task`() = runBlocking {
        val gateway1 = FakeNamespaceLogCaptureGateway()
        val options1 = testOptions()
        val key1 = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            NamespaceLogCaptureEngine.start(this, gateway1, "example-namespace", options1)
        }
        val firstTask = activeTask(key1)
        awaitTerminal(firstTask)
        assertFalse(firstTask.isRunning)

        val gateway2 = FakeNamespaceLogCaptureGateway()
        val options2 = testOptions()
        var factoryInvoked = false
        val key2 = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            factoryInvoked = true
            NamespaceLogCaptureEngine.start(this, gateway2, "example-namespace", options2)
        }

        assertEquals(key1, key2)
        assertTrue(factoryInvoked)
        val secondTask = activeTask(key2)
        assertNotSame(firstTask, secondTask)
        awaitTerminal(secondTask)
        Unit
    }

    @Test
    fun `close cancels the running capture job and keeps partial output`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeNamespaceLogCaptureGateway(
            podsResult = Result.success(listOf(testPod("pod-a", testContainer("app")))),
        )
        gateway.programGated("pod-a", "app", false, gate, "partial-line\n")
        val options = testOptions()

        val key = LogStreamRegistry.openOrFocusCaptureTab("session-a", "example-namespace") {
            NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        }
        val task = activeTask(key)

        withTimeout(5_000) {
            while (gateway.currentHighWaterMark() < 1) yield()
        }

        LogStreamRegistry.close(key)
        assertFalse(key in LogStreamRegistry.tabs.value)

        gate.complete(Unit)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Cancelled)
        assertTrue(File(state.outputDir, "capture-summary.txt").exists())
        assertTrue(File(state.outputDir, "pod-a/app.log").exists())
    }
}
