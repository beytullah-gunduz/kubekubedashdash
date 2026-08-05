package com.kubekubedashdash.services.logcapture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [NamespaceLogCaptureEngine] against [FakeNamespaceLogCaptureGateway]
 * with no cluster and no real preferences — only files under a per-test
 * temporary directory, cleaned up afterwards.
 */
class NamespaceLogCaptureEngineTest {

    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File {
        val dir = createTempDirectory("logcapture-test-").toFile()
        tempDirs.add(dir)
        return dir
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun testContainer(
        name: String,
        started: Boolean = true,
        restartCount: Int = 0,
        kind: CaptureContainerKind = CaptureContainerKind.MAIN,
    ) = CaptureContainerSpec(name = name, kind = kind, restartCount = restartCount, started = started)

    private fun testPod(
        name: String,
        vararg containers: CaptureContainerSpec,
        phase: String = "Running",
    ) = CapturePodSpec(name = name, phase = phase, containers = containers.toList())

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

    /** A sink whose writes fail with [message] once more than [failAfterBytes] total bytes have been written. */
    private fun diskFullSinkFactory(failAfterBytes: Long, message: String = "No space left on device"): (File) -> OutputStream {
        var totalWritten = 0L
        return { file ->
            file.parentFile?.mkdirs()
            val real = java.io.BufferedOutputStream(java.io.FileOutputStream(file))
            object : OutputStream() {
                override fun write(b: Int) {
                    if (totalWritten >= failAfterBytes) throw IOException(message)
                    totalWritten++
                    real.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    val allowed = minOf(len.toLong(), failAfterBytes - totalWritten).coerceAtLeast(0).toInt()
                    if (allowed > 0) {
                        real.write(b, off, allowed)
                        totalWritten += allowed
                    }
                    if (allowed < len) throw IOException(message)
                }

                override fun flush() = real.flush()

                override fun close() = real.close()
            }
        }
    }

    @Test
    fun `captures all containers into the expected directory layout`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(
                podsResult = Result.success(listOf(testPod("pod-a", testContainer("app"), testContainer("sidecar")))),
            )
        gateway.programContent("pod-a", "app", false, "line1\nline2\n")
        gateway.programContent("pod-a", "sidecar", false, "hello\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Completed)
        val outputDir = File(state.outputDir)
        assertEquals("line1\nline2\n", File(outputDir, "pod-a/app.log").readText())
        assertEquals("hello\n", File(outputDir, "pod-a/sidecar.log").readText())
        assertTrue(File(outputDir, "capture-summary.txt").exists())

        val outcomes = state.pods.single { it.podName == "pod-a" }.outcomes
        val appOutcome = outcomes.getValue("app") as ContainerOutcome.Captured
        assertEquals(2, appOutcome.lines)
        val sidecarOutcome = outcomes.getValue("sidecar") as ContainerOutcome.Captured
        assertEquals(1, sidecarOutcome.lines)
    }

    @Test
    fun `previous log captured only for containers with restarts`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(
                podsResult =
                Result.success(
                    listOf(testPod("pod-a", testContainer("app", restartCount = 2), testContainer("stable", restartCount = 0))),
                ),
            )
        gateway.programContent("pod-a", "app", false, "current\n")
        gateway.programContent("pod-a", "app", true, "previous\n")
        gateway.programContent("pod-a", "stable", false, "stable-current\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = true, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Completed)
        val outputDir = File(state.outputDir)
        assertEquals("previous\n", File(outputDir, "pod-a/app.previous.log").readText())
        assertFalse(File(outputDir, "pod-a/stable.previous.log").exists())
        assertTrue(gateway.receivedQueries.none { it.containerName == "stable" && it.previous })

        val outcomes = state.pods.single().outcomes
        assertTrue((outcomes.getValue("app") as ContainerOutcome.Captured).previousCaptured)
        assertFalse((outcomes.getValue("stable") as ContainerOutcome.Captured).previousCaptured)
    }

    @Test
    fun `never started container is skipped without an api call`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(
                podsResult = Result.success(listOf(testPod("pod-a", testContainer("app", started = false, restartCount = 0)))),
            )

        val options = CaptureOptions(sinceSeconds = null, includePrevious = true, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Completed)
        assertEquals(ContainerOutcome.Skipped, state.pods.single().outcomes.getValue("app"))
        assertTrue(gateway.receivedQueries.isEmpty())
        assertFalse(File(state.outputDir, "pod-a/app.log").exists())
    }

    @Test
    fun `per container failure does not abort the run`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(
                podsResult =
                Result.success(listOf(testPod("pod-a", testContainer("bad")), testPod("pod-b", testContainer("good")))),
            )
        gateway.programThrow("pod-a", "bad", false, IOException("boom"))
        gateway.programContent("pod-b", "good", false, "ok\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Completed)
        val badOutcome = state.pods.single { it.podName == "pod-a" }.outcomes.getValue("bad")
        assertEquals(ContainerOutcome.Failed("boom"), badOutcome)
        val goodOutcome = state.pods.single { it.podName == "pod-b" }.outcomes.getValue("good")
        assertTrue(goodOutcome is ContainerOutcome.Captured)
    }

    @Test
    fun `fast fails when the first five errors are identical`() = runBlocking {
        val pods = (0 until NamespaceLogCaptureEngine.FAST_FAIL_SAMPLE).map { i -> testPod("pod-$i", testContainer("app")) }
        val gateway = FakeNamespaceLogCaptureGateway(podsResult = Result.success(pods))
        pods.forEach { p -> gateway.programThrow(p.name, "app", false, IOException("rbac denied")) }

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        val phase = state.phase
        assertTrue(phase is CapturePhase.Failed)
        assertTrue(phase.message.contains("rbac denied"))
        assertTrue(phase.message.contains("the first ${NamespaceLogCaptureEngine.FAST_FAIL_SAMPLE} log reads"))

        // Negative case: a mix of five DIFFERENT error messages must not trigger fast-fail.
        val mixedPods = (0 until NamespaceLogCaptureEngine.FAST_FAIL_SAMPLE).map { i -> testPod("mixed-$i", testContainer("app")) }
        val mixedGateway = FakeNamespaceLogCaptureGateway(podsResult = Result.success(mixedPods))
        mixedPods.forEachIndexed { i, p -> mixedGateway.programThrow(p.name, "app", false, IOException("error-$i")) }

        val mixedOptions = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val mixedTask = NamespaceLogCaptureEngine.start(this, mixedGateway, "example-namespace", mixedOptions)
        val mixedState = awaitTerminal(mixedTask)

        assertTrue(mixedState.phase is CapturePhase.Completed)
        mixedPods.forEachIndexed { i, p ->
            val outcome = mixedState.pods.single { it.podName == p.name }.outcomes.getValue("app")
            assertEquals(ContainerOutcome.Failed("error-$i"), outcome)
        }
    }

    @Test
    fun `cancel keeps partial output and writes the summary`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val gateway =
            FakeNamespaceLogCaptureGateway(podsResult = Result.success(listOf(testPod("pod-a", testContainer("app")))))
        gateway.programGated("pod-a", "app", false, gate, "partial-line\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)

        withTimeout(5_000) {
            while (gateway.currentHighWaterMark() < 1) yield()
        }

        task.cancel()
        gate.complete(Unit)

        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Cancelled)
        assertTrue(File(state.outputDir, "capture-summary.txt").exists())
    }

    @Test
    fun `disk full aborts the run and keeps partials`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(podsResult = Result.success(listOf(testPod("pod-a", testContainer("app")))))
        gateway.programContent("pod-a", "app", false, "0123456789\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task =
            NamespaceLogCaptureEngine.start(
                scope = this,
                gateway = gateway,
                namespace = "example-namespace",
                options = options,
                sinkFactory = diskFullSinkFactory(failAfterBytes = 5),
            )
        val state = awaitTerminal(task)

        val phase = state.phase
        assertTrue(phase is CapturePhase.Failed)
        assertTrue(phase.message.contains("disk space", ignoreCase = true))
        assertTrue(phase.message.contains("0 of 1 pods"))
        assertTrue(File(state.outputDir, "capture-summary.txt").exists())

        val partial = File(state.outputDir, "pod-a/app.log")
        assertTrue(partial.exists())
        assertEquals(5, partial.length())
    }

    @Test
    fun `bounded concurrency never exceeds six pods`() = runBlocking {
        val podCount = 10
        val pods = (0 until podCount).map { i -> testPod("pod-$i", testContainer("app")) }
        val gateway = FakeNamespaceLogCaptureGateway(podsResult = Result.success(pods))
        val gate = CompletableDeferred<Unit>()
        pods.forEach { p -> gateway.programGated(p.name, "app", false, gate, "log\n") }

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)

        withTimeout(5_000) {
            while (gateway.currentHighWaterMark() < NamespaceLogCaptureEngine.MAX_CONCURRENT_PODS) yield()
        }
        assertEquals(NamespaceLogCaptureEngine.MAX_CONCURRENT_PODS, gateway.currentHighWaterMark())

        gate.complete(Unit)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Completed)
        assertEquals(NamespaceLogCaptureEngine.MAX_CONCURRENT_PODS, gateway.currentHighWaterMark())
    }

    @Test
    fun `summary file lists failures verbatim`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(podsResult = Result.success(listOf(testPod("pod-a", testContainer("bad")))))
        gateway.programThrow("pod-a", "bad", false, IOException("connection refused by apiserver"))

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        val summary = File(state.outputDir, "capture-summary.txt").readText()
        assertTrue(summary.contains("connection refused by apiserver"))
    }

    @Test
    fun `previous log is captured when the current log read fails`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(
                podsResult =
                Result.success(listOf(testPod("pod-a", testContainer("app", started = false, restartCount = 1)))),
            )
        gateway.programThrow("pod-a", "app", false, IOException("container not found"))
        gateway.programContent("pod-a", "app", true, "previous-content\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = true, destinationDir = newTempDir().absolutePath)
        val task = NamespaceLogCaptureEngine.start(this, gateway, "example-namespace", options)
        val state = awaitTerminal(task)

        assertTrue(state.phase is CapturePhase.Completed)
        val previousFile = File(state.outputDir, "pod-a/app.previous.log")
        assertTrue(previousFile.exists())
        assertEquals("previous-content\n", previousFile.readText())

        val outcome = state.pods.single().outcomes.getValue("app")
        assertTrue(outcome is ContainerOutcome.Captured)
        assertTrue(outcome.previousCaptured)

        val summary = File(state.outputDir, "capture-summary.txt").readText()
        assertTrue(summary.contains("container not found"))
    }

    @Test
    fun `internal abort is reported as Failed not Cancelled`() = runBlocking {
        val gateway =
            FakeNamespaceLogCaptureGateway(podsResult = Result.success(listOf(testPod("pod-a", testContainer("app")))))
        gateway.programContent("pod-a", "app", false, "0123456789\n")

        val options = CaptureOptions(sinceSeconds = null, includePrevious = false, destinationDir = newTempDir().absolutePath)
        val task =
            NamespaceLogCaptureEngine.start(
                scope = this,
                gateway = gateway,
                namespace = "example-namespace",
                options = options,
                sinkFactory = diskFullSinkFactory(failAfterBytes = 2),
            )
        val state = awaitTerminal(task)

        assertFalse(state.phase is CapturePhase.Cancelled)
        assertTrue(state.phase is CapturePhase.Failed)
    }
}
