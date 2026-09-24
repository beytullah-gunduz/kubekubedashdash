package com.kubekubedashdash.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the user set up in one drawer log tab — filter, toggles, muted pods and
 * scroll position — kept outside the pane so it survives the pane leaving
 * composition: a log-tab switch, collapsing or hiding the drawer, or the
 * drawer moving between the window and a cluster tab (widescreen layout).
 * One instance per drawer-tab key, owned by [LogPaneStateStore].
 */
@Stable
class LogPaneViewState {
    var filterText by mutableStateOf("")
    var useRegex by mutableStateOf(false)
    var caseSensitive by mutableStateOf(false)
    var wrap by mutableStateOf(false)

    /** Pinned to the newest line: the pod pane's Follow chip, the tail pane's stickToBottom. */
    var follow by mutableStateOf(true)

    /** Namespace tail only: pods whose lines are hidden. */
    var mutedPods by mutableStateOf(emptySet<String>())

    // Last scroll position. Plain fields, not snapshot state: nothing renders
    // from them; a pane reads them once, when it re-enters composition with
    // [follow] off.
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
}

/**
 * Drawer-tab key → [LogPaneViewState], one store per window: the application-log
 * tab shows in every window and each window's pane keeps its own view. The window
 * (App.kt) prunes it to the registry's open tabs, so a closed tab's state goes
 * with it. Main thread only (composition and effects).
 */
class LogPaneStateStore {
    private val states = HashMap<String, LogPaneViewState>()

    fun stateFor(key: String): LogPaneViewState = states.getOrPut(key) { LogPaneViewState() }

    fun retainOnly(keys: Set<String>) {
        states.keys.retainAll(keys)
    }

    internal val size: Int get() = states.size
}
