package com.kubekubedashdash.model

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.window.WindowPosition
import com.kubekubedashdash.services.OpenTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What to focus after the active cluster tab is closed.
 *
 * Stored in [com.kubekubedashdash.data.repository.PreferenceRepository.closeTabFocus]
 * as the enum's `name`. Default ([LEFT_NEIGHBOR]) is applied when the preference key
 * is unset, so existing users picking up an updated build automatically get the new
 * behavior without touching Settings.
 */
enum class CloseTabFocus {
    /** Activate the leftmost remaining tab — legacy behavior. */
    FIRST,

    /** Activate the tab immediately left of the closed one, or
     *  the new index 0 if the closed tab was leftmost. New default. */
    LEFT_NEIGHBOR,

    /** Activate the most recently active session before the closed
     *  one. Falls back to [LEFT_NEIGHBOR] when the activation
     *  history has no usable entries. */
    PREVIOUS_ACTIVE,
}

/**
 * One OS window's worth of tabs. A workspace holds an ordered list of
 * [WorkspaceTab]s (rendered as a strip when N≥2 or any non-cluster tab is open)
 * and tracks which one is currently active.
 *
 * Per-window concerns also live here: the cluster-picker visibility flag is
 * scoped per window (Decision 1 in `.docs/multi-cluster-plan.md`) so two open
 * windows can independently show or hide their own pickers.
 *
 * [initialPosition] is the desired top-left position when this workspace's OS
 * window first opens. Set when [com.kubekubedashdash.services.WorkspaceManager.tearOutSession]
 * spawns a new window at the cursor; otherwise null and the window opens at
 * the platform default.
 */
class Workspace(
    val id: WorkspaceId = WorkspaceId.new(),
    val initialPosition: WindowPosition? = null,
) {
    private val _tabs = MutableStateFlow<List<WorkspaceTab>>(emptyList())
    val tabs: StateFlow<List<WorkspaceTab>> = _tabs.asStateFlow()

    private val _activeTabKey = MutableStateFlow<String?>(null)
    val activeTabKey: StateFlow<String?> = _activeTabKey.asStateFlow()

    private val activationHistory = ArrayDeque<SessionId>()
    private val historyCapacity = 16

    private val _showClusterSelector = MutableStateFlow(false)
    val showClusterSelector: StateFlow<Boolean> = _showClusterSelector.asStateFlow()

    /**
     * What [OpenTarget] a row-click in the cluster picker should resolve to.
     * Set when [showClusterSelector] is invoked: the sidebar's cluster header
     * leaves it at the default ([OpenTarget.CURRENT_VIEW] — replace the active
     * session) while the tab-strip's `+` button bumps it to
     * [OpenTarget.NEW_TAB] so picking a cluster appends instead of replacing.
     * Per-row icon buttons in the modal still let the user override this.
     */
    private val _clusterSelectorDefaultTarget = MutableStateFlow(OpenTarget.CURRENT_VIEW)
    val clusterSelectorDefaultTarget: StateFlow<OpenTarget> = _clusterSelectorDefaultTarget.asStateFlow()

    private val _showEksDiscovery = MutableStateFlow(false)
    val showEksDiscovery: StateFlow<Boolean> = _showEksDiscovery.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    /**
     * Screen-space rectangle of this window's chip-drop zone — the chip slot in
     * the title bar at N=1 or the [com.kubekubedashdash.ui.WindowTabStrip] row
     * at N≥2. Updated by the corresponding composable via `onGloballyPositioned`
     * (see [com.kubekubedashdash.ui.App]) and queried by
     * [com.kubekubedashdash.services.WorkspaceManager.handleChipRelease] to hit-
     * test the cursor at drag end and decide between chip-on-chip merge and
     * tear-out. Null while the layout is being measured for the first time or
     * after the corresponding composable detaches.
     */
    private val _dropZoneScreenBounds = MutableStateFlow<Rect?>(null)
    val dropZoneScreenBounds: StateFlow<Rect?> = _dropZoneScreenBounds.asStateFlow()

    /** Snapshot accessor — the active cluster session at this instant, or null if empty or Logs tab active. */
    val activeSession: ClusterSession?
        get() {
            val key = _activeTabKey.value ?: return null
            val tab = _tabs.value.firstOrNull { it.key == key } ?: return null
            return (tab as? WorkspaceTab.Cluster)?.session
        }

    private fun pushHistory(key: String?) {
        if (key == null) return
        val tab = _tabs.value.firstOrNull { it.key == key } ?: return
        val session = (tab as? WorkspaceTab.Cluster)?.session ?: return
        if (activationHistory.lastOrNull() == session.id) return
        activationHistory.addLast(session.id)
        while (activationHistory.size > historyCapacity) {
            activationHistory.removeFirst()
        }
    }

    internal fun addTab(tab: WorkspaceTab, makeActive: Boolean = true) {
        _tabs.value = _tabs.value + tab
        if (makeActive) {
            pushHistory(_activeTabKey.value)
            _activeTabKey.value = tab.key
        }
    }

    internal fun removeTab(
        key: String,
        behavior: CloseTabFocus = CloseTabFocus.LEFT_NEIGHBOR,
    ): WorkspaceTab? {
        val tab = _tabs.value.firstOrNull { it.key == key } ?: return null
        val closedIndex = _tabs.value.indexOf(tab)
        val newList = _tabs.value.filterNot { it.key == key }
        if (tab is WorkspaceTab.Cluster) {
            activationHistory.removeAll { it == tab.session.id }
        }
        _tabs.value = newList
        if (_activeTabKey.value == key) {
            _activeTabKey.value = computeNewActiveKey(closedIndex, newList, behavior)
        }
        return tab
    }

    private fun computeNewActiveKey(
        closedIndex: Int,
        newList: List<WorkspaceTab>,
        behavior: CloseTabFocus,
    ): String? {
        if (newList.isEmpty()) return null
        return when (behavior) {
            CloseTabFocus.FIRST -> newList.first().key

            CloseTabFocus.LEFT_NEIGHBOR ->
                newList.getOrNull((closedIndex - 1).coerceAtLeast(0))?.key

            CloseTabFocus.PREVIOUS_ACTIVE -> {
                val clusterTabs = newList.filterIsInstance<WorkspaceTab.Cluster>()
                val livingIds = clusterTabs.mapTo(HashSet(clusterTabs.size)) { it.session.id }
                val recoveredId = activationHistory.lastOrNull { it in livingIds }
                val recoveredKey = recoveredId?.let { id ->
                    newList.firstOrNull { it is WorkspaceTab.Cluster && it.session.id == id }?.key
                }
                recoveredKey ?: newList.getOrNull((closedIndex - 1).coerceAtLeast(0))?.key
            }
        }
    }

    internal fun setActive(key: String) {
        if (_tabs.value.any { it.key == key }) {
            pushHistory(_activeTabKey.value)
            _activeTabKey.value = key
        }
    }

    /** Convenience for callers that still think in [SessionId]. */
    internal fun addSession(session: ClusterSession, makeActive: Boolean = true) {
        addTab(WorkspaceTab.Cluster(session), makeActive)
    }

    /** Convenience for callers that still think in [SessionId]. */
    internal fun removeSession(
        id: SessionId,
        behavior: CloseTabFocus = CloseTabFocus.LEFT_NEIGHBOR,
    ): ClusterSession? {
        val key = "cluster:${id.value}"
        val tab = removeTab(key, behavior) ?: return null
        return (tab as? WorkspaceTab.Cluster)?.session
    }

    /**
     * Insert an AllClusters tab at [index] (clamped to [0, size]) if one is not
     * already present. Does NOT change the active tab — spec: not auto-activated.
     */
    internal fun ensureAllClustersTabAt(index: Int = 0) {
        if (_tabs.value.any { it is WorkspaceTab.AllClusters }) return
        val clamped = index.coerceIn(0, _tabs.value.size)
        _tabs.value = _tabs.value.toMutableList().also { it.add(clamped, WorkspaceTab.AllClusters) }
    }

    /** Remove the AllClusters tab if present. Activates the next tab if it was active. */
    internal fun removeAllClustersTab() {
        val key = WorkspaceTab.AllClusters.key
        _tabs.value = _tabs.value.filterNot { it.key == key }
        if (_activeTabKey.value == key) {
            _activeTabKey.value = _tabs.value.firstOrNull()?.key
        }
    }

    /**
     * Append a Logs tab and activate it, or — if a Logs tab already exists —
     * just activate the existing one. Singleton: at most one Logs tab per window.
     */
    fun openLogsTab() {
        val existing = _tabs.value.firstOrNull { it is WorkspaceTab.Logs }
        if (existing != null) {
            _activeTabKey.value = existing.key
        } else {
            addTab(WorkspaceTab.Logs, makeActive = true)
        }
    }

    fun showClusterSelector(defaultTarget: OpenTarget = OpenTarget.CURRENT_VIEW) {
        _clusterSelectorDefaultTarget.value = defaultTarget
        _showClusterSelector.value = true
    }

    fun dismissClusterSelector() {
        _showClusterSelector.value = false
    }

    fun showEksDiscovery() {
        _showEksDiscovery.value = true
    }

    fun dismissEksDiscovery() {
        _showEksDiscovery.value = false
    }

    fun showSettings() {
        _showSettings.value = true
    }

    fun dismissSettings() {
        _showSettings.value = false
    }

    fun updateDropZoneScreenBounds(bounds: Rect?) {
        _dropZoneScreenBounds.value = bounds
    }
}
