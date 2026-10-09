package com.kubekubedashdash.uismoke

import com.kubekubedashdash.ui.MAC_TITLE_BAR_HEIGHT_DP
import com.kubekubedashdash.ui.UiTestHookNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * UI smoke for windows, tabs and the window-wide history. It drives a real hot-run copy of the app
 * through the Compose Hot Reload MCP server and checks what the user sees after a tab is torn out,
 * merged back, or walked through with Back/Forward. It does not depend on anything outside this
 * repository.
 *
 * What it covers, by test method:
 * - `s1-first-run-title-bar`, `s2-discovery-splash-title-bar`: the first-run screen and the EKS/GKE
 *   discovery splash have a title bar with window buttons and a disabled Back/Forward.
 * - `s3-tear-out-all-clusters`, `s4-merge-all-clusters-back`: tearing the All Clusters tab out into
 *   its own window, and merging it back.
 * - `s5-history-stays-in-its-window`: the window-wide history after a tab moved to another window:
 *   neither window's Back/Forward points at a tab it does not hold.
 * - `s6-rapid-back-forward`: four Back then four Forward presses with no pause: the pager ends on
 *   the active tab, at the newest place, with no UI error.
 * - `s7-history-shortcut-tab-kinds`: the `Cmd/Ctrl+[` handler on a cluster tab, on All Clusters, and
 *   (when a terminal can be opened) on a terminal tab, where it must pass through to the shell.
 * - `s8-cluster-selector-short-window`: the cluster picker in a 1000x600 window: its footer rows
 *   (All Clusters view, Discover EKS, Discover GKE) keep their room instead of being squeezed out
 *   by the list.
 * - `s9-modal-keeps-title-bar`: an open modal (the cluster picker, in a 1000x280 window) sits under
 *   the title bar, the title bar's Settings and Back buttons are disabled and `Cmd/Ctrl+[` passes
 *   through until the picker closes.
 *
 * What it does not cover:
 * - The drag gesture itself. The MCP has no drag, so hidden test hooks call the same code a drag
 *   ends in (`WorkspaceManager.handleChipRelease` / `handleTabRelease`).
 * - Real key events. The MCP has no keyboard; a hook calls the same function the key handler calls
 *   for `Cmd/Ctrl+[` and `Cmd/Ctrl+]`.
 * - Pixels, beyond the screenshots it saves for you to look at (Retro, high contrast, ...).
 * - Windows and Linux. macOS only: the run refuses elsewhere (the window-button names, the title-bar
 *   height and `ps` are macOS's).
 *
 * Running it, from the repository root, on a desktop session with JDK 21:
 * ```
 * ./gradlew :composeApp:uiSmoke                                      every scenario, in order
 * ./gradlew :composeApp:uiSmoke --tests '*s3-tear-out-all-clusters'  one scenario
 * ./gradlew :composeApp:uiSmoke -PuiSmokeKeepApp=true                leave a failing scenario's app running
 * ```
 * Leave the mouse and keyboard alone while it runs: every scenario compiles and launches a fresh
 * app, so a full run takes a while and opens real windows. It runs through the `uiSmoke` task only:
 * `desktopTest` excludes this class, so an IDE run or `desktopTest --tests` for it reports no tests.
 * The output is one line per check, `PASS|FAIL <scenario>: <check>` (`SKIP` for a check that could
 * not be exercised, for example when the pod has several containers and no terminal opens), then
 * `N passed, M failed`. A failed check fails its test method.
 *
 * Isolation:
 * - Each scenario launches the app with `-PhotRunDataDir=<run>/<scenario>/data`, a data directory
 *   that did not exist before. Your preferences, session and logs are not touched.
 * - The kubeconfig stays the empty test one, so the demo cluster is the only cluster.
 * - The cloud CLIs are off: hot runs start with `-Dkkdd.disableCloudClis=true`, which makes `aws`,
 *   `gcloud`, `gke-gcloud-auth-plugin`, `az` and `kubelogin` resolve as missing in the app. No
 *   click, intended or stray, can start a real EKS/GKE discovery against your accounts; the
 *   first-run screen shows no Discover button, and `s1-first-run-title-bar` checks that. The
 *   discovery splash is forced through a hook.
 * - Before the app starts and again after, this class reads the JVM argfile and aborts the run
 *   unless it holds the empty kubeconfig, this scenario's data directory,
 *   `-Dkkdd.uiTestHooks=true` and `-Dkkdd.disableCloudClis=true`. No semantic tree is read before
 *   that. [startRun] fails (it refuses to start) when `ORG_GRADLE_PROJECT_hotRunKubeconfig` or
 *   `ORG_GRADLE_PROJECT_hotRunCloudClis` is set.
 * - [startRun] also fails while another hot run of the same worktree is up (the MCP server follows
 *   one pid file and would attach to it). Stop other Hot Reload MCP servers of this worktree first.
 * - It only ever signals processes it started: the app, the processes below it, and the Gradle
 *   client that runs the MCP server with every process under it. An app whose launch was
 *   interrupted is stopped too, but only when it started after that launch began. There are no
 *   pattern kills.
 *
 * Where things go: `build/ui-smoke/<YYYYmmdd-HHMMSS>/` (git-ignored), and the JUnit report is
 * `composeApp/build/reports/tests/uiSmoke/`.
 * - `mcp.err` is the MCP server's stderr for the whole run.
 * - `<scenario>/` holds the screenshots (`.png`); after a failing scenario also the semantic trees
 *   (`failure-*.json`) and `failure-*.png`.
 * - `<scenario>/gradle-argfile.log` and `gradle-launch.log` are the Gradle output of the launch.
 * - `<scenario>/data/` is the throwaway data directory.
 *
 * The test hooks: the app composes invisible, zero-size nodes named `ui-test:*` (tear a tab out,
 * merge it into the other window, Back, Forward, force the discovery splash, and one that reports
 * the window's state as JSON) only when `-Dkkdd.uiTestHooks=true`. The `hotRun*` Gradle tasks set
 * it; release builds, tests and the screenshot generator never do. See `UiTestHooks.kt`.
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

    @Test
    fun `s3-tear-out-all-clusters`() = scenario("tear-out-all-clusters") {
        // Tearing All Clusters out opens it in its own window, with a title bar, active.
        val win = mainWindow()
        val torn = tearOutAllClusters(win)
        check("a window shows only the All Clusters tab", torn != null)
        if (torn != null) {
            val s = st(torn)
            check("the new window's active tab is All Clusters", s?.active == ALL_CLUSTERS, "active=${s?.active}")
            check("the new window is not on the first-run screen", s?.firstRun == false)
            check("the new window has a 'Close window' button", find(torn, desc = "Close window", clickable = false, timeout = 5.seconds) != null)
            shot(torn, "tear-out-new")
        }
        val old = st(win)
        check("the old window no longer has All Clusters", ALL_CLUSTERS !in old?.tabs.orEmpty(), "tabs=${old?.tabs}")
        check("the old window's active tab is a cluster", old?.active?.startsWith("cluster:") == true, "active=${old?.active}")
        shot(win, "tear-out-old")
    }

    @Test
    fun `s4-merge-all-clusters-back`() = scenario("merge-all-clusters-back") {
        // Merging the torn-out All Clusters tab back leaves one window with it first and active.
        val win = mainWindow()
        val torn = tearOutAllClusters(win) ?: throw StepFailed("no window with only the All Clusters tab after the tear-out")
        clickDesc(torn, UiTestHookNames.MERGE + ALL_CLUSTERS)
        must("one window after the merge") { windows().size == 1 }
        val left = windows().first().id
        val s = mustState(left, "the remaining window's state") { it.tabs.isNotEmpty() }
        check("All Clusters is the first tab of the remaining window", s.tabs.firstOrNull() == ALL_CLUSTERS, "tabs=${s.tabs}")
        check("All Clusters is active in the remaining window", s.active == ALL_CLUSTERS, "active=${s.active}")
        shot(left, "merged")
    }

    @Test
    fun `s5-history-stays-in-its-window`() = scenario("history-stays-in-its-window") {
        // After a tab is torn out, neither window's Back/Forward points at a tab it does not hold.
        val win = mainWindow()
        twoDemoTabs(win)
        clickText(win, "Nodes")
        mustState(win, "the Nodes screen") { it.screen == "Nodes" }
        val moved = checkNotNull(st(win)?.active) { "the window state has no active tab" }
        clickText(win, TAB1_SUFFIX, Match.SUFFIX)
        mustState(win, "tab 1 to become active") { it.active != moved }
        settle(win)
        clickText(win, "Pods")
        mustState(win, "the Pods screen") { it.screen == "Pods" }
        clickText(win, TAB2_SUFFIX, Match.SUFFIX)
        mustState(win, "tab 2 to become active again") { it.active == moved }
        settle(win)
        clickDesc(win, UiTestHookNames.TEAR_OUT + moved)
        must("a second window after the tear-out") { windows().size == 2 }
        val src = windowFor { moved !in it.tabs }
        val dst = windowFor { moved in it.tabs }
        if (src == null || dst == null) throw StepFailed("could not tell the source and the torn-out window apart (src=$src, dst=$dst)")
        val sSrc = st(src)
        check("the source window's Back stack has no entry for the moved tab", moved !in sSrc?.back.orEmpty(), "back=${sSrc?.back}")
        check("the source window's Forward stack has no entry for the moved tab", moved !in sSrc?.forward.orEmpty(), "forward=${sSrc?.forward}")
        val sDst = st(dst)
        check("the torn-out window starts with an empty Back stack", sDst?.back == emptyList<String>(), "back=${sDst?.back}")
        for (i in 1..5) {
            val back = find(src, desc = "Back", clickable = false, timeout = 3.seconds)
            if (back == null || !back.enabled) break
            val before = st(src)?.back.orEmpty().size
            click(src, back)
            waitState(src, 5.seconds) { it.back.size < before }
            val now = st(src)
            check("Back #$i in the source window does not land on the moved tab", now?.active != moved, "active=${now?.active}")
        }
        shot(src, "source")
        shot(dst, "torn-out")
    }

    @Test
    fun `s6-rapid-back-forward`() = scenario("rapid-back-forward") {
        // Four Backs then four Forwards without pausing leave the pager on the active tab, at the newest place.
        val win = mainWindow()
        val (_, tab2) = twoDemoTabs(win)
        clickText(win, "Pods")
        mustState(win, "the Pods screen") { it.screen == "Pods" }
        clickText(win, "Nodes")
        mustState(win, "the Nodes screen") { it.screen == "Nodes" }
        clickText(win, TAB1_SUFFIX, Match.SUFFIX)
        mustState(win, "tab 1 to become active") { it.active != tab2 }
        settle(win)
        clickText(win, "Deployments")
        mustState(win, "the Deployments screen") { it.screen == "Deployments" }
        repeat(4) { clickDesc(win, "Back") }
        repeat(4) { clickDesc(win, "Forward") }
        val settled = waitState(win, 10.seconds) { it.active != null && it.active == it.page }
        val s = st(win)
        check("the pager shows the active tab after the rapid presses", settled, "active=${s?.active}, page=${s?.page}")
        val fwd = find(win, desc = "Forward", clickable = false, timeout = 5.seconds)
        check(
            "back at the newest place: Deployments, Forward disabled",
            s?.screen == "Deployments" && fwd != null && !fwd.enabled,
            "screen=${s?.screen}, forward=${if (fwd == null) "missing" else fwd.enabled}",
        )
        val err = mcpJson("get_ui_error", mapOf("window_id" to win))
        check("get_ui_error reports no UI error", ((err as? JsonObject)?.get("hasError") as? JsonPrimitive)?.booleanOrNull == false, "answer=$err")
        shot(win, "after-rapid")
    }

    @Test
    fun `s7-history-shortcut-tab-kinds`() = scenario("history-shortcut-tab-kinds") {
        // The Cmd/Ctrl+[ handler walks history on a cluster tab and on All Clusters, and passes through on a terminal tab.
        val win = mainWindow()
        twoDemoTabs(win)
        clickText(win, "Nodes")
        mustState(win, "the Nodes screen") { it.screen == "Nodes" }
        clickDesc(win, UiTestHookNames.BACK)
        var ok = waitState(win) { it.lastShortcut == "handled" && it.screen == "Cluster Overview" }
        check("back on a cluster tab is handled and returns to the overview", ok, "state=${st(win)}")

        openAllClusters(win)
        clickDesc(win, UiTestHookNames.BACK)
        ok = waitState(win) { it.lastShortcut == "handled" && it.active?.startsWith("cluster:") == true }
        check("back on the All Clusters tab is handled and returns to a cluster", ok, "state=${st(win)}")

        clickText(win, "Pods")
        mustState(win, "the Pods screen") { it.screen == "Pods" }
        val name = "back on a terminal tab passes through"
        // The first "⋮" is the table header's columns menu, the second is the first row's.
        if (!waitUntil(15.seconds) { findNodes(win, text = "⋮").size >= 2 }) {
            skip(name, "no row menu found")
            return@scenario
        }
        val menus = findNodes(win, text = "⋮")
        if (menus.size < 2) {
            skip(name, "the row menu vanished before it was clicked")
            return@scenario
        }
        click(win, menus[1])
        val opener = find(win, text = "Open terminal", timeout = 10.seconds)
        if (opener == null) {
            skip(name, "no 'Open terminal' entry in the row menu")
            return@scenario
        }
        click(win, opener)
        if (!waitState(win) { it.active?.startsWith("terminal:") == true }) {
            skip(name, "no terminal tab opened (a pod with several containers opens a picker)")
            return@scenario
        }
        clickDesc(win, UiTestHookNames.BACK)
        ok = waitState(win) { it.lastShortcut == "passed" }
        val s = st(win)
        check(name, ok && s?.active?.startsWith("terminal:") == true, "state=$s")
        shot(win, "terminal")
    }

    @Test
    fun `s8-cluster-selector-short-window`() = scenario("cluster-selector-short-window") {
        // In a short window the cluster picker still shows its footer rows (All Clusters view, Discover EKS/GKE).
        val win = mainWindow()
        twoDemoTabs(win) // "All Clusters view" is offered from two cluster tabs on: the tallest footer
        val (width, height) = SHORT_WINDOW
        val before = rootHeight(win)
        SmokeRun.mcp.call("resize_window", mapOf("window_id" to win, "width" to width, "height" to height))
        must("the window to shrink to ${width}x$height") {
            val now = tree(win).firstOrNull()?.bounds?.height
            now != null && now != before
        }
        clickDesc(win, "Open another cluster")
        must("the cluster picker") { find(win, text = "Select Cluster", match = Match.PREFIX, clickable = false, timeout = Duration.ZERO) != null }
        val windowHeight = rootHeight(win)
        val scale = windowHeight / windowHeightPt(win)
        val allRow = find(win, text = "All Clusters view", match = Match.PREFIX, clickable = false, timeout = 5.seconds)
        val ab = allRow?.bounds
        val allBottom = ab?.bottom ?: 0.0
        check(
            "'All Clusters view' is visible in a ${width}x$height window",
            allRow != null && (ab?.height ?: 0.0) > 0 && allBottom <= windowHeight,
            "bounds=$ab, window height=${plain(windowHeight)}",
        )
        val card = find(win, text = "Select Cluster", match = Match.PREFIX, clickable = false, timeout = 5.seconds)
        val cb = card?.bounds
        val cardBottom = cb?.bottom ?: 0.0
        for (label in listOf("Discover EKS clusters", "Discover GKE clusters")) {
            val node = find(win, text = label, match = Match.PREFIX, clickable = false, timeout = 2.seconds)
            val visible: Boolean
            val detail: String
            if (node != null) {
                val b = node.bounds
                visible = (b?.height ?: 0.0) > 0 && (b?.bottom ?: 0.0) <= windowHeight
                detail = "bounds=$b, window height=${plain(windowHeight)}"
            } else {
                // With the cloud CLIs off the row is not clickable, so its text merges into the card's
                // node: it is on screen when the card holds it, fits the window and keeps room for the
                // two Discover rows (about 56 dp each) under a laid-out All Clusters view row (a
                // squeezed footer leaves that row 0x0, which would make the room look like the card).
                val room = cardBottom - allBottom
                visible = card != null && label in card.text && cardBottom <= windowHeight &&
                    (ab?.height ?: 0.0) > 0 && room >= 100 * scale
                detail = "card=$cb, room under All Clusters view=${plain(room)}px, window height=${plain(windowHeight)}"
            }
            check("'$label' is visible in a ${width}x$height window", visible, detail)
        }
        shot(win, "selector-short")
    }

    @Test
    fun `s9-modal-keeps-title-bar`() = scenario("modal-keeps-title-bar") {
        // An open modal (the cluster picker) sits under the title bar, whose controls go inert until it closes.
        val win = mainWindow()
        must("the first-run screen", 120.seconds) { find(win, text = "Try demo cluster", match = Match.PREFIX, timeout = Duration.ZERO) != null }
        clickText(win, "Try demo cluster", Match.PREFIX)
        mustState(win, "the demo cluster's overview") { it.screen == "Cluster Overview" }
        // Short enough that a picker laid out over the whole window would reach the title bar.
        val width = 1000
        val height = 280
        val before = rootHeight(win)
        SmokeRun.mcp.call("resize_window", mapOf("window_id" to win, "width" to width, "height" to height))
        must("the window to shrink to ${width}x$height") {
            val now = tree(win).firstOrNull()?.bounds?.height
            now != null && now != before
        }
        val rootPx = rootHeight(win)
        val windowPt = windowHeightPt(win)
        val barPx = MAC_TITLE_BAR_HEIGHT_DP * rootPx / windowPt

        // One step of history, so Back has somewhere to go.
        clickText(win, "Nodes")
        mustState(win, "the Nodes screen") { it.screen == "Nodes" }
        var settings = titleBarNode(win, "Settings", barPx)
        check("'Settings' is enabled before the picker opens", settings != null && settings.enabled, "node=${settings?.json}")
        var back = titleBarNode(win, "Back", barPx)
        check("'Back' is enabled before the picker opens", back != null && back.enabled, "node=${back?.json}")

        clickDesc(win, "Open another cluster")
        must("the cluster picker") { find(win, text = "Select Cluster", match = Match.PREFIX, clickable = false, timeout = Duration.ZERO) != null }
        val card = find(win, text = "Select Cluster", match = Match.PREFIX, clickable = false, timeout = 5.seconds)
        val top = card?.bounds?.y ?: -1.0
        check("the picker starts under the title bar", top >= barPx - 1, "card top=${plain(top)}px, title bar=${plain(barPx)}px")
        settings = titleBarNode(win, "Settings", barPx)
        check("'Settings' is disabled while the picker is open", settings != null && !settings.enabled, "node=${settings?.json}")
        back = titleBarNode(win, "Back", barPx)
        check("'Back' is disabled while the picker is open", back != null && !back.enabled, "node=${back?.json}")
        clickDesc(win, UiTestHookNames.BACK)
        val ok = waitState(win) { it.lastShortcut == "passed" }
        check("Cmd/Ctrl+[ passes through while the picker is open", ok, "state=${st(win)}")
        shot(win, "picker-open")

        clickDesc(win, "Close")
        must("the picker to close") { findNodes(win, text = "Select Cluster", match = Match.PREFIX, clickable = false).isEmpty() }
        settings = titleBarNode(win, "Settings", barPx)
        check("'Settings' is enabled again once the picker is closed", settings != null && settings.enabled, "node=${settings?.json}")
        back = titleBarNode(win, "Back", barPx)
        check("'Back' is enabled again once the picker is closed", back != null && back.enabled, "node=${back?.json}")
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
