package com.kubekubedashdash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises CliRunner's coroutine-cancellation contract against real `/bin/sh`
 * children. Every test body starts with a POSIX guard because CI also runs
 * desktopTest on windows-latest, which has no `sh`. Timing bounds are deliberately
 * generous (10 s) to avoid CI flakes; the behaviors being pinned differ by an
 * order of magnitude (2 s grace vs 30 s child lifetime), so the bounds still
 * discriminate.
 */
class CliRunnerTest {

    private fun posix(): Boolean = !System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    private fun childAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun `returns stdout for a successful command`() = runBlocking {
        if (!posix()) return@runBlocking
        val result = CliRunner.run(listOf("sh", "-c", "echo hello"), timeoutSeconds = 10)
        assertEquals("hello", result.getOrNull()?.trim())
    }

    @Test
    fun `non-zero exit maps to CliInvocationFailure with the exit code`() = runBlocking {
        if (!posix()) return@runBlocking
        val failure = CliRunner.run(listOf("sh", "-c", "echo nope >&2; exit 3"), timeoutSeconds = 10)
            .exceptionOrNull()
        assertTrue(failure is CliInvocationFailure, "expected CliInvocationFailure, got $failure")
        assertEquals(3, failure.exitCode)
    }

    @Test
    fun `timeout produces a Timed out failure`() = runBlocking {
        if (!posix()) return@runBlocking
        val failure = CliRunner.run(listOf("sh", "-c", "sleep 30"), timeoutSeconds = 1).exceptionOrNull()
        assertTrue(failure is CliInvocationFailure, "expected CliInvocationFailure, got $failure")
        assertTrue(failure.message.orEmpty().contains("Timed out"), "unexpected message: ${failure.message}")
    }

    @Test
    fun `cancellation terminates the child process promptly`() = runBlocking {
        if (!posix()) return@runBlocking
        val pidFile = File.createTempFile("clirunner-cancel", ".pid").apply {
            delete()
            deleteOnExit()
        }
        val job = launch(Dispatchers.IO) {
            CliRunner.run(
                listOf("sh", "-c", "echo $$ > '${pidFile.absolutePath}'; exec sleep 30"),
                timeoutSeconds = 60,
            )
        }
        // 30s, not 10s: the FIRST CliRunner.run in the test JVM triggers
        // ShellEnvironment's cold-start PATH resolution, which itself spawns
        // path_helper plus two shell probes with 3s and 5s waitFor caps
        // (ShellEnvironment.kt:180, :220) — up to ~8s before this child even spawns.
        // The discriminating assertion is `cancelMillis < 10_000`, measured after
        // the pid file exists; leave THAT bound alone.
        withTimeout(30_000) {
            while (!pidFile.exists() || pidFile.readText().isBlank()) delay(50)
        }
        val pid = pidFile.readText().trim().toLong()
        val cancelMillis = measureTimeMillis { job.cancelAndJoin() }
        assertTrue(cancelMillis < 10_000, "cancel took ${cancelMillis}ms — the process wait was not interruptible")
        // 30s, not 10s: the FIRST CliRunner.run in the test JVM triggers
        // ShellEnvironment's cold-start PATH resolution, which itself spawns
        // path_helper plus two shell probes with 3s and 5s waitFor caps
        // (ShellEnvironment.kt:180, :220) — up to ~8s before this child even spawns.
        // The discriminating assertion is `cancelMillis < 10_000`, measured after
        // the pid file exists; leave THAT bound alone.
        withTimeout(30_000) {
            while (childAlive(pid)) delay(100)
        }
    }

    @Test
    fun `NonCancellable shields a call inside an already-cancelled coroutine`() = runBlocking {
        if (!posix()) return@runBlocking
        var result: Result<String>? = null
        val job = launch(Dispatchers.IO) {
            // Mirrors the import loop's contract (plan D2): the surrounding job is
            // cancelled, but the NonCancellable step must still run to completion.
            cancel()
            result = withContext(NonCancellable) {
                CliRunner.run(listOf("sh", "-c", "echo shielded"), timeoutSeconds = 10)
            }
        }
        job.join()
        assertEquals("shielded", result?.getOrNull()?.trim())
    }
}
