package com.kubekubedashdash.services

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.model.ClusterSession
import com.kubekubedashdash.model.SessionId
import com.kubekubedashdash.model.Workspace
import com.kubekubedashdash.model.WorkspaceId
import com.kubekubedashdash.model.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where a freshly-picked cluster lands when the user confirms it from the
 * cluster selector. Wired to per-row action buttons in
 * [com.kubekubedashdash.ui.modals.ClusterSelectorModal].
 *
 * - [CURRENT_VIEW]: replace the active session's connection in this window.
 * - [NEW_TAB]: append a new session to this window's workspace.
 * - [NEW_WINDOW]: spawn a new window/workspace pre-connected to this cluster.
 */
enum class OpenTarget { CURRENT_VIEW, NEW_TAB, NEW_WINDOW }

/**
 * Process-wide coordinator for [Workspace]s (one per OS window) and the
 * [ClusterSession]s they contain (one per cluster tab).
 *
 * Phase 3: bootstraps with one workspace + one (initially-disconnected) session.
 * Supports all three [OpenTarget]s and propagates "close last tab → close
 * window" / "close last window → exit app" per Decision 2 of
 * `.docs/multi-cluster-plan.md`. The exit-app step happens in
 * [com.kubekubedashdash.Main]'s `application` block when [workspaces] goes
 * empty — there is no `exitApplication` call from this object directly,
 * keeping the lifecycle decision in Compose's scope.
 */
object WorkspaceManager {
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    /**
     * The workspace whose drop zone currently sits under the cursor mid-drag,
     * or null when no other window is being targeted (or no drag is in
     * progress). Each window's [com.kubekubedashdash.ui.App] subscribes to this
     * to render the drag-over highlight on its own chip slot / tab strip.
     *
     * Updated by [notifyDragMove] as the source chip's screen position changes,
     * and cleared at drag end (either through [handleChipRelease] or
     * [cancelDrag]). Source workspace is excluded from the search so dropping
     * back over the source's own zone is a no-op rather than a self-merge.
     */
    private val _dragTarget = MutableStateFlow<WorkspaceId?>(null)
    val dragTarget: StateFlow<WorkspaceId?> = _dragTarget.asStateFlow()

    init {
        val workspace = Workspace()
        workspace.addSession(ClusterSession(id = SessionId.new()), makeActive = true)
        _workspaces.value = listOf(workspace)
    }

    /**
     * The active session of the first workspace. Used only by the legacy
     * [KubeClientService] facade — new code should reach the active session via
     * `LocalViewModelStoreOwner` and the per-screen ViewModel, which is correctly
     * scoped to the user's currently-focused window/tab.
     */
    val activeSession: ClusterSession
        get() = _workspaces.value.firstOrNull()?.activeSession
            ?: error("No active session — bootstrap should always create one")

    fun workspaceById(id: WorkspaceId): Workspace? = _workspaces.value.firstOrNull { it.id == id }

    /**
     * Apply a cluster pick from the modal. Mutates state according to [target]:
     * replace the active session of [workspace], append a new session, or spawn
     * a fresh workspace (= new window) pre-connected to [ctx].
     *
     * If [workspace] has no active session yet (the bootstrap state right after
     * app launch), [NEW_TAB] is downgraded to [CURRENT_VIEW] so the user doesn't
     * end up with two pending sessions.
     */
    fun openCluster(workspace: Workspace, ctx: String, target: OpenTarget) {
        when (target) {
            OpenTarget.CURRENT_VIEW -> {
                val session = workspace.activeSession ?: ClusterSession().also {
                    workspace.addSession(it, makeActive = true)
                }
                session.viewModel.connectToCluster(ctx)
            }

            OpenTarget.NEW_TAB -> {
                val active = workspace.activeSession
                if (active == null || active.viewModel.selectedContext.value.isBlank()) {
                    // Bootstrap state: no real "current" view to add to. Just
                    // connect the existing empty session in place.
                    openCluster(workspace, ctx, OpenTarget.CURRENT_VIEW)
                    return
                }
                val session = ClusterSession()
                workspace.addSession(session, makeActive = true)
                session.viewModel.connectToCluster(ctx)
            }

            OpenTarget.NEW_WINDOW -> {
                val newWorkspace = Workspace()
                val session = ClusterSession()
                newWorkspace.addSession(session, makeActive = true)
                _workspaces.update { it + newWorkspace }
                session.viewModel.connectToCluster(ctx)
            }
        }
        reconcileAllClustersTab()
    }

    /**
     * Close a tab by its key. Cluster tabs dispose their session; the Logs and
     * AllClusters tabs have no resources to free. Empty workspace cascades to
     * window close per Decision 2 of `.docs/multi-cluster-plan.md`.
     */
    fun closeTab(workspace: Workspace, tabKey: String) {
        val behavior = PreferenceRepository.closeTabFocus.value
        when (val removed = workspace.removeTab(tabKey, behavior)) {
            is WorkspaceTab.Cluster -> removed.session.close()
            WorkspaceTab.Logs, WorkspaceTab.AllClusters, null -> { /* no resources to free */ }
        }
        if (workspace.tabs.value.isEmpty()) {
            closeWorkspace(workspace.id)
        }
        reconcileAllClustersTab()
    }

    /**
     * Close a session in [workspace] and dispose its connection + ViewModels.
     * If this empties [workspace], the workspace is closed too (Decision 2 — no
     * empty windows). Closing the last workspace propagates up via the
     * [workspaces] flow, which `Main.kt` watches to call `exitApplication`.
     */
    fun closeSession(workspace: Workspace, sessionId: SessionId) {
        val key = "cluster:${sessionId.value}"
        closeTab(workspace, key)
        LogStreamRegistry.closeAllForSession(sessionId)
    }

    /** Open or focus the Logs tab in [workspace]. Singleton. */
    fun openLogsTab(workspace: Workspace) {
        workspace.openLogsTab()
    }

    /**
     * Called by any tab's drag handler (including Logs) each time the cursor
     * moves past the drag threshold. Updates [dragTarget] exactly like
     * [notifyDragMove], but keyed by tab key rather than [SessionId].
     */
    fun notifyDragMoveTab(tabKey: String, screenX: Int, screenY: Int) {
        val source = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it.key == tabKey }
        } ?: run {
            _dragTarget.value = null
            return
        }
        _dragTarget.value = findDropTargetWorkspace(screenX, screenY, exclude = source.id)
    }

    /**
     * Drag-end dispatcher for any tab type (cluster, Logs, or AllClusters). Mirrors
     * [handleChipRelease] but works with any [WorkspaceTab]. Merge drops
     * are always allowed; tear-out is refused when the source has only one
     * tab (same rule as [tearOutSession]).
     */
    fun handleTabRelease(tabKey: String, screenX: Int, screenY: Int) {
        _dragTarget.value = null
        val source = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it.key == tabKey }
        } ?: return
        val pt = Offset(screenX.toFloat(), screenY.toFloat())
        if (source.dropZoneScreenBounds.value?.contains(pt) == true) return
        val targetId = findDropTargetWorkspace(screenX, screenY, exclude = source.id)
        if (targetId != null) {
            val target = workspaceById(targetId) ?: return
            val tab = source.removeTab(tabKey) ?: return
            when (tab) {
                is WorkspaceTab.Logs -> target.openLogsTab()
                is WorkspaceTab.Cluster -> target.addSession(tab.session, makeActive = true)
                WorkspaceTab.AllClusters -> target.ensureAllClustersTabAt(0)
            }
            if (source.tabs.value.isEmpty()) closeWorkspace(source.id)
        } else if (source.tabs.value.size > 1) {
            val tab = source.removeTab(tabKey) ?: return
            val newWorkspace = Workspace(
                initialPosition = WindowPosition.Absolute(screenX.dp, screenY.dp),
            )
            when (tab) {
                is WorkspaceTab.Logs -> newWorkspace.openLogsTab()
                is WorkspaceTab.Cluster -> newWorkspace.addSession(tab.session, makeActive = true)
                WorkspaceTab.AllClusters -> newWorkspace.ensureAllClustersTabAt(0)
            }
            _workspaces.update { it + newWorkspace }
            if (source.tabs.value.isEmpty()) closeWorkspace(source.id)
        }
        // single-tab window dropped over empty space → no-op, use title bar to move
        reconcileAllClustersTab()
    }

    /**
     * Close a workspace and dispose every cluster session it still holds. Intended
     * both as the cascade target for [closeTab] when the last tab leaves and as
     * the OS-window-close handler.
     */
    fun closeWorkspace(workspaceId: WorkspaceId) {
        val workspace = _workspaces.value.firstOrNull { it.id == workspaceId } ?: return
        workspace.tabs.value.filterIsInstance<WorkspaceTab.Cluster>().forEach { it.session.close() }
        _workspaces.update { list -> list.filterNot { it.id == workspaceId } }
        reconcileAllClustersTab()
    }

    /**
     * Move [sessionId] out of its current workspace into a brand-new workspace
     * positioned at the given screen coordinates. The session keeps its
     * connection, informers, and ViewModelStore — only the parent workspace
     * changes, so the user's place in the cluster is preserved across the
     * tear-out. If the source workspace empties as a result, it is closed.
     *
     * Returns the new workspace's id, or `null` if [sessionId] could not be
     * located.
     */
    fun tearOutSession(sessionId: SessionId, atScreenX: Int, atScreenY: Int): WorkspaceId? {
        val source = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it is WorkspaceTab.Cluster && it.session.id == sessionId }
        } ?: return null
        if (source.tabs.value.size == 1) {
            // This chip is the window's only tab — tear-out would leave nothing.
            // Move the window via the title bar drag region instead.
            return null
        }
        val session = source.removeSession(sessionId) ?: return null
        val newWorkspace = Workspace(
            initialPosition = WindowPosition.Absolute(atScreenX.dp, atScreenY.dp),
        )
        newWorkspace.addSession(session, makeActive = true)
        _workspaces.update { it + newWorkspace }
        if (source.tabs.value.isEmpty()) {
            closeWorkspace(source.id)
        }
        reconcileAllClustersTab()
        return newWorkspace.id
    }

    /**
     * Activate the cluster tab for [sessionId] in whichever workspace currently
     * holds it. If that workspace's window is in the background or minimized,
     * raise it to the front so the user actually sees the activation.
     *
     * Returns true if a matching session was found and activated.
     */
    fun activateClusterTab(sessionId: SessionId): Boolean {
        val workspace = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it is WorkspaceTab.Cluster && it.session.id == sessionId }
        } ?: return false
        val tabKey = "cluster:${sessionId.value}"
        workspace.setActive(tabKey)
        workspace.awtWindow?.let { win ->
            (win as? java.awt.Frame)?.let { frame ->
                if ((frame.extendedState and java.awt.Frame.ICONIFIED) != 0) {
                    frame.extendedState = frame.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
            }
            win.toFront()
            win.requestFocus()
        }
        return true
    }

    /**
     * Move [sessionId] from its current workspace into [toWorkspaceId], appending
     * it to the destination's tab list and making it active. The session keeps
     * its connection, informers, and ViewModelStore, so the user's place in the
     * cluster is preserved across the merge. If the source workspace empties as
     * a result, it is closed (Decision 2 of `.docs/multi-cluster-plan.md`),
     * which can in turn cascade to app exit if it was the last workspace.
     *
     * No-op if source and destination are the same workspace, or if either id
     * cannot be resolved. Returns true on success.
     */
    fun moveSession(sessionId: SessionId, toWorkspaceId: WorkspaceId): Boolean {
        val source = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it is WorkspaceTab.Cluster && it.session.id == sessionId }
        } ?: return false
        if (source.id == toWorkspaceId) return false
        val target = workspaceById(toWorkspaceId) ?: return false
        val session = source.removeSession(sessionId) ?: return false
        target.addSession(session, makeActive = true)
        if (source.tabs.value.isEmpty()) {
            closeWorkspace(source.id)
        }
        reconcileAllClustersTab()
        return true
    }

    /**
     * Called by the source chip's drag handler each time the cursor moves past
     * the drag threshold. Updates [dragTarget] so other windows can highlight
     * their drop zone when the cursor is over it. Excludes the source workspace
     * so dragging within the source window's own area shows no highlight (and
     * is ultimately a no-op cancellation).
     */
    fun notifyDragMove(sessionId: SessionId, screenX: Int, screenY: Int) {
        val source = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it is WorkspaceTab.Cluster && it.session.id == sessionId }
        } ?: run {
            _dragTarget.value = null
            return
        }
        _dragTarget.value = findDropTargetWorkspace(screenX, screenY, exclude = source.id)
    }

    /**
     * Drag-end dispatcher for the cluster chip. Decides between four outcomes:
     *
     * - Cursor over the **source's own** drop zone → no-op cancellation
     *   (Decision 5.6 of `.docs/multi-cluster-plan.md` — keeps a chip wiggled
     *   a few px and released on its own row from spawning a redundant window).
     * - Cursor over **another** workspace's drop zone → merge via [moveSession].
     * - Cursor over empty space, source has only one tab → no-op
     *   (tear-out is refused at N=1; see [tearOutSession]).
     * - Cursor over empty space, source has ≥2 tabs → tear-out into a
     *   fresh window at the cursor.
     *
     * Always clears [dragTarget]. Idempotent on repeat calls.
     */
    fun handleChipRelease(sessionId: SessionId, screenX: Int, screenY: Int) {
        _dragTarget.value = null
        val source = _workspaces.value.firstOrNull { ws ->
            ws.tabs.value.any { it is WorkspaceTab.Cluster && it.session.id == sessionId }
        } ?: return
        val pt = Offset(screenX.toFloat(), screenY.toFloat())
        if (source.dropZoneScreenBounds.value?.contains(pt) == true) {
            // Released over our own chip / tab strip — treat as cancellation.
            return
        }
        val target = findDropTargetWorkspace(screenX, screenY, exclude = source.id)
        if (target != null) {
            moveSession(sessionId, target)
        } else {
            tearOutSession(sessionId, screenX, screenY)
        }
    }

    /**
     * Clear [dragTarget] without committing a drop. Called when the chip's drag
     * gesture is cancelled (e.g. the user releases before crossing the drag
     * threshold) so any drag-over highlight on other windows fades out.
     */
    fun cancelDrag() {
        _dragTarget.value = null
    }

    /**
     * Ensure the singleton AllClusters tab is present in exactly one workspace
     * when totalClusters ≥ 2, and absent from all workspaces otherwise.
     *
     * Re-entrancy guard prevents infinite recursion when empty-workspace cleanup
     * inside this method calls [closeWorkspace], which also calls this function.
     */
    @Volatile private var reconciling = false

    private fun reconcileAllClustersTab() {
        if (reconciling) return
        reconciling = true
        try {
            val totalClusters = _workspaces.value.sumOf { ws ->
                ws.tabs.value.count { it is WorkspaceTab.Cluster }
            }
            if (totalClusters >= 2) {
                val already = _workspaces.value.firstOrNull { ws ->
                    ws.tabs.value.any { it is WorkspaceTab.AllClusters }
                }
                if (already == null) {
                    _workspaces.value.first().ensureAllClustersTabAt(0)
                }
            } else {
                _workspaces.value.forEach { it.removeAllClustersTab() }
                // Close any workspaces that only held AllClusters and are now empty
                _workspaces.value
                    .filter { it.tabs.value.isEmpty() }
                    .forEach { ws -> closeWorkspace(ws.id) }
            }
        } finally {
            reconciling = false
        }
    }

    private fun findDropTargetWorkspace(
        screenX: Int,
        screenY: Int,
        exclude: WorkspaceId,
    ): WorkspaceId? {
        val pt = Offset(screenX.toFloat(), screenY.toFloat())
        return _workspaces.value
            .firstOrNull { ws ->
                ws.id != exclude && ws.dropZoneScreenBounds.value?.contains(pt) == true
            }
            ?.id
    }
}
