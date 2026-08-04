package com.kubekubedashdash.util

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Shared subprocess runner for CLI-backed cluster discovery (`aws`, `gcloud`, …).
 * Resolves the binary through [ShellEnvironment], applies the augmented PATH plus any
 * extra environment overrides, and drains output on daemon threads so a chatty child
 * process can never deadlock `waitFor` on a full pipe buffer.
 */
object CliRunner {

    private val log = LoggerFactory.getLogger(CliRunner::class.java)

    /**
     * Runs [args] as a subprocess and returns its output.
     *
     * When [mergeStderr] is true (the default), stderr is redirected into stdout and
     * drained on a single thread — this is the historical `aws` CLI behavior and must
     * stay unchanged for every existing call site. When false, stdout and stderr are
     * drained on two separate daemon threads (merging into one stream and draining with
     * a single thread would reintroduce the pipe-buffer deadlock this class exists to
     * avoid) and only stdout is returned on success; gcloud writes diagnostics — some of
     * them unconditionally, not gated behind `--format` — to stderr, and merging that
     * text into stdout corrupts the JSON payload we need to parse.
     */
    fun run(
        args: List<String>,
        timeoutSeconds: Long,
        env: Map<String, String> = emptyMap(),
        mergeStderr: Boolean = true,
    ): Result<String> = try {
        val binary = args.firstOrNull()
        val resolvedBinary = binary?.let { ShellEnvironment.resolveCommand(it) }
        if (resolvedBinary == null) {
            Result.failure(CliInvocationFailure(-1, "", "${binary ?: "?"} CLI not found on PATH"))
        } else {
            val resolvedArgs = listOf(resolvedBinary) + args.drop(1)
            if (mergeStderr) runMerged(args, resolvedArgs, timeoutSeconds, env) else runSplit(args, resolvedArgs, timeoutSeconds, env)
        }
    } catch (e: Exception) {
        log.warn("{} CLI invocation failed: {}", args.firstOrNull() ?: "?", e.message)
        Result.failure(e)
    }

    private fun runMerged(
        originalArgs: List<String>,
        resolvedArgs: List<String>,
        timeoutSeconds: Long,
        env: Map<String, String>,
    ): Result<String> {
        // Merge stderr → stdout so a single drainer thread prevents pipe-buffer deadlock
        // (if the CLI writes >64 KB to stdout/stderr, waitFor blocks unless we drain first).
        val pb = ProcessBuilder(resolvedArgs).redirectErrorStream(true)
        ShellEnvironment.applyTo(pb)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val process = pb.start()
        process.outputStream.close()
        val output = StringBuilder()
        val drainer = Thread {
            process.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { line -> synchronized(output) { output.appendLine(line) } }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        return if (!finished) {
            process.destroyForcibly()
            drainer.join(1_000)
            Result.failure(CliInvocationFailure(-1, "", "Timed out after ${timeoutSeconds}s: ${safeCmd(originalArgs)}"))
        } else {
            drainer.join(2_000)
            val combined = synchronized(output) { output.toString() }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                Result.success(combined)
            } else {
                val msg = combined.trim().ifBlank { "exit $exitCode" }
                log.warn("{} CLI failed: cmd={} msg={}", originalArgs.firstOrNull() ?: "?", safeCmd(originalArgs), msg)
                Result.failure(CliInvocationFailure(exitCode, msg.take(500), msg))
            }
        }
    }

    private fun runSplit(
        originalArgs: List<String>,
        resolvedArgs: List<String>,
        timeoutSeconds: Long,
        env: Map<String, String>,
    ): Result<String> {
        val pb = ProcessBuilder(resolvedArgs)
        ShellEnvironment.applyTo(pb)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val process = pb.start()
        process.outputStream.close()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutDrainer = Thread {
            process.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { line -> synchronized(stdout) { stdout.appendLine(line) } }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val stderrDrainer = Thread {
            process.errorStream.bufferedReader().use { r ->
                r.lineSequence().forEach { line -> synchronized(stderr) { stderr.appendLine(line) } }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        return if (!finished) {
            process.destroyForcibly()
            stdoutDrainer.join(1_000)
            stderrDrainer.join(1_000)
            Result.failure(CliInvocationFailure(-1, "", "Timed out after ${timeoutSeconds}s: ${safeCmd(originalArgs)}"))
        } else {
            stdoutDrainer.join(2_000)
            stderrDrainer.join(2_000)
            val stdoutText = synchronized(stdout) { stdout.toString() }
            val stderrText = synchronized(stderr) { stderr.toString() }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                Result.success(stdoutText)
            } else {
                val msg = stderrText.trim().ifBlank { stdoutText.trim() }.ifBlank { "exit $exitCode" }
                log.warn("{} CLI failed: cmd={} msg={}", originalArgs.firstOrNull() ?: "?", safeCmd(originalArgs), msg)
                Result.failure(CliInvocationFailure(exitCode, stderrText.take(500), msg))
            }
        }
    }

    private fun safeCmd(args: List<String>): String = "${args.firstOrNull() ?: "?"} ${args.getOrNull(1).orEmpty()} ${args.getOrNull(2).orEmpty()}".trim()
}
