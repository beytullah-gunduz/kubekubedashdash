package com.kubekubedashdash.services.session

import com.kubekubedashdash.Screen
import com.kubekubedashdash.model.WindowGeometry

/** A cluster tab as the live app sees it; [context] is blank for the bootstrap session. */
data class TabView(
    val context: String,
    val namespace: String,
    val screen: Screen,
    val paneWidthDp: Float,
)

/** One window: its cluster tabs in strip order, the active tab's index in that list (-1 if none), its geometry. */
data class WorkspaceView(
    val tabs: List<TabView>,
    val activeTabIndex: Int,
    val geometry: WindowGeometry?,
)

/**
 * Pure: live views -> saved snapshot. Drops tabs without a context (the
 * bootstrap session) and windows left with no tabs; keeps the active index
 * pointing at the same tab after drops, or -1 when it was a dropped or
 * non-cluster tab.
 */
object SessionSnapshotBuilder {
    fun build(workspaces: List<WorkspaceView>): SessionSnapshot {
        val saved = workspaces.mapNotNull { ws ->
            val kept = ws.tabs.withIndex().filter { it.value.context.isNotBlank() }
            if (kept.isEmpty()) return@mapNotNull null
            SavedWorkspace(
                tabs = kept.map { (_, tab) ->
                    SavedClusterTab(
                        context = tab.context,
                        namespace = tab.namespace,
                        screen = ScreenCodec.encode(tab.screen),
                        paneWidthDp = tab.paneWidthDp,
                    )
                },
                activeTab = kept.indexOfFirst { it.index == ws.activeTabIndex },
                geometry = ws.geometry,
            )
        }
        return SessionSnapshot(workspaces = saved)
    }
}
