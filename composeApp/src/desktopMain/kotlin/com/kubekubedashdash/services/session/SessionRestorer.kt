package com.kubekubedashdash.services.session

import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceTab
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.WorkspaceManager
import com.kubekubedashdash.ui.screens.viewmodel.SessionViewModel
import com.kubekubedashdash.util.toPosition
import com.kubekubedashdash.util.toSize
import org.slf4j.LoggerFactory

/**
 * Applies a [RestorePlanner] plan to the live WorkspaceManager. Must run on
 * the UI thread (it is called from AppViewModel's main-dispatcher scope).
 * The first planned window reuses the bootstrap workspace, which already
 * carries the saved geometry; every other one becomes a new OS window.
 */
object SessionRestorer {
    private val log = LoggerFactory.getLogger(SessionRestorer::class.java)

    /** @return the number of cluster tabs opened. */
    fun apply(plan: List<PlannedWorkspace>): Int {
        var opened = 0
        plan.forEachIndexed { index, planned ->
            val workspace = if (index == 0) {
                WorkspaceManager.workspaces.value.firstOrNull() ?: WorkspaceManager.addWorkspace(Workspace())
            } else {
                WorkspaceManager.addWorkspace(
                    Workspace(
                        initialPosition = planned.geometry?.toPosition(),
                        initialSize = planned.geometry?.toSize(),
                        initialMaximized = planned.geometry?.maximized ?: false,
                        initialGeometry = planned.geometry,
                    ),
                )
            }
            planned.tabs.forEachIndexed { i, tab ->
                val target = if (i == 0) OpenTarget.CURRENT_VIEW else OpenTarget.NEW_TAB
                WorkspaceManager.openCluster(
                    workspace,
                    tab.context,
                    target,
                    restore = SessionViewModel.RestoreTarget(tab.namespace, tab.screen, tab.paneWidthDp),
                )
                opened++
            }
            val clusterTabs = workspace.tabs.value.filterIsInstance<WorkspaceTab.Cluster>()
            clusterTabs.getOrNull(planned.activeTab)?.let { workspace.setActive(it.key) }
        }
        if (opened > 0) log.info("Restored {} cluster tab(s) across {} window(s)", opened, plan.size)
        return opened
    }
}
