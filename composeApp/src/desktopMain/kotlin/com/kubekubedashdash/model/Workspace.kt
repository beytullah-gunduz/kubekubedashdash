package com.kubekubedashdash.model

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import com.kubekubedashdash.services.OpenTarget
import com.kubekubedashdash.services.portforward.PortForwardRequest
import com.kubekubedashdash.ui.screens.viewmodel.NavigationHistoryHost
import com.kubekubedashdash.ui.screens.viewmodel.SessionViewModel
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

/** A request to open the logs drawer on a specific pod/container (screenshot driver). */
data class LogRequest(
    val podName: String,
    val namespace: String,
    val container: String? = null,
)

/**
 * One OS window's worth of tabs. A workspace holds an ordered list of
 * [WorkspaceTab]s (rendered in the window's title bar) and tracks which one is
 * currently active. It also keeps the window's one Back/Forward history across
 * those tabs ([navigationHistory]).
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
    /** Restored floating window size; null = the app default. */
    val initialSize: DpSize? = null,
    /** Restored maximized state. */
    val initialMaximized: Boolean = false,
    /** Restored geometry, seeding [geometry] so a window that opens maximized still knows its floating size. */
    initialGeometry: WindowGeometry? = null,
) {
    /**
     * Live geometry of the OS window hosting this workspace, mirrored in by
     * Main.kt so session persistence can save it. Seeded from the restored
     * geometry; updated on every move/resize once the window has laid out.
     */
    private val _geometry = MutableStateFlow(initialGeometry)
    val geometry: StateFlow<WindowGeometry?> = _geometry.asStateFlow()

    fun updateGeometry(value: WindowGeometry) {
        _geometry.value = value
    }

    private val _tabs = MutableStateFlow<List<WorkspaceTab>>(emptyList())
    val tabs: StateFlow<List<WorkspaceTab>> = _tabs.asStateFlow()

    private val _activeTabKey = MutableStateFlow<String?>(null)
    val activeTabKey: StateFlow<String?> = _activeTabKey.asStateFlow()

    private val historyLock = Any()
    private val activationHistory = ArrayDeque<SessionId>()
    private val historyCapacity = 16

    // One chronological Back/Forward history across this window's tabs (not the
    // close-focus [activationHistory] above).
    private val navigation = WindowHistory()
    val navigationHistory: StateFlow<NavigationHistoryState> = navigation.state

    // How this window's cluster sessions report navigations. A private object:
    // Workspace is public and the hook interface is internal.
    private val sessionHistoryHost = object : NavigationHistoryHost {
        override fun beforeNavigate(session: SessionViewModel) {
            val key = tabKeyOf(session) ?: return
            // A background tab changes nothing the user sees.
            if (key != _activeTabKey.value) return
            val snapshot = session.historySnapshot()
            navigation.recordNavigation(
                snapshot?.let { (screen, pane) -> HistoryLocation(key, session.selectedContext.value, screen, pane) },
            )
        }

        override fun contextReplaced(session: SessionViewModel, context: String) {
            val key = tabKeyOf(session) ?: return
            navigation.prune { it.tabKey == key && it.screen != null && it.context != context }
        }
    }

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

    private val _showGkeDiscovery = MutableStateFlow(false)
    val showGkeDiscovery: StateFlow<Boolean> = _showGkeDiscovery.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    // Command palette visibility — hoisted so the screenshot generator can open it
    // (App.kt owns the keyboard toggle; this lets an external driver request it too).
    private val _showPalette = MutableStateFlow(false)
    val showPalette: StateFlow<Boolean> = _showPalette.asStateFlow()

    // One-shot "open the logs drawer on this pod" request, consumed by App.kt.
    // Null in normal use. Carries the same args App.kt's onOpenLogs already takes.
    private val _logRequest = MutableStateFlow<LogRequest?>(null)
    val logRequest: StateFlow<LogRequest?> = _logRequest.asStateFlow()

    // One-shot "hide the logs drawer" request, consumed by App.kt. False in normal use.
    private val _hideLogsRequest = MutableStateFlow(false)
    val hideLogsRequest: StateFlow<Boolean> = _hideLogsRequest.asStateFlow()

    // One-shot "tail this namespace" request (screenshot driver), consumed by App.kt. Null in normal use.
    private val _tailRequest = MutableStateFlow<String?>(null)
    val tailRequest: StateFlow<String?> = _tailRequest.asStateFlow()

    // One-shot "open the port-forward dialog" request (screenshot driver), consumed by App.kt.
    private val _portForwardRequest = MutableStateFlow<PortForwardRequest?>(null)
    val portForwardRequest: StateFlow<PortForwardRequest?> = _portForwardRequest.asStateFlow()

    // One-shot "close the port-forward dialog" request (screenshot teardown). False in normal use.
    private val _dismissPortForwardRequest = MutableStateFlow(false)
    val dismissPortForwardRequest: StateFlow<Boolean> = _dismissPortForwardRequest.asStateFlow()

    /**
     * Screen-space rectangle of this window's chip-drop zone — the title bar,
     * which holds the [com.kubekubedashdash.ui.WindowTabStrip]. Updated by the
     * corresponding composable via `onGloballyPositioned`
     * (see [com.kubekubedashdash.ui.App]) and queried by
     * [com.kubekubedashdash.services.WorkspaceManager.handleChipRelease] to hit-
     * test the cursor at drag end and decide between chip-on-chip merge and
     * tear-out. Null while the layout is being measured for the first time or
     * after the corresponding composable detaches.
     */
    private val _dropZoneScreenBounds = MutableStateFlow<Rect?>(null)
    val dropZoneScreenBounds: StateFlow<Rect?> = _dropZoneScreenBounds.asStateFlow()

    /**
     * AWT window backing this workspace. Set from [com.kubekubedashdash.ui.App]
     * via `DisposableEffect` and used by [com.kubekubedashdash.services.WorkspaceManager.activateClusterTab]
     * to bring the window to front when navigating to a cluster tab from another window.
     * Null while the window has not yet attached or after it has been destroyed.
     */
    @Volatile var awtWindow: java.awt.Window? = null

    /** Snapshot accessor — the active cluster session at this instant, or null if empty or a non-cluster tab is active. */
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
        synchronized(historyLock) {
            if (activationHistory.lastOrNull() == session.id) return
            activationHistory.addLast(session.id)
            while (activationHistory.size > historyCapacity) {
                activationHistory.removeFirst()
            }
        }
    }

    internal fun addTab(tab: WorkspaceTab, makeActive: Boolean = true) {
        _tabs.value = _tabs.value + tab
        (tab as? WorkspaceTab.Cluster)?.session?.viewModel?.historyHost = sessionHistoryHost
        if (makeActive) switchTo(tab.key)
    }

    internal fun removeTab(
        key: String,
        behavior: CloseTabFocus = CloseTabFocus.LEFT_NEIGHBOR,
    ): WorkspaceTab? {
        val tab = _tabs.value.firstOrNull { it.key == key } ?: return null
        val closedIndex = _tabs.value.indexOf(tab)
        val newList = _tabs.value.filterNot { it.key == key }
        if (tab is WorkspaceTab.Cluster) {
            synchronized(historyLock) {
                activationHistory.removeAll { it == tab.session.id }
            }
        }
        (tab as? WorkspaceTab.Cluster)?.session?.viewModel?.let { vm ->
            if (vm.historyHost === sessionHistoryHost) vm.historyHost = null
        }
        // A closed (or moved) tab cannot be gone back to.
        navigation.prune { it.tabKey == key }
        val newActive = if (_activeTabKey.value == key) computeNewActiveKey(closedIndex, newList, behavior) else _activeTabKey.value
        if (newActive != _activeTabKey.value) _activeTabKey.value = newActive
        _tabs.value = newList
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
                val recoveredId = synchronized(historyLock) { activationHistory.lastOrNull { it in livingIds } }
                val recoveredKey = recoveredId?.let { id ->
                    newList.firstOrNull { it is WorkspaceTab.Cluster && it.session.id == id }?.key
                }
                recoveredKey ?: newList.getOrNull((closedIndex - 1).coerceAtLeast(0))?.key
            }
        }
    }

    internal fun setActive(key: String) {
        if (key == _activeTabKey.value) return
        if (_tabs.value.any { it.key == key }) switchTo(key)
    }

    /** A user switch to [key]: recorded in the window history and the close-focus activation history. */
    private fun switchTo(key: String) {
        navigation.beforeTabSwitch(locationOf(_activeTabKey.value))
        pushHistory(_activeTabKey.value)
        _activeTabKey.value = key
        navigation.afterTabSwitch(locationOf(key))
    }

    /** Back through this window's history; may switch tabs. Waits while the active tab is on a connection screen. */
    fun goBack() {
        if (activeSession?.viewModel?.canNavigateHistory() == false) return
        val active = _activeTabKey.value
        val target = navigation.back(locationOf(active)) { usable(it, active) } ?: return
        show(target)
    }

    /** Forward through this window's history; may switch tabs. Waits like [goBack]. */
    fun goForward() {
        if (activeSession?.viewModel?.canNavigateHistory() == false) return
        val active = _activeTabKey.value
        val target = navigation.forward(locationOf(active)) { usable(it, active) } ?: return
        show(target)
    }

    /** Session restore opens the saved tabs one by one; that is not history the user made. */
    internal fun clearNavigationHistory() = navigation.clear()

    private fun tabKeyOf(session: SessionViewModel): String? = _tabs.value.firstOrNull { it is WorkspaceTab.Cluster && it.session.viewModel === session }?.key

    /** Where [key]'s tab is now; a cluster tab on a connection screen has no screen. */
    private fun locationOf(key: String?): HistoryLocation? {
        if (key == null) return null
        return when (val tab = _tabs.value.firstOrNull { it.key == key }) {
            null -> null

            is WorkspaceTab.Cluster -> {
                val vm = tab.session.viewModel
                val snapshot = vm.historySnapshot()
                HistoryLocation(key, vm.selectedContext.value, snapshot?.first, snapshot?.second)
            }

            else -> HistoryLocation(key)
        }
    }

    // Still worth landing on: its tab is open, a cluster entry is for the cluster
    // the tab shows now, and a bare "that tab" entry is not the active tab itself.
    private fun usable(entry: HistoryLocation, active: String?): Boolean = when (val tab = _tabs.value.firstOrNull { it.key == entry.tabKey }) {
        null -> false
        is WorkspaceTab.Cluster -> if (entry.screen == null) entry.tabKey != active else tab.session.viewModel.selectedContext.value == entry.context
        else -> entry.tabKey != active
    }

    private fun show(entry: HistoryLocation) {
        if (_activeTabKey.value != entry.tabKey) {
            pushHistory(_activeTabKey.value)
            _activeTabKey.value = entry.tabKey
        }
        val screen = entry.screen ?: return
        val tab = _tabs.value.firstOrNull { it.key == entry.tabKey } as? WorkspaceTab.Cluster ?: return
        tab.session.viewModel.showHistoryEntry(screen, entry.extraPane)
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
        navigation.prune { it.tabKey == key }
        if (_activeTabKey.value == key) {
            _activeTabKey.value = _tabs.value.firstOrNull()?.key
        }
    }

    /**
     * Append a Terminal tab and activate it, or — if a tab with the same
     * [TerminalSession.id] already exists — focus that one. Each terminal is
     * scoped to a single container; opening "the same terminal again" should
     * land on the existing tab rather than spawn a duplicate.
     */
    fun openTerminalTab(session: TerminalSession) {
        val key = "terminal:${session.id.value}"
        val existing = _tabs.value.firstOrNull { it.key == key }
        if (existing != null) {
            setActive(existing.key)
        } else {
            addTab(WorkspaceTab.Terminal(session), makeActive = true)
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

    fun showGkeDiscovery() {
        _showGkeDiscovery.value = true
    }

    fun dismissGkeDiscovery() {
        _showGkeDiscovery.value = false
    }

    fun showSettings() {
        _showSettings.value = true
    }

    fun dismissSettings() {
        _showSettings.value = false
    }

    fun showPalette() {
        _showPalette.value = true
    }
    fun dismissPalette() {
        _showPalette.value = false
    }

    /** Ask App.kt to open the logs drawer for [podName]/[namespace]/[container]. */
    fun requestLogs(podName: String, namespace: String, container: String? = null) {
        _logRequest.value = LogRequest(podName, namespace, container)
    }

    /** Clear a consumed/pending log request. */
    fun clearLogRequest() {
        _logRequest.value = null
    }

    /** Ask App.kt to hide the logs drawer (screenshot teardown). */
    fun requestHideLogs() {
        _hideLogsRequest.value = true
    }

    /** Clear a consumed hide-logs request. */
    fun clearHideLogsRequest() {
        _hideLogsRequest.value = false
    }

    /** Ask App.kt to tail every running pod in [namespace] (screenshot driver). */
    fun requestTailLogs(namespace: String) {
        _tailRequest.value = namespace
    }

    /** Clear a consumed tail request. */
    fun clearTailRequest() {
        _tailRequest.value = null
    }

    /** Ask App.kt to open the port-forward dialog for [request] (screenshot driver). */
    fun requestPortForward(request: PortForwardRequest) {
        _portForwardRequest.value = request
    }

    /** Clear a consumed port-forward request. */
    fun clearPortForwardRequest() {
        _portForwardRequest.value = null
    }

    /** Ask App.kt to close the port-forward dialog (screenshot teardown). */
    fun requestDismissPortForward() {
        _dismissPortForwardRequest.value = true
    }

    /** Clear a consumed dismiss-port-forward request. */
    fun clearDismissPortForwardRequest() {
        _dismissPortForwardRequest.value = false
    }

    fun updateDropZoneScreenBounds(bounds: Rect?) {
        _dropZoneScreenBounds.value = bounds
    }
}
