package com.kubekubedashdash.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * Widescreen layout: which tab (by key) hosts the window's log drawer, or null
 * for the window itself (below the pager, full width).
 *
 * Mid-animation (currentPage ≠ targetPage) the drawer stays where it is, so
 * pages flying past — a terminal, All Clusters, another cluster — never pull it
 * along; it moves once the pager has reached its target (for neighbouring tabs,
 * halfway through the swipe). A host tab that has since closed does not count
 * as "where it is".
 */
internal fun nextLogDrawerHostKey(
    tabKeys: List<String>,
    clusterTabKeys: Set<String>,
    currentPage: Int,
    targetPage: Int,
    previousHostKey: String?,
): String? {
    if (tabKeys.isEmpty()) return null
    if (currentPage != targetPage && (previousHostKey == null || previousHostKey in tabKeys)) return previousHostKey
    return tabKeys[currentPage.coerceIn(0, tabKeys.lastIndex)].takeIf { it in clusterTabKeys }
}

/**
 * [nextLogDrawerHostKey], tracked by tab KEY and recomputed from a snapshotFlow
 * on the pager — i.e. after layout. The pager re-resolves its current page by
 * key during measure, so an index read during composition is one frame stale
 * after a tab is inserted or removed in front of the current one, and would
 * drop the drawer to the window for that frame. Composition only validates the
 * tracked key: if its tab has closed, it falls back to the current page's tab.
 */
@Composable
internal fun rememberLogDrawerHostKey(
    pagerState: PagerState,
    tabKeys: List<String>,
    clusterTabKeys: Set<String>,
): String? {
    val latestTabKeys by rememberUpdatedState(tabKeys)
    val latestClusterTabKeys by rememberUpdatedState(clusterTabKeys)
    var tracked by remember {
        mutableStateOf(
            nextLogDrawerHostKey(tabKeys, clusterTabKeys, pagerState.currentPage, pagerState.currentPage, previousHostKey = null),
        )
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.targetPage }
            .collect { (current, target) ->
                tracked = nextLogDrawerHostKey(latestTabKeys, latestClusterTabKeys, current, target, tracked)
            }
    }
    val host = tracked
    return if (host == null || host in tabKeys) {
        host
    } else {
        nextLogDrawerHostKey(tabKeys, clusterTabKeys, pagerState.currentPage, pagerState.currentPage, previousHostKey = null)
    }
}
