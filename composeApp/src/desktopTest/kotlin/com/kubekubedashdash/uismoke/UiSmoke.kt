package com.kubekubedashdash.uismoke

import com.kubekubedashdash.ui.UiTestHookNames
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * UI smoke for windows, tabs and the window-wide history, driven through the Compose Hot Reload
 * MCP server. Run it with `./gradlew :composeApp:uiSmoke` on a macOS desktop session; any other
 * runner skips it without launching anything.
 *
 * Every scenario starts a fresh hot-run app on a fresh throwaway data directory and the demo
 * cluster only (an empty kubeconfig), drives it through the MCP server's semantic tree and the
 * hidden `ui-test:*` hook nodes the app composes in a hot run, and prints one
 * `PASS|FAIL <scenario>: <check>` line per check.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiSmoke {
    companion object {
        /** True once [startRun] got past its opt-in check, so [endRun] has something to clean up. */
        private var started = false
        private var shutdownHook: Thread? = null
        private val cleaned = AtomicBoolean(false)

        @JvmStatic
        @BeforeClass
        fun startRun() {
            Assume.assumeTrue("run it with ./gradlew :composeApp:uiSmoke", System.getProperty("kkdd.uiSmoke") == "true")
            started = true
            if (!System.getProperty("os.name").startsWith("Mac")) {
                refuse("the UI smoke runs on macOS only (window-button names, title-bar height, ps)")
            }
            val repo = File(System.getProperty("kkdd.uiSmoke.repo") ?: refuse("kkdd.uiSmoke.repo is not set: run it with ./gradlew :composeApp:uiSmoke")).canonicalFile
            if (!(File(repo, "gradlew").isFile && File(repo, "composeApp").isDirectory)) {
                refuse("kkdd.uiSmoke.repo is not the repository root (./gradlew and composeApp/ must be in it)")
            }
            SmokeRun.repo = repo
            if (!System.getenv("ORG_GRADLE_PROJECT_hotRunCloudClis").isNullOrEmpty()) {
                refuse("ORG_GRADLE_PROJECT_hotRunCloudClis is set: the app could run aws/gcloud against real accounts; unset it")
            }
            if (!System.getenv("ORG_GRADLE_PROJECT_hotRunKubeconfig").isNullOrEmpty()) {
                refuse("ORG_GRADLE_PROJECT_hotRunKubeconfig is set: a hot run could read a kubeconfig other than the empty one; unset it")
            }
            val javaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.home")
            val other = pidFilePid()
            if (other != null && alive(other)) {
                refuse("another hot run of this worktree is up (pid $other): the MCP server follows the same pid file and would attach to it; stop it first")
            }
            SmokeRun.env = System.getenv() + ("JAVA_HOME" to javaHome) - "KUBECONFIG"
            SmokeRun.runDir = File(repo, "build/ui-smoke/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))).also { it.mkdirs() }
            say("run directory: ${rel(SmokeRun.runDir)}")
            // Assigned before anything below can fail, so cleanUp always has the handle.
            SmokeRun.mcp = HotReloadMcp(repo, SmokeRun.env, File(SmokeRun.runDir, "mcp.err"))
            val hook = Thread { cleanUp(fromHook = true) }
            Runtime.getRuntime().addShutdownHook(hook)
            shutdownHook = hook
            SmokeRun.mcp.start()
        }

        @JvmStatic
        @AfterClass
        fun endRun() {
            if (!started) return
            cleanUp(fromHook = false)
            try {
                shutdownHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
            } catch (_: IllegalStateException) {
                // The JVM is already shutting down; the hook has nothing left to do.
            }
            if (SmokeRun.skipped > 0) say("${SmokeRun.skipped} skipped")
            say("${SmokeRun.passed} passed, ${SmokeRun.failed} failed")
        }

        private fun refuse(message: String): Nothing = throw IllegalStateException(message)

        /**
         * Stops everything this run started, once: a running Gradle call, the app, the MCP server.
         * The shutdown hook's run is [fromHook]: an interrupted run should not hang the JVM's exit
         * for the app's slow file and MCP-link waits.
         */
        private fun cleanUp(fromHook: Boolean) {
            if (!cleaned.compareAndSet(false, true)) return
            SmokeRun.gradle?.let { terminateTree(it) }
            if (SmokeRun.appPid == null && !SmokeRun.keptApp) SmokeRun.appPid = orphanAppPid()
            val pid = SmokeRun.appPid
            if (pid != null && !SmokeRun.keptApp) {
                try {
                    stopApp(pid, quick = fromHook)
                } catch (e: Exception) {
                    say("warning: could not stop the app cleanly: ${e.message}")
                }
            }
            if (SmokeRun.mcpStarted) SmokeRun.mcp.stop()
        }
    }

    @Test
    fun `s1-first-run-title-bar`() = scenario("first-run-title-bar") {
        // The first-run screen has a title bar with window buttons and disabled Back/Forward.
        val win = mainWindow()
        must("the first-run screen", 120.seconds) { find(win, text = "Try demo cluster", match = Match.PREFIX, timeout = Duration.ZERO) != null }
        for (d in listOf("Close window", "Minimize window", "Maximize window")) {
            check("'$d' is in the title bar", find(win, desc = d, clickable = false, timeout = 5.seconds) != null)
        }
        for (d in listOf("Back", "Forward")) {
            val n = find(win, desc = d, clickable = false, timeout = 5.seconds)
            check("'$d' is disabled", n != null && !n.enabled, if (n == null) "node missing" else "")
        }
        check("state.firstRun is true", st(win)?.firstRun == true)
        // With the cloud CLIs off, the screen must not offer a discovery that would run aws/gcloud.
        val offered = findNodes(win, text = "Discover", match = Match.PREFIX, clickable = false).map { it.text }
        check("the first-run screen offers no EKS/GKE discovery", offered.isEmpty(), "found $offered")
        shot(win, "first-run")
    }

    @Test
    fun `s2-discovery-splash-title-bar`() = scenario("discovery-splash-title-bar") {
        // The EKS/GKE discovery splash (forced by a hook) keeps the title bar.
        val win = mainWindow()
        must("the first-run screen", 120.seconds) { find(win, text = "Try demo cluster", match = Match.PREFIX, timeout = Duration.ZERO) != null }
        clickDesc(win, UiTestHookNames.DISCOVERY_SPLASH)
        must("the first-run screen to give way to the discovery splash") {
            val all = nodes(win)
            all.any { it.contentDescription.startsWith(UiTestHookNames.STATE) } && all.none { it.text.startsWith("Try demo cluster") }
        }
        check("'Close window' is in the title bar", find(win, desc = "Close window", clickable = false, timeout = 5.seconds) != null)
        check("state.firstRun is true", st(win)?.firstRun == true)
        shot(win, "splash")
    }

    /**
     * Runs one scenario on a fresh app. A failed check fails the test; an aborted run skips the
     * scenarios after it, and so does an app kept running for inspection.
     */
    private fun scenario(name: String, body: () -> Unit) {
        Assume.assumeTrue("the run was aborted: ${SmokeRun.abortReason}", SmokeRun.abortReason == null)
        Assume.assumeFalse("an earlier scenario's app was kept running (-PuiSmokeKeepApp)", SmokeRun.keptApp)
        if (SmokeRun.mcp.dead) {
            val reason = "the MCP server exited (see mcp.err in the run directory)"
            SmokeRun.abortReason = reason
            fail(reason)
        }
        val keepApp = System.getProperty("kkdd.uiSmoke.keepApp") == "true"
        SmokeRun.scenario = name
        SmokeRun.scenarioFailures.clear()
        SmokeRun.scenarioDir = File(SmokeRun.runDir, name).also { it.mkdirs() }
        say("--- $name")
        try {
            launchApp(File(SmokeRun.scenarioDir, "data"))
            SmokeRun.mcp.awaitInitialized()
            must("the MCP server to attach to the app with a window", 120.seconds) { mcpConnected() && windows().isNotEmpty() }
            // The isolation gate has passed twice for this launch: only now is a tree read.
            body()
        } catch (e: StepFailed) {
            check("scenario ran to the end", false, e.message.orEmpty())
        } catch (e: McpError) {
            check("scenario ran to the end", false, "MCP error: ${e.message}")
            if (SmokeRun.mcp.dead) abort("the MCP server exited (see mcp.err in the run directory)")
        } catch (e: AbortRun) {
            abort(e.message.orEmpty())
            check("scenario ran to the end", false, e.message.orEmpty())
        } catch (e: Exception) {
            check("scenario ran to the end", false, "unexpected error:\n" + e.stackTraceToString())
        } finally {
            if (SmokeRun.appPid == null) SmokeRun.appPid = orphanAppPid()
            SmokeRun.launchStarted = null
            val failed = SmokeRun.scenarioFailures.isNotEmpty()
            val pid = SmokeRun.appPid
            if (failed && pid != null && SmokeRun.mcp.ready && !SmokeRun.mcp.dead) dumpFailure()
            if (pid != null && !(failed && keepApp)) stopApp(pid)
        }
        val failures = SmokeRun.scenarioFailures.toList()
        val keptPid = SmokeRun.appPid
        if (failures.isNotEmpty() && keepApp && keptPid != null) {
            say("-PuiSmokeKeepApp: the app of $name is still running (pid $keptPid); stop it with: kill $keptPid")
            SmokeRun.keptApp = true
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} check(s) failed in $name:\n" + failures.joinToString("\n") { "- $it" })
        }
    }

    /** Stops the run after this scenario: the later ones are skipped. */
    private fun abort(reason: String) {
        say("ABORT: $reason")
        SmokeRun.abortReason = reason
    }
}
