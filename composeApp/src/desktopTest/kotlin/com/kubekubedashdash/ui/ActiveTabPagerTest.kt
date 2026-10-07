package com.kubekubedashdash.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FollowActiveTab] and [containBringIntoView] driven through a real [HorizontalPager] wired as
 * in App.kt, with cluster pages whose table focuses itself like ResourceTable does.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ActiveTabPagerTest {

    private var tabs by mutableStateOf(listOf("AC", "C1", "C2"))
    private var active by mutableStateOf("")
    private val settledPages = mutableListOf<Int>()
    private lateinit var pager: PagerState

    // ResourceTable focuses itself once it has rows; here they arrive a frame after the page is
    // composed, so the page is laid out — part-way into view during a slide — when it asks.
    @Composable
    private fun SelfFocusingTable() {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
        Box(Modifier.fillMaxSize().focusRequester(focusRequester).focusable())
    }

    @Composable
    private fun Window(contained: Boolean, follow: Boolean = true) {
        val activeIndex = tabs.indexOf(active)
        val pagerState = rememberPagerState(activeIndex) { tabs.size }
        pager = pagerState
        if (follow) {
            FollowActiveTab(pagerState, activeIndex, tabs.size)
        } else {
            // The pre-fix slide: no recovery when something else takes the pager over.
            LaunchedEffect(activeIndex) {
                if (pagerState.currentPage != activeIndex) pagerState.animateScrollToPage(activeIndex)
            }
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { settledPages += it }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.size(800.dp, 600.dp),
            key = { tabs[it] },
            beyondViewportPageCount = 0,
            userScrollEnabled = false,
        ) { page ->
            Box(if (contained) Modifier.containBringIntoView() else Modifier, propagateMinConstraints = true) {
                if (tabs[page] == "AC") Box(Modifier.fillMaxSize()) else SelfFocusingTable()
            }
        }
    }

    private fun switchTabs(from: String, to: String, contained: Boolean, follow: Boolean = true) = runComposeUiTest {
        active = from
        setContent { Window(contained, follow) }
        waitForIdle()
        active = to
        waitForIdle()
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
    }

    @Test
    fun `last cluster to All Clusters slides past the first cluster`() {
        switchTabs(from = "C2", to = "AC", contained = true)
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
        assertEquals(listOf(2, 0), settledPages)
    }

    @Test
    fun `All Clusters to last cluster slides past the first cluster`() {
        switchTabs(from = "AC", to = "C2", contained = true)
        assertEquals(2, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
        assertEquals(listOf(0, 2), settledPages)
    }

    @Test
    fun `harness - a passed page that focuses itself takes the bare slide over`() {
        // Negative control: without the fix the slide to All Clusters stops on the first cluster.
        switchTabs(from = "C2", to = "AC", contained = false, follow = false)
        assertEquals(1, pager.currentPage)
    }

    @Test
    fun `containment alone keeps a passed page from taking the bare slide over`() {
        switchTabs(from = "C2", to = "AC", contained = true, follow = false)
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun `without containment the pager still goes back to the active tab`() {
        switchTabs(from = "C2", to = "AC", contained = false)
        assertEquals(0, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun `a second tab click mid-slide lands on that tab`() = runComposeUiTest {
        active = "AC"
        mainClock.autoAdvance = false
        setContent { Window(contained = true) }
        mainClock.advanceTimeBy(100)
        active = "C2"
        var frames = 0
        while (!(pager.currentPage == 1 && pager.currentPageOffsetFraction != 0f) && frames < 200) {
            mainClock.advanceTimeByFrame()
            frames++
        }
        assertEquals(1, pager.currentPage, "never reached C1 mid-slide")
        active = "C1"
        mainClock.advanceTimeBy(3_000)
        assertEquals(1, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun `closing a tab in front of the active one keeps the pager on it`() = runComposeUiTest {
        // This runner starts effects before the frame's measure, so FollowActiveTab reads the
        // pager's page before the pager has re-resolved it by key: one past the active tab here.
        tabs = listOf("AC", "C1", "C2", "C3")
        active = "C3"
        setContent { Window(contained = true) }
        waitForIdle()
        tabs = listOf("AC", "C2", "C3")
        waitForIdle()
        mainClock.advanceTimeBy(3_000)
        assertEquals(2, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction)
    }

    @Test
    fun `scrollables inside a page still bring focused content into view`() = runComposeUiTest {
        val scroll = ScrollState(0)
        val focusRequester = FocusRequester()
        setContent {
            Box(Modifier.size(400.dp).containBringIntoView(), propagateMinConstraints = true) {
                Column(Modifier.verticalScroll(scroll)) {
                    Spacer(Modifier.height(2_000.dp))
                    Box(Modifier.size(50.dp).focusRequester(focusRequester).focusable())
                }
            }
        }
        waitForIdle()
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()
        assertTrue(scroll.value > 0, "scroll: ${scroll.value}")
    }
}
