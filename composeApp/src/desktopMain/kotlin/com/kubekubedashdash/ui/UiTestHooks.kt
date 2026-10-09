package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.NavigationHistoryState
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.services.WorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** True only when a hot run starts the app (`-Dkkdd.uiTestHooks=true`): never in a release build. */
internal val UiTestHooksEnabled: Boolean = System.getProperty("kkdd.uiTestHooks") == "true"

/** contentDescription prefixes the hooks use; scripts/ui-smoke matches on them. */
internal object UiTestHookNames {
    const val STATE = "ui-test:state:"
    const val TEAR_OUT = "ui-test:tear-out:"
    const val MERGE = "ui-test:merge:"
    const val BACK = "ui-test:back"
    const val FORWARD = "ui-test:forward"
    const val DISCOVERY_SPLASH = "ui-test:discovery-splash"
}

/**
 * What the state node reports for one window. The UI smoke (`uismoke.UiSmoke` in the tests)
 * decodes the same class, so a renamed or retyped field breaks its compilation, not a run.
 */
@Serializable
internal data class UiTestState(
    val window: String,
    val active: String?,
    val page: String?,
    val tabs: List<String>,
    val back: List<String>,
    val forward: List<String>,
    val firstRun: Boolean,
    val screen: String?,
    val pane: Boolean,
    val lastShortcut: String,
)

/** [UiTestState] for one window, as one line of JSON. */
internal fun uiTestStateJson(
    workspaceId: String,
    activeTabKey: String?,
    pagerPageKey: String?,
    tabKeys: List<String>,
    history: NavigationHistoryState,
    firstRun: Boolean,
    screenTitle: String?,
    paneOpen: Boolean,
    lastShortcut: String,
): String = Json.encodeToString(
    UiTestState.serializer(),
    UiTestState(
        window = workspaceId,
        active = activeTabKey,
        page = pagerPageKey,
        tabs = tabKeys,
        back = history.back.map { it.tabKey },
        forward = history.forward.map { it.tabKey },
        firstRun = firstRun,
        screen = screenTitle,
        pane = paneOpen,
        lastShortcut = lastShortcut,
    ),
)

/** The first of [candidates] that no window's drop zone contains, or null. */
internal fun firstPointOutside(candidates: List<Offset>, zones: List<Rect>): Offset? = candidates.firstOrNull { p -> zones.none { it.contains(p) } }

/** Ends a drag of [tabKey] at screen point ([x], [y]) the way the tab's own chip does. */
private fun releaseTab(workspace: Workspace, tabKey: String, x: Int, y: Int) {
    val tab = workspace.tabs.value.firstOrNull { it.key == tabKey } ?: return
    if (tab is WorkspaceTab.Cluster) {
        WorkspaceManager.notifyDragMove(tab.session.id, x, y)
        WorkspaceManager.handleChipRelease(tab.session.id, x, y)
    } else {
        WorkspaceManager.notifyDragMoveTab(tabKey, x, y)
        WorkspaceManager.handleTabRelease(tabKey, x, y)
    }
}

/** Tears [tabKey] out of [workspace]: a release inside its window but outside every title bar. */
private fun tearOut(workspace: Workspace, tabKey: String) {
    val window = workspace.awtWindow ?: return
    val origin = window.locationOnScreen
    val centre = Offset(origin.x + window.width / 2f, origin.y + window.height / 2f)
    val candidates = listOf(centre) + (1..6).map { Offset(centre.x, centre.y + 60f * it) }
    val zones = WorkspaceManager.workspaces.value.mapNotNull { it.dropZoneScreenBounds.value }
    val point = firstPointOutside(candidates, zones) ?: return
    releaseTab(workspace, tabKey, point.x.toInt(), point.y.toInt())
}

/** Merges [tabKey] into the first other window: a release on the middle of its title bar. */
private fun mergeIntoNextWindow(workspace: Workspace, tabKey: String) {
    val target = WorkspaceManager.workspaces.value.firstOrNull { it.id != workspace.id } ?: return
    val zone = target.dropZoneScreenBounds.value ?: return
    releaseTab(workspace, tabKey, zone.center.x.toInt(), zone.center.y.toInt())
}

/**
 * Invisible, zero-size nodes that let scripts/ui-smoke drive what the Compose
 * Hot Reload MCP cannot: tab drags between windows and the history shortcuts.
 * Each node's contentDescription names its action and the MCP's click runs it
 * through the semantic onClick. One more node reports this window's state as
 * JSON in its contentDescription. Composed only when [UiTestHooksEnabled].
 */
@Composable
internal fun UiTestHooks(
    workspace: Workspace,
    tabs: List<WorkspaceTab>,
    activeTabKey: String?,
    activeSession: ClusterSession?,
    pagerState: PagerState,
    history: NavigationHistoryState,
    firstRun: Boolean,
    lastShortcut: String,
    onHistoryShortcut: (back: Boolean) -> Unit,
    onForceDiscoverySplash: () -> Unit,
) {
    val windows by WorkspaceManager.workspaces.collectAsState()
    val screen = activeSession?.viewModel?.currentScreen?.collectAsState()?.value
    val pane = activeSession?.viewModel?.extraPaneScreen?.collectAsState()?.value
    val state = uiTestStateJson(
        workspaceId = workspace.id.value,
        activeTabKey = activeTabKey,
        pagerPageKey = tabs.getOrNull(pagerState.currentPage)?.key,
        tabKeys = tabs.map { it.key },
        history = history,
        firstRun = firstRun,
        screenTitle = screen?.title,
        paneOpen = pane != null,
        lastShortcut = lastShortcut,
    )
    hook(UiTestHookNames.STATE + state) {}
    hook(UiTestHookNames.BACK) { onHistoryShortcut(true) }
    hook(UiTestHookNames.FORWARD) { onHistoryShortcut(false) }
    hook(UiTestHookNames.DISCOVERY_SPLASH, onForceDiscoverySplash)
    tabs.forEach { tab ->
        hook(UiTestHookNames.TEAR_OUT + tab.key) { tearOut(workspace, tab.key) }
        if (windows.size > 1) hook(UiTestHookNames.MERGE + tab.key) { mergeIntoNextWindow(workspace, tab.key) }
    }
}

@Composable
private fun hook(name: String, action: () -> Unit) {
    Box(
        Modifier.size(0.dp).semantics {
            contentDescription = name
            onClick {
                action()
                true
            }
        },
    )
}
