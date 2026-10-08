package com.kubekubedashdash.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * Moves the window's tab pager to [activeIndex], the workspace's active tab. The pager takes no
 * user scroll, so this is the only thing meant to move it, and the pager never writes back to the
 * active tab.
 *
 * Slides to the new page, except right after a tab was inserted: then it snaps, because
 * animateScrollToPage composes every page it passes — a new tab usually lands at the end, so
 * opening one from an early tab (say the 4th cluster from the 1st) would compose several full
 * session panes.
 *
 * "Already there" means on the page AND at a zero offset: a second tab click mid-slide cancels the
 * slide wherever it is, which can be part-way past the new target's page.
 *
 * Wherever the move ends, the pager is then snapped onto [activeIndex] if it is not there: another
 * scroll may have taken the slide over (it is pre-empted), or the slide may have started from a
 * stale page — after a tab in front of the current one closes, the pager re-resolves its page by
 * key only at the next measure, and a slide measured from the old index lands a page short.
 */
@Composable
internal fun FollowActiveTab(pagerState: PagerState, activeIndex: Int, tabCount: Int) {
    val prevTabCount = remember { mutableStateOf(tabCount) }
    LaunchedEffect(activeIndex, tabCount) {
        val grew = tabCount > prevTabCount.value
        prevTabCount.value = tabCount
        if (pagerState.isSettledOn(activeIndex)) return@LaunchedEffect
        try {
            if (grew) {
                pagerState.scrollToPage(activeIndex)
            } else {
                pagerState.animateScrollToPage(activeIndex)
            }
        } catch (e: CancellationException) {
            // Rethrows when this effect itself was restarted or left composition; otherwise
            // another scroll took the pager over (MutationInterruptedException).
            ensureActive()
        }
        if (!pagerState.isSettledOn(activeIndex)) pagerState.scrollToPage(activeIndex)
    }
}

private fun PagerState.isSettledOn(page: Int) = currentPage == page && currentPageOffsetFraction == 0f

/**
 * Stops bring-into-view requests from a tab page's content at the page, so they never reach the
 * tab pager. Scrollables inside the page still receive them.
 *
 * A control that gains focus asks its scrollable ancestors to bring it into view, and the pager
 * honours that even with user scroll off: it drops its slide and settles on the requesting page.
 * The pages a slide passes are composed, and a list's table focuses itself once it has rows
 * (ResourceTable), so a click on All Clusters from the last cluster tab ended on the first one.
 */
internal fun Modifier.containBringIntoView(): Modifier = this then ContainBringIntoViewElement

private data object ContainBringIntoViewElement : ModifierNodeElement<ContainBringIntoViewNode>() {
    override fun create() = ContainBringIntoViewNode()
    override fun update(node: ContainBringIntoViewNode) = Unit
}

private class ContainBringIntoViewNode :
    Modifier.Node(),
    BringIntoViewModifierNode {
    // Deliberately not forwarded to the parent, which the interface otherwise asks for.
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) = Unit
}
