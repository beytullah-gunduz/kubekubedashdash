package com.kubekubedashdash.services.session

import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.ScreenBounds
import com.kubekubedashdash.model.WindowGeometry
import com.kubekubedashdash.util.DemoContext

data class PlannedTab(
    val context: String,
    val namespace: String,
    val screen: Screen.Main,
    val paneWidthDp: Float?,
)

data class PlannedWorkspace(
    val tabs: List<PlannedTab>,
    /** Index into [tabs]; always valid. */
    val activeTab: Int,
    val geometry: WindowGeometry?,
)

/**
 * Pure: saved snapshot -> what to open. Drops tabs whose context is no
 * longer in the kubeconfig (demo contexts are always restorable), drops
 * windows left with no tabs, clamps the pane width to the session's own
 * range, keeps the active tab if it survived (else the first), and forgets a
 * window position that lies on no attached screen (size is kept). An empty
 * [screens] list means "unknown", which keeps positions as saved.
 */
object RestorePlanner {
    const val MIN_PANE_DP = 400f
    const val MAX_PANE_DP = 1200f

    fun plan(
        snapshot: SessionSnapshot?,
        availableContexts: Collection<String>,
        screens: List<ScreenBounds>,
    ): List<PlannedWorkspace> {
        if (snapshot == null) return emptyList()
        return snapshot.workspaces.mapNotNull { ws ->
            val kept = ws.tabs.withIndex().filter { (_, tab) ->
                tab.context.isNotBlank() && (DemoContext.isMockContext(tab.context) || tab.context in availableContexts)
            }
            if (kept.isEmpty()) return@mapNotNull null
            val active = kept.indexOfFirst { it.index == ws.activeTab }.coerceAtLeast(0)
            PlannedWorkspace(
                tabs = kept.map { (_, tab) ->
                    PlannedTab(
                        context = tab.context,
                        namespace = tab.namespace.ifBlank { SavedClusterTab.ALL_NAMESPACES },
                        screen = ScreenCodec.decode(tab.screen),
                        paneWidthDp = tab.paneWidthDp?.coerceIn(MIN_PANE_DP, MAX_PANE_DP),
                    )
                },
                activeTab = active,
                geometry = ws.geometry?.let { g ->
                    if (screens.isEmpty() || g.isVisibleOn(screens)) g else g.copy(x = null, y = null)
                },
            )
        }
    }
}
