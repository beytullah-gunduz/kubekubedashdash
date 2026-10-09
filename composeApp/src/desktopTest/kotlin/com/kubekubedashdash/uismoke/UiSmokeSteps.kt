package com.kubekubedashdash.uismoke

import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.ui.UiTestHookNames
import com.kubekubedashdash.ui.UiTestState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Helpers over the app's semantic tree and its `ui-test:state:` hook. Everything here reads
// SmokeRun, so only UiSmoke (a live hot run) calls it; the pure parts live in SemanticTree.kt.

/** Which of several matching nodes [find] returns: the first in document order, or the last (the topmost root). */
internal enum class Which { FIRST, LAST }

private val prettyJson = Json { prettyPrint = true }

/** The app's windows. */
internal fun windows(): List<McpWindow> = parseWindows(SmokeRun.mcp.call("list_windows"))

/** The id of the first window. */
internal fun mainWindow(): String = windows().firstOrNull()?.id ?: throw StepFailed("the app has no window")

/** The semantic tree of a window as a list of roots (popups and dialogs are extra roots, later = on top). */
internal fun tree(win: String): List<SemNode> = parseRoots(SmokeRun.mcp.call("get_semantic_tree", mapOf("window_id" to win)))

/** Every node of every root, depth first, in document order. */
internal fun nodes(win: String): List<SemNode> = flatten(tree(win))

/**
 * All nodes in the current tree whose contentDescription / text matches. Never filters by size or
 * visibility (the hook nodes are 0x0). [clickable] also requires an onClick action and an enabled node.
 */
internal fun findNodes(win: String, desc: String? = null, text: String? = null, match: Match = Match.EXACT, clickable: Boolean = true): List<SemNode> = nodes(win).matching(desc, text, match, clickable)

/** Poll the tree every 0.25 s for a matching node; the last match (topmost root) unless [which] is FIRST. Null on timeout. */
internal fun find(
    win: String,
    desc: String? = null,
    text: String? = null,
    match: Match = Match.EXACT,
    clickable: Boolean = true,
    which: Which = Which.LAST,
    timeout: Duration = 10.seconds,
): SemNode? {
    var found: SemNode? = null
    waitUntil(timeout) {
        val hits = findNodes(win, desc, text, match, clickable)
        if (hits.isNotEmpty()) found = if (which == Which.LAST) hits.last() else hits.first()
        hits.isNotEmpty()
    }
    return found
}

/** Run a node's semantic onClick through the MCP. */
internal fun click(win: String, node: SemNode) {
    SmokeRun.mcp.call("click", mapOf("nodeId" to node.id, "window_id" to win))
}

// Right after the first-run screen gives way to the tabs, a window briefly has no semantics owner and a
// click fails with this message although the tree reads fine; it clears within a second or two.
private const val TRANSIENT_CLICK_ERROR = "No semantic owners available"

/** Find the node, click it; on the transient no-owner error, re-find and retry until [timeout]. */
private fun clickWhere(win: String, label: String, desc: String? = null, text: String? = null, match: Match = Match.EXACT, timeout: Duration = 10.seconds) {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (true) {
        val node = find(win, desc = desc, text = text, match = match) ?: throw StepFailed("no clickable node with $label in window $win")
        try {
            click(win, node)
            return
        } catch (e: McpError) {
            if (TRANSIENT_CLICK_ERROR !in e.message.orEmpty() || System.nanoTime() >= deadline || (SmokeRun.mcpStarted && SmokeRun.mcp.dead)) throw e
            Thread.sleep(500)
        }
    }
}

/** Re-find a node by contentDescription right before clicking it (node ids change on recomposition). */
internal fun clickDesc(win: String, name: String, match: Match = Match.EXACT) {
    clickWhere(win, "desc '$name'", desc = name, match = match)
}

/** Re-find a node by text right before clicking it. */
internal fun clickText(win: String, name: String, match: Match = Match.EXACT) {
    clickWhere(win, "text '$name'", text = name, match = match)
}

/** The window's `ui-test:state:` hook, decoded, or null. */
internal fun state(win: String): UiTestState? = nodes(win).uiTestState()

/**
 * [state], retried for up to [timeout] (a read can miss the node while the window recomposes);
 * null when there is none.
 */
internal fun st(win: String, timeout: Duration = 3.seconds): UiTestState? {
    val deadline = System.nanoTime() + timeout.inWholeNanoseconds
    while (true) {
        val s = try {
            state(win)
        } catch (e: McpError) {
            if (SmokeRun.mcpStarted && SmokeRun.mcp.dead) throw e
            null
        }
        if (s != null || System.nanoTime() >= deadline) return s
        Thread.sleep(200)
    }
}

/** Poll until [predicate] holds for the window's state (one tree read per poll; a missing state never matches); false on timeout. */
internal fun waitState(win: String, timeout: Duration = 10.seconds, predicate: (UiTestState) -> Boolean): Boolean = waitUntil(timeout) {
    val s = st(win)
    s != null && predicate(s)
}

/**
 * [waitState], but a timeout aborts the scenario with a clear message. Returns the state that satisfied
 * [predicate]: a later read can come back empty while the window recomposes, so use this snapshot.
 */
internal fun mustState(win: String, what: String, timeout: Duration = 30.seconds, predicate: (UiTestState) -> Boolean): UiTestState {
    var seen: UiTestState? = null
    must(what, timeout) {
        val s = st(win)
        if (s != null && predicate(s)) {
            seen = s
            true
        } else {
            false
        }
    }
    return seen ?: throw StepFailed("no state after waiting for $what")
}

/**
 * After a tab switch: wait until the pager shows the active tab and its slide is over (only one tab's
 * sidebar is composed), so a click on a sidebar row cannot land on the tab sliding away.
 */
internal fun settle(win: String, timeout: Duration = 30.seconds) {
    must("the pager to settle on the active tab", timeout) {
        val s = st(win)
        s != null && s.active != null && s.active == s.page && findNodes(win, text = "Topology").size == 1
    }
}

/** The id of the window whose state satisfies [predicate], or null. */
internal fun windowFor(timeout: Duration = 10.seconds, predicate: (UiTestState) -> Boolean): String? {
    var found: String? = null
    waitUntil(timeout) {
        for (window in windows()) {
            val s = try {
                state(window.id)
            } catch (e: McpError) {
                continue
            }
            if (s != null && predicate(s)) {
                found = window.id
                return@waitUntil true
            }
        }
        false
    }
    return found
}

/** take_screenshot into the scenario dir; records a check that the file exists. */
internal fun shot(win: String, name: String) {
    val path = File(SmokeRun.scenarioDir, "$name.png")
    SmokeRun.mcp.call("take_screenshot", mapOf("window_id" to win, "save_to" to path.path), timeout = 180.seconds)
    check("screenshot $name.png saved", path.isFile && path.length() > 0)
}

/** After a failed scenario: save every window's tree and a screenshot next to the other artifacts. */
internal fun dumpFailure() {
    try {
        for (window in windows()) {
            val id = window.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            File(SmokeRun.scenarioDir, "failure-$id.json").writeText(prettyJson.encodeToString(JsonArray(tree(window.id).map { it.json })))
            SmokeRun.mcp.call("take_screenshot", mapOf("window_id" to window.id, "save_to" to File(SmokeRun.scenarioDir, "failure-$id.png").path), timeout = 180.seconds)
        }
    } catch (_: Exception) {
        // Best effort: the scenario already failed, and the app may be gone.
    }
}

// ---------------------------------------------------------------- shared scenario steps

/** The key of the All Clusters tab. */
internal val ALL_CLUSTERS: String = WorkspaceTab.AllClusters.key

// The demo cluster's tab chips end with these (the chip text reads "D demo-cluster (mock) #1").
internal const val TAB1_SUFFIX = "demo-cluster (mock) #1"
internal const val TAB2_SUFFIX = "demo-cluster (mock) #2"

/** The window size (width to height, in points) the short-window scenarios resize to. */
internal val SHORT_WINDOW = 1000 to 600

/** From the first-run screen: open the demo cluster, then a second one. Returns (tab 1 key, tab 2 key). */
internal fun twoDemoTabs(win: String): Pair<String, String?> {
    must("the first-run screen", 120.seconds) { find(win, text = "Try demo cluster", match = Match.PREFIX, timeout = Duration.ZERO) != null }
    clickText(win, "Try demo cluster", Match.PREFIX)
    val first = mustState(win, "the first demo cluster's overview") {
        it.active?.startsWith("cluster:") == true && !it.firstRun && it.screen == "Cluster Overview"
    }
    val tab1 = checkNotNull(first.active)
    clickDesc(win, "Open another cluster")
    clickText(win, "In-memory mock cluster with sample data", Match.CONTAINS)
    val second = mustState(win, "the second demo cluster's overview") {
        it.tabs.size == 2 && it.active != tab1 && it.screen == "Cluster Overview"
    }
    settle(win)
    return tab1 to second.active
}

/** Open the All Clusters tab from the cluster picker. */
internal fun openAllClusters(win: String) {
    clickDesc(win, "Open another cluster")
    clickText(win, "All Clusters view", Match.PREFIX)
    mustState(win, "the All Clusters tab to become active") { it.active == ALL_CLUSTERS }
}

/** Two demo tabs + All Clusters, then tear All Clusters out. Returns the new window's id (or null). */
internal fun tearOutAllClusters(win: String): String? {
    twoDemoTabs(win)
    openAllClusters(win)
    clickDesc(win, UiTestHookNames.TEAR_OUT + ALL_CLUSTERS)
    must("a second window after the tear-out") { windows().size == 2 }
    return windowFor { it.tabs == listOf(ALL_CLUSTERS) }
}

/** The window root's height in px, waiting out a tree that is briefly empty. */
internal fun rootHeight(win: String, timeout: Duration = 10.seconds): Double {
    var found = 0.0
    must("the window's semantic tree", timeout) {
        val height = tree(win).firstOrNull()?.bounds?.height ?: 0.0
        if (height != 0.0) found = height
        height != 0.0
    }
    return found
}

/** The window's height in points from list_windows, waiting out an empty answer. */
internal fun windowHeightPt(win: String, timeout: Duration = 10.seconds): Double {
    var found = 0.0
    must("the window in list_windows", timeout) {
        val hits = windows().filter { it.id == win && it.height != 0.0 }.map { it.height }
        if (hits.isNotEmpty()) found = hits.last()
        hits.isNotEmpty()
    }
    return found
}

/**
 * The node with this exact contentDescription inside the title bar band, or null. Polled for up to
 * [timeout]: a read right after a modal closes can miss the bar while the window recomposes.
 */
internal fun titleBarNode(win: String, desc: String, barPx: Double, timeout: Duration = 5.seconds): SemNode? {
    var found: SemNode? = null
    waitUntil(timeout) {
        found = findNodes(win, desc = desc, clickable = false).lastOrNull { node -> node.bounds?.let { it.y < barPx } == true }
        found != null
    }
    return found
}

/** A pixel count as text, without a trailing ".0". */
internal fun plain(value: Double): String = value.toString().removeSuffix(".0")
