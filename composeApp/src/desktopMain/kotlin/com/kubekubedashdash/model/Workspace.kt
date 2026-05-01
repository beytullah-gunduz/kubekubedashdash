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
 * One OS window's worth of cluster sessions. A workspace holds an ordered list of
 * [ClusterSession]s (rendered as tabs when N≥2) and tracks which one is currently
 * active.
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
    private val _sessions = MutableStateFlow<List<ClusterSession>>(emptyList())
    val sessions: StateFlow<List<ClusterSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<SessionId?>(null)
    val activeSessionId: StateFlow<SessionId?> = _activeSessionId.asStateFlow()

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

    /** Snapshot accessor — the active session at this instant, or null if empty. */
    val activeSession: ClusterSession?
        get() = _sessions.value.firstOrNull { it.id == _activeSessionId.value }

    private fun pushHistory(previous: SessionId?) {
        if (previous == null) return
        if (activationHistory.lastOrNull() == previous) return
        activationHistory.addLast(previous)
        while (activationHistory.size > historyCapacity) {
            activationHistory.removeFirst()
        }
    }

    internal fun addSession(session: ClusterSession, makeActive: Boolean = true) {
        _sessions.value = _sessions.value + session
        if (makeActive) {
            pushHistory(_activeSessionId.value)
            _activeSessionId.value = session.id
        }
    }

    internal fun removeSession(
        id: SessionId,
        behavior: CloseTabFocus = CloseTabFocus.LEFT_NEIGHBOR,
    ): ClusterSession? {
        val oldList = _sessions.value
        val session = oldList.firstOrNull { it.id == id } ?: return null
        val closedIndex = oldList.indexOf(session)
        val newList = oldList.filterNot { it.id == id }
        _sessions.value = newList

        activationHistory.removeAll { it == id }

        if (_activeSessionId.value == id) {
            _activeSessionId.value = computeNewActive(closedIndex, newList, behavior)
        }
        return session
    }

    private fun computeNewActive(
        closedIndex: Int,
        newList: List<ClusterSession>,
        behavior: CloseTabFocus,
    ): SessionId? {
        if (newList.isEmpty()) return null
        return when (behavior) {
            CloseTabFocus.FIRST -> newList.first().id

            CloseTabFocus.LEFT_NEIGHBOR ->
                newList.getOrNull((closedIndex - 1).coerceAtLeast(0))?.id

            CloseTabFocus.PREVIOUS_ACTIVE -> {
                val livingIds = newList.mapTo(HashSet(newList.size)) { it.id }
                val recovered = activationHistory.lastOrNull { it in livingIds }
                recovered ?: newList.getOrNull((closedIndex - 1).coerceAtLeast(0))?.id
            }
        }
    }

    internal fun setActive(id: SessionId) {
        if (_sessions.value.any { it.id == id }) {
            pushHistory(_activeSessionId.value)
            _activeSessionId.value = id
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

    fun updateDropZoneScreenBounds(bounds: Rect?) {
        _dropZoneScreenBounds.value = bounds
    }
}
