package com.kubekubedashdash.model

import com.kubekubedashdash.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many places each of a window's Back and Forward stacks keeps. */
internal const val MAX_WINDOW_HISTORY = 50

/**
 * One place the user saw in a window: a tab and, for a cluster tab, the
 * cluster it showed, its main screen and its detail pane. A null [screen]
 * means "that tab, as it is now": a non-cluster tab, or a cluster tab caught
 * on a connection screen.
 */
data class HistoryLocation(
    val tabKey: String,
    val context: String? = null,
    val screen: Screen? = null,
    val extraPane: Screen? = null,
)

/** A window's Back and Forward stacks, oldest first. */
data class NavigationHistoryState(
    val back: List<HistoryLocation> = emptyList(),
    val forward: List<HistoryLocation> = emptyList(),
) {
    val canGoBack: Boolean get() = back.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()
}

/**
 * One chronological Back/Forward history for a window, across its tabs. Pure
 * bookkeeping: [Workspace] says where the user is, which entries are still
 * usable, and shows the one it gets back. Thread-safe — a session's fresh
 * connect prunes it off the UI thread. While holding its lock it calls out
 * only to the predicates passed to [back], [forward] and [prune], which must
 * just read state.
 */
internal class WindowHistory(private val capacity: Int = MAX_WINDOW_HISTORY) {
    private val lock = Any()
    private val _state = MutableStateFlow(NavigationHistoryState())
    val state: StateFlow<NavigationHistoryState> = _state.asStateFlow()

    // A run is tab switches with no navigation in between; it records one
    // entry, where it started ([runStart], null if that pushed nothing).
    private var inTabSwitchRun = false
    private var runStart: HistoryLocation? = null

    /**
     * A navigation inside the active tab is about to happen. [outgoing] is
     * where the user is now, or null on a connection screen, which is never
     * recorded. Forward is cleared either way.
     */
    fun recordNavigation(outgoing: HistoryLocation?) = synchronized(lock) {
        endRun()
        val s = _state.value
        _state.value = NavigationHistoryState(
            back = if (outgoing == null) s.back else push(s.back, outgoing),
            forward = emptyList(),
        )
    }

    /**
     * The active tab is about to change. [outgoing] is where the user is now
     * (null when no tab is active: then nothing is recorded and no run
     * starts). Only the first switch of a run records it.
     */
    fun beforeTabSwitch(outgoing: HistoryLocation?) = synchronized(lock) {
        var back = _state.value.back
        // No active tab yet (a window's first tab): nothing to record, no run to start.
        if (outgoing != null && !inTabSwitchRun) {
            inTabSwitchRun = true
            runStart = null
            if (back.lastOrNull() != outgoing) {
                back = push(back, outgoing)
                runStart = outgoing
            }
        }
        _state.value = NavigationHistoryState(back = back, forward = emptyList())
    }

    /** The switch landed on [arrived]. Back where the run started: the detour leaves no entry. */
    fun afterTabSwitch(arrived: HistoryLocation?) = synchronized(lock) {
        val start = runStart ?: return@synchronized
        val s = _state.value
        if (arrived == start && s.back.lastOrNull() == start) {
            _state.value = s.copy(back = s.back.dropLast(1))
            endRun()
        }
    }

    /**
     * Focus moved without a user switch (a tab closed and another took over):
     * any run is over, and newest entries equal to [current] go, so Back and
     * Forward never offer a click that does nothing.
     */
    fun focusMoved(current: HistoryLocation?) = synchronized(lock) {
        endRun()
        if (current == null) return@synchronized
        val s = _state.value
        _state.value = NavigationHistoryState(
            back = s.back.dropLastWhile { it == current },
            forward = s.forward.dropLastWhile { it == current },
        )
    }

    /**
     * Pops the newest entry that [usable] accepts and that is not [current],
     * dropping the ones passed over, and puts [current] on Forward. Null, with
     * Back emptied, when no entry qualifies.
     */
    fun back(current: HistoryLocation?, usable: (HistoryLocation) -> Boolean): HistoryLocation? = synchronized(lock) {
        endRun()
        val s = _state.value
        val remaining = s.back.toMutableList()
        while (remaining.isNotEmpty()) {
            val candidate = remaining.removeAt(remaining.lastIndex)
            if (candidate != current && usable(candidate)) {
                _state.value = NavigationHistoryState(
                    back = remaining,
                    forward = if (current == null) s.forward else push(s.forward, current),
                )
                return@synchronized candidate
            }
        }
        _state.value = s.copy(back = emptyList())
        null
    }

    /** [back], the other way. */
    fun forward(current: HistoryLocation?, usable: (HistoryLocation) -> Boolean): HistoryLocation? = synchronized(lock) {
        endRun()
        val s = _state.value
        val remaining = s.forward.toMutableList()
        while (remaining.isNotEmpty()) {
            val candidate = remaining.removeAt(remaining.lastIndex)
            if (candidate != current && usable(candidate)) {
                _state.value = NavigationHistoryState(
                    back = if (current == null) s.back else push(s.back, current),
                    forward = remaining,
                )
                return@synchronized candidate
            }
        }
        _state.value = s.copy(forward = emptyList())
        null
    }

    /**
     * Drops every entry [gone] matches — a closed tab, or a tab now showing
     * another cluster — and collapses neighbours that became equal.
     */
    fun prune(gone: (HistoryLocation) -> Boolean) = synchronized(lock) {
        val s = _state.value
        _state.value = NavigationHistoryState(
            back = s.back.filterNot(gone).withoutRepeats(),
            forward = s.forward.filterNot(gone).withoutRepeats(),
        )
        // The run started on an entry that is gone: the next switch is a new run.
        if (runStart?.let(gone) == true) endRun()
    }

    /** Forgets everything. */
    fun clear() = synchronized(lock) {
        endRun()
        _state.value = NavigationHistoryState()
    }

    private fun endRun() {
        inTabSwitchRun = false
        runStart = null
    }

    private fun push(list: List<HistoryLocation>, entry: HistoryLocation): List<HistoryLocation> = if (list.lastOrNull() == entry) list else (list + entry).takeLast(capacity)

    private fun List<HistoryLocation>.withoutRepeats(): List<HistoryLocation> = filterIndexed { i, e -> i == 0 || this[i - 1] != e }
}
