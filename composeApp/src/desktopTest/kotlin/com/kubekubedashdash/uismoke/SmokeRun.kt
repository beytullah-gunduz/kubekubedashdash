package com.kubekubedashdash.uismoke

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A scenario step could not be done (a node never appeared, a wait timed out). */
internal class StepFailed(message: String) : Exception(message)

/** The whole run must stop now (isolation gate failed, MCP server died). */
internal class AbortRun(message: String) : Exception(message)

/** Mutable state of one run (one JUnit class run): paths, counters, the MCP client, the scenario. */
internal object SmokeRun {
    lateinit var repo: File
    lateinit var runDir: File
    var env: Map<String, String> = emptyMap()
    lateinit var mcp: HotReloadMcp

    /** False until [mcp] is assigned (a failed start leaves it unassigned). */
    val mcpStarted: Boolean get() = ::mcp.isInitialized
    var scenario = ""
    lateinit var scenarioDir: File
    var passed = 0
    var failed = 0
    var skipped = 0

    /** This scenario's failed checks, "name (detail)", for the JUnit failure message. */
    val scenarioFailures = mutableListOf<String>()
    var appPid: Long? = null
    var launchStarted: Instant? = null

    @Volatile var gradle: Process? = null

    /** Set when the run aborted (also from the shutdown hook); later scenarios are skipped. */
    @Volatile var abortReason: String? = null

    /** Set when -PuiSmokeKeepApp left a failing scenario's app running; later scenarios are skipped. */
    var keptApp = false
}

// ---------------------------------------------------------------- results

/** Print one line right away. */
internal fun say(msg: String) {
    println(msg)
    System.out.flush()
}

/** Record one PASS/FAIL line for the current scenario. */
internal fun check(name: String, ok: Boolean, detail: String = ""): Boolean {
    if (ok) {
        SmokeRun.passed++
    } else {
        SmokeRun.failed++
        SmokeRun.scenarioFailures += if (detail.isEmpty()) name else "$name ($detail)"
    }
    val extra = if (detail.isNotEmpty() && !ok) " ($detail)" else ""
    say("${if (ok) "PASS" else "FAIL"} ${SmokeRun.scenario}: $name$extra")
    return ok
}

/** Record a SKIP line: a check that could not be exercised, which is not a failure. */
internal fun skip(name: String, why: String) {
    SmokeRun.skipped++
    say("SKIP ${SmokeRun.scenario}: $name ($why)")
}

/** A path relative to the repository root, for printing. */
internal fun rel(file: File): String = runCatching {
    val repo = SmokeRun.repo.canonicalFile
    val target = file.canonicalFile
    if (target.startsWith(repo)) target.relativeTo(repo).path else file.absolutePath
}.getOrDefault(file.absolutePath)

// ---------------------------------------------------------------- processes

/** True when the process exists and is not a zombie (the hot run's parent may not reap it). */
internal fun alive(pid: Long): Boolean {
    val handle = ProcessHandle.of(pid).orElse(null) ?: return false
    if (!handle.isAlive) return false
    val ps = ProcessBuilder("ps", "-o", "stat=", "-p", pid.toString())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val out = ps.inputStream.bufferedReader().readText().trim()
    ps.waitFor()
    return out.isNotEmpty() && !out.startsWith("Z")
}

/** The processes below [pid], found through the JVM's process handles. */
internal fun descendantsOf(pid: Long): List<ProcessHandle> = ProcessHandle.of(pid).map { it.descendants().toList() }.orElse(emptyList())

/** True when [pid] started at or after [since] (2 s of slack for the clock's resolution). */
internal fun startedSince(pid: Long, since: Instant): Boolean {
    val start = ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null) ?: return false
    return !start.isBefore(since.minusSeconds(2))
}

/** False for pids no app of this run can have: 0, 1, this JVM and its ancestors. */
internal fun isSignallable(pid: Long): Boolean {
    val self = ProcessHandle.current()
    if (pid <= 1 || pid == self.pid()) return false
    return generateSequence(self.parent().orElse(null)) { it.parent().orElse(null) }.none { it.pid() == pid }
}

/** SIGTERM [pids] that still exist. */
private fun terminate(pids: List<Long>) = pids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }

/** SIGKILL [pids] that still exist. */
private fun kill(pids: List<Long>) = pids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }

/** SIGTERM a process and every process under it (the tree is read first: children are reparented once the parent goes). */
internal fun terminateTree(process: Process) {
    val below = process.descendants().toList()
    process.destroy()
    below.forEach { it.destroy() }
}

/** The seconds in a duration the way Python prints `{timeout:g}`: `30`, `120`, `0.5`. */
private fun secondsText(timeout: Duration): String {
    val seconds = timeout.inWholeMilliseconds / 1000.0
    return if (seconds == Math.floor(seconds)) seconds.toLong().toString() else seconds.toString()
}

/** Poll [fn] until it is true; false on timeout. A dead MCP server is re-raised, other MCP errors are retried. */
internal fun waitUntil(timeout: Duration, every: Duration = 250.milliseconds, fn: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (true) {
        try {
            if (fn()) return true
        } catch (e: McpError) {
            if (SmokeRun.mcpStarted && SmokeRun.mcp.dead) throw e
        }
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(every.inWholeMilliseconds)
    }
}

/** [waitUntil], but a timeout aborts the scenario with a clear message. */
internal fun must(what: String, timeout: Duration = 30.seconds, fn: () -> Boolean) {
    if (!waitUntil(timeout, fn = fn)) throw StepFailed("timed out after ${secondsText(timeout)}s waiting for $what")
}

/** Run ./gradlew with the run's JDK, output to a log file in the scenario dir. Returns (exit code, log text). */
internal fun runGradle(args: List<String>, logName: String, timeout: Duration = 15.minutes): Pair<Int, String> {
    SmokeRun.abortReason?.let { throw AbortRun(it) }
    val log = File(SmokeRun.scenarioDir, logName)
    val builder = ProcessBuilder(listOf("./gradlew", "--console=plain") + args)
        .directory(SmokeRun.repo)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log))
    builder.environment().apply {
        clear()
        putAll(SmokeRun.env)
    }
    val process = builder.start()
    SmokeRun.gradle = process
    try {
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            terminateTree(process)
            throw StepFailed("./gradlew ${args.joinToString(" ")} did not finish within ${timeout.inWholeSeconds} s")
        }
        return process.exitValue() to log.readText()
    } catch (e: InterruptedException) {
        terminateTree(process)
        throw e
    } finally {
        SmokeRun.gradle = null
    }
}

/** The last lines of a log, for failure messages. */
internal fun tail(text: String, lines: Int = 40): String {
    val all = text.lines()
    return (if (all.isNotEmpty() && all.last().isEmpty()) all.dropLast(1) else all).takeLast(lines).joinToString("\n")
}

// ---------------------------------------------------------------- hot-run files and the isolation gate

/** The hot-run pid file the MCP server follows. */
internal fun pidFile(): File = File(SmokeRun.repo, "composeApp/build/run/desktopMain/desktopMain.pid")

/** The JVM argfile of the last hotRunDesktop* task. */
internal fun argfile(): File = File(SmokeRun.repo, "composeApp/build/run/desktopMain/desktopMain.argfile")

/** The pid in the pid file, or null when it is absent or unreadable. */
internal fun pidFilePid(): Long? = runCatching { parsePidFile(pidFile().readText()) }.getOrNull()

/** Abort the run unless the argfile proves isolation. */
internal fun requireIsolation(dataDir: File, label: String) {
    val problems = try {
        isolationProblems(argfileProps(argfile().readText()), dataDir, File(SmokeRun.repo, "composeApp/$EMPTY_KUBECONFIG_SUFFIX"))
    } catch (e: IOException) {
        listOf("cannot read ${rel(argfile())}: ${e.message}")
    }
    if (problems.isNotEmpty()) throw AbortRun("isolation gate failed $label: " + problems.joinToString("; "))
}

/**
 * SIGTERM the app, its child processes and the pid, SIGKILL leftovers after 10 s, then wait for the
 * files and the MCP link to clear. [quick] (the shutdown hook) skips the two 30 s waits.
 */
internal fun stopApp(pid: Long, quick: Boolean = false) {
    if (!isSignallable(pid)) {
        say("warning: not signalling pid $pid: it is this JVM, one of its ancestors or a system pid")
        SmokeRun.appPid = null
        return
    }
    val victims = descendantsOf(pid).map { it.pid() } + pid
    terminate(victims)
    waitUntil(10.seconds) { victims.none { alive(it) } }
    val leftovers = victims.filter { alive(it) }
    if (leftovers.isNotEmpty()) {
        say("note: SIGKILL for pids $leftovers (they ignored SIGTERM for 10 s)")
        kill(leftovers)
    }
    if (!waitUntil(15.seconds) { !alive(pid) }) say("warning: app pid $pid is still alive after SIGKILL")
    if (!quick && !waitUntil(30.seconds) { !pidFile().exists() }) {
        if (pidFilePid() == pid && !alive(pid)) {
            say("note: removing the stale pid file of the stopped app")
            runCatching { pidFile().delete() }
        } else {
            say("warning: the pid file is still there after the app stopped")
        }
    }
    SmokeRun.appPid = null
    if (!quick && SmokeRun.mcpStarted && SmokeRun.mcp.ready && !SmokeRun.mcp.dead) {
        if (!waitUntil(30.seconds) { !mcpConnected() }) say("warning: the MCP server still reports connected 30 s after the app stopped")
    }
}

/** Gate, start the app with hotRunDesktopAsync on [dataDir], return its pid. */
internal fun launchApp(dataDir: File): Long {
    val prop = "-PhotRunDataDir=${dataDir.path}"
    val (argfileCode, argfileLog) = runGradle(listOf(":composeApp:hotRunDesktopArgfile", prop), "gradle-argfile.log")
    if (argfileCode != 0) throw StepFailed("hotRunDesktopArgfile failed:\n" + tail(argfileLog))
    requireIsolation(dataDir, "before the launch")
    SmokeRun.launchStarted = Instant.now()
    val (launchCode, launchLog) = runGradle(listOf(":composeApp:hotRunDesktopAsync", prop), "gradle-launch.log")
    if (launchCode != 0) throw StepFailed("hotRunDesktopAsync failed:\n" + tail(launchLog))
    val pid = parseStartedPid(launchLog)
        ?: throw StepFailed("hotRunDesktopAsync printed no \"Started ... in background (<pid>)\" line:\n" + tail(launchLog))
    SmokeRun.appPid = pid
    SmokeRun.launchStarted = null
    requireIsolation(dataDir, "after the launch")
    if (!waitUntil(60.seconds) { pidFilePid() == pid }) throw StepFailed("the pid file never named the launched app (pid $pid)")
    return pid
}

/**
 * The app of a launch whose pid this run never learned (an abort during the Gradle launch, or no
 * "Started" line), or null. Taken from this launch's log, else from the pid file, and only when the pid
 * file was written and the process started after the launch began: a stale pid file or a recycled pid
 * is never adopted. A hot run of this worktree that someone else starts during the launch would still
 * look like ours; the startup guard rules out a concurrent one, not a racing one.
 */
internal fun orphanAppPid(): Long? {
    val since = SmokeRun.launchStarted ?: return null
    val scenarioDir = runCatching { SmokeRun.scenarioDir }.getOrNull()
    val log = scenarioDir?.let { File(it, "gradle-launch.log") }
    if (log != null && log.isFile) {
        val logged = parseStartedPid(log.readText())
        if (logged != null) return logged.takeIf { alive(it) && startedSince(it, since) }
    }
    val written = pidFile().lastModified()
    if (written == 0L) return null
    val pid = pidFilePid() ?: return null
    if (written < since.toEpochMilli() - 2000) return null
    return pid.takeIf { alive(it) && startedSince(it, since) }
}

// ---------------------------------------------------------------- MCP helpers

/** Call a tool whose text answer is JSON; null when the text is not JSON. */
internal fun mcpJson(tool: String, arguments: Map<String, Any?> = emptyMap(), timeout: Duration = 120.seconds): JsonElement? {
    val text = SmokeRun.mcp.call(tool, arguments, timeout)
    return runCatching { Json.parseToJsonElement(text) }.getOrNull()
}

/** True when the MCP server reports an attached app. */
internal fun mcpConnected(): Boolean {
    val data = try {
        mcpJson("status", timeout = 30.seconds)
    } catch (e: McpError) {
        return false
    }
    return ((data as? JsonObject)?.get("connected") as? JsonPrimitive)?.booleanOrNull == true
}
