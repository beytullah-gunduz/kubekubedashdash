package com.kubekubedashdash.ui.yamledit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.kubekubedashdash.ui.screens.YamlSearchMatch
import com.kubekubedashdash.ui.screens.findYamlMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** How long after the last edit the matches are recomputed; a new query is searched at once. */
private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * The editor window's search: the query the toolbar's field edits, the matches in the buffer and
 * which one is current. The window owns one for its whole life (the editor itself comes and goes
 * with the phase); [rememberYamlSearch] keeps [matches] up to date and marks them in the buffer.
 */
internal class YamlSearchState {
    var query by mutableStateOf("")

    /** In document order, as `findYamlMatches` numbers the lines of the buffer's text. */
    var matches by mutableStateOf<List<YamlSearchMatch>>(emptyList())
        internal set

    /** Index into [matches]; meaningful only while [matches] is not empty. */
    var current by mutableIntStateOf(0)
        internal set

    /** Bumped by a new query and by Next / Previous: the caret follows the current match then, and only then. */
    var navigations by mutableIntStateOf(0)
        internal set

    /** Counts every change of the buffer's text, so the status strip can re-read the line count. */
    var bufferChanges by mutableIntStateOf(0)
        internal set

    fun next() = step(+1)

    fun previous() = step(-1)

    private fun step(by: Int) {
        if (matches.isEmpty()) return
        Snapshot.withMutableSnapshot {
            current = (current + by + matches.size) % matches.size
            navigations++
        }
    }
}

/**
 * The window's [YamlSearchState] for [buffer], with its matches recomputed on every query change
 * and [SEARCH_DEBOUNCE_MS] after the last edit, off the EDT. The buffer's text is read on the EDT
 * (Swing); the search itself runs on `Dispatchers.Default`, because a 1 MB buffer has tens of
 * thousands of lines. The matches are marked in the buffer, and the caret follows only a new query
 * or a Next / Previous, never the refresh after typing.
 */
@Composable
internal fun rememberYamlSearch(buffer: RstaBuffer): YamlSearchState {
    val state = remember(buffer) {
        YamlSearchState().also { search -> buffer.addChangeListener { search.bufferChanges++ } }
    }
    LaunchedEffect(state, buffer) {
        var searchedQuery = state.query
        snapshotFlow { state.query to state.bufferChanges }.collectLatest { (query, _) ->
            val queryChanged = query != searchedQuery
            if (query.isBlank()) {
                searchedQuery = query
                if (state.matches.isNotEmpty()) {
                    Snapshot.withMutableSnapshot {
                        state.matches = emptyList()
                        state.current = 0
                    }
                }
                return@collectLatest
            }
            if (!queryChanged) delay(SEARCH_DEBOUNCE_MS)
            // This coroutine runs on the EDT, which Swing requires for reading the text.
            val lines = buffer.text().lines()
            val found = withContext(Dispatchers.Default) { findYamlMatches(lines, query) }
            searchedQuery = query
            Snapshot.withMutableSnapshot {
                if (queryChanged) {
                    state.current = 0
                    state.navigations++
                } else {
                    state.current = state.current.coerceIn(0, (found.size - 1).coerceAtLeast(0))
                }
                state.matches = found
            }
        }
    }
    LaunchedEffect(state, buffer) {
        var movedFor = state.navigations
        snapshotFlow { Triple(state.matches, state.current, state.navigations) }.collect { (matches, current, navigations) ->
            buffer.highlightMatches(matches, current, moveToCurrent = navigations != movedFor)
            movedFor = navigations
        }
    }
    return state
}
