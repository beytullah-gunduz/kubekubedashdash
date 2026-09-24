package com.kubekubedashdash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [nextLogDrawerHostKey] pure cases, then [rememberLogDrawerHostKey] driven
 * through a real [HorizontalPager] to prove the mid-animation and
 * insert/remove behaviour end to end.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class LogDrawerPlacementTest {

    private val tabKeys = listOf("AC", "C1", "T", "C2")
    private val clusterTabKeys = setOf("C1", "C2")

    @Test
    fun `settled on a cluster tab hosts it`() {
        assertEquals(
            "C1",
            nextLogDrawerHostKey(tabKeys, clusterTabKeys, currentPage = 1, targetPage = 1, previousHostKey = null),
        )
    }

    @Test
    fun `settled on a tab without a sidebar hosts nothing`() {
        assertEquals(
            null,
            nextLogDrawerHostKey(tabKeys, clusterTabKeys, currentPage = 2, targetPage = 2, previousHostKey = "C1"),
        )
    }

    @Test
    fun `mid-animation keeps the previous host`() {
        assertEquals(
            "C1",
            nextLogDrawerHostKey(tabKeys, clusterTabKeys, currentPage = 2, targetPage = 3, previousHostKey = "C1"),
        )
        assertEquals(
            null,
            nextLogDrawerHostKey(tabKeys, clusterTabKeys, currentPage = 2, targetPage = 3, previousHostKey = null),
        )
    }

    @Test
    fun `mid-animation with the previous host closed falls back to the current page`() {
        assertEquals(
            "C2",
            nextLogDrawerHostKey(
                tabKeys = listOf("AC", "T", "C2"),
                clusterTabKeys = clusterTabKeys,
                currentPage = 2,
                targetPage = 1,
                previousHostKey = "C1",
            ),
        )
    }

    @Test
    fun `no tabs hosts nothing`() {
        assertEquals(
            null,
            nextLogDrawerHostKey(emptyList(), clusterTabKeys, currentPage = 0, targetPage = 0, previousHostKey = null),
        )
        assertEquals(
            "C1",
            nextLogDrawerHostKey(listOf("C1"), setOf("C1"), currentPage = 9, targetPage = 9, previousHostKey = null),
        )
    }

    private var tabs by mutableStateOf(listOf<String>())
    private val log = mutableListOf<String>()
    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope

    // Mirrors App.kt's wiring: cluster tabs start with "C"; the page whose key
    // is the host gets the drawer, and a null host puts it at the window.
    @Composable
    private fun Window(initialPage: Int) {
        val pagerState = rememberPagerState(initialPage) { tabs.size }
        pager = pagerState
        scope = rememberCoroutineScope()
        val host = rememberLogDrawerHostKey(
            pagerState = pagerState,
            tabKeys = tabs,
            clusterTabKeys = tabs.filterTo(HashSet()) { it.startsWith("C") },
        )
        Column(Modifier.size(400.dp, 400.dp)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                key = { tabs.getOrNull(it) ?: it },
                beyondViewportPageCount = 0,
                userScrollEnabled = false,
            ) { page ->
                val key = tabs.getOrNull(page)
                Box(Modifier.fillMaxSize()) {
                    if (key != null && key == host) {
                        DisposableEffect(Unit) {
                            log += "enter:$key"
                            onDispose { log += "exit:$key" }
                        }
                    }
                }
            }
            if (host == null) {
                DisposableEffect(Unit) {
                    log += "enter:window"
                    onDispose { log += "exit:window" }
                }
            }
        }
    }

    @Test
    fun `inserting a tab in front of the host does not move the drawer`() = runComposeUiTest {
        tabs = listOf("C0")
        setContent { Window(0) }
        waitForIdle()
        log.clear()
        tabs = listOf("AC", "C0")
        waitForIdle()
        assertTrue(log.isEmpty(), "log: $log")
    }

    @Test
    fun `closing a tab left of the host does not move the drawer`() = runComposeUiTest {
        tabs = listOf("C0", "C1", "C2")
        setContent { Window(1) }
        waitForIdle()
        log.clear()
        tabs = listOf("C1", "C2")
        waitForIdle()
        assertTrue(log.isEmpty(), "log: $log")
    }

    @Test
    fun `switching across a tab without a sidebar never drops the drawer to the window`() = runComposeUiTest {
        tabs = listOf("AC", "C1", "T", "C2")
        setContent { Window(1) }
        waitForIdle()
        log.clear()
        scope.launch { pager.animateScrollToPage(3) }
        waitForIdle()
        assertTrue(log.none { it.endsWith("window") }, "log: $log")
        assertEquals(setOf("exit:C1", "enter:C2"), log.toSet(), "log: $log")
        assertEquals(2, log.size, "log: $log")
    }

    @Test
    fun `switching to a neighbouring cluster tab moves the drawer once`() = runComposeUiTest {
        tabs = listOf("C1", "C2")
        setContent { Window(0) }
        waitForIdle()
        log.clear()
        scope.launch { pager.animateScrollToPage(1) }
        waitForIdle()
        assertEquals(setOf("exit:C1", "enter:C2"), log.toSet(), "log: $log")
        assertEquals(2, log.size, "log: $log")
    }
}
