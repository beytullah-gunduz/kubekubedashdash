package com.kubekubedashdash.ui.crt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val HostSize = Size(300f, 200f)
private val PageColors = listOf(Color(0xFFAA3355), Color(0xFF33AA55), Color(0xFF3355AA))

/**
 * Exercises the WS9 tab cut (D23): [goToTab] jumps a pager instantly in Retro and counts the cut
 * only once [PagerState.scrollToPage] returns, and [crtTabCut] plays a
 * [CrtPanelTiming.TAB_CUT_MS] enter-only aperture restarted by that count. Runs only against the
 * Gradle test-data store; the manager's prior mode, style and dark flag are restored after each
 * case (CrtGhostTest's pattern).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrtTabCutTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    // The three-page pager shared by every case: a plain solid-colour page per index, driven
    // through `crtTabCut` exactly as the four detail panels wire it (WS9 step 2).
    @Composable
    private fun ThreePagePager(state: PagerState, cut: CrtTabCut) {
        HorizontalPager(state = state, modifier = Modifier.fillMaxSize().crtTabCut(cut)) { page ->
            Box(Modifier.fillMaxSize().background(PageColors[page]))
        }
    }

    @Test
    fun `case 1 - Retro first composition shows page 0 with no aperture`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            lateinit var state: PagerState
            lateinit var cut: CrtTabCut
            setContent {
                state = rememberPagerState(pageCount = { PageColors.size })
                cut = rememberCrtTabCut()
                ThreePagePager(state, cut)
            }
            waitForIdle()

            assertEquals(
                PageColors[0],
                captureToImage().toPixelMap()[150, 50],
                "the first composition must never play the cut (cuts starts at 0)",
            )
        }
    }

    @Test
    fun `case 2 - a tab change counts the cut once scrollToPage returns and closes then reopens`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            lateinit var state: PagerState
            lateinit var cut: CrtTabCut
            lateinit var scope: CoroutineScope
            setContent {
                state = rememberPagerState(pageCount = { PageColors.size })
                cut = rememberCrtTabCut()
                scope = rememberCoroutineScope()
                ThreePagePager(state, cut)
            }
            waitForIdle()

            scope.launch { state.goToTab(1, cut) }
            mainClock.advanceTimeByFrame()
            waitForIdle()

            assertEquals(1, cut.cuts, "a real page change must count exactly one cut")
            // Pin the jump first, so the closed-pixel check below cannot pass on a pager that
            // simply has not moved yet.
            assertEquals(1, state.currentPage, "the jump must land on the frame it is asked for")
            assertNotEquals(
                PageColors[1],
                captureToImage().toPixelMap()[150, 50],
                "the new tab must be drawn closed on the frame the jump happens",
            )

            mainClock.advanceTimeBy(150)
            waitForIdle()
            assertEquals(
                PageColors[1],
                captureToImage().toPixelMap()[150, 50],
                "the aperture must be fully open once the 110 ms cut settles",
            )
        }
    }

    @Test
    fun `case 2b - a second cut inside 110 ms restarts the aperture closed on the new page`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            lateinit var state: PagerState
            lateinit var cut: CrtTabCut
            lateinit var scope: CoroutineScope
            setContent {
                state = rememberPagerState(pageCount = { PageColors.size })
                cut = rememberCrtTabCut()
                scope = rememberCoroutineScope()
                ThreePagePager(state, cut)
            }
            waitForIdle()

            scope.launch { state.goToTab(1, cut) }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            mainClock.advanceTimeBy(50) // mid-aperture on page 1
            waitForIdle()

            scope.launch { state.goToTab(2, cut) }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(2, cut.cuts, "each real page change counts its own cut")
            assertEquals(2, state.currentPage)
            assertNotEquals(
                PageColors[2],
                captureToImage().toPixelMap()[150, 50],
                "the second cut must restart the aperture closed, not carry the first one's progress",
            )

            mainClock.advanceTimeBy(150)
            waitForIdle()
            assertEquals(PageColors[2], captureToImage().toPixelMap()[150, 50])
        }
    }

    @Test
    fun `case 3 - goToTab on the current page never counts a cut`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
            ThemeManager.syncFromPreferences(ThemeMode.DARK)
            mainClock.autoAdvance = false
            lateinit var state: PagerState
            lateinit var cut: CrtTabCut
            lateinit var scope: CoroutineScope
            setContent {
                state = rememberPagerState(pageCount = { PageColors.size })
                cut = rememberCrtTabCut()
                scope = rememberCoroutineScope()
                ThreePagePager(state, cut)
            }
            waitForIdle()

            scope.launch { state.goToTab(0, cut) }
            mainClock.advanceTimeByFrame()
            waitForIdle()

            assertEquals(0, cut.cuts, "re-selecting the current page must never count a cut")
        }
    }

    @Test
    fun `case 4 - Default keeps the slide and never counts a cut`() {
        runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
            ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
            mainClock.autoAdvance = false
            lateinit var state: PagerState
            lateinit var cut: CrtTabCut
            lateinit var scope: CoroutineScope
            setContent {
                state = rememberPagerState(pageCount = { PageColors.size })
                cut = rememberCrtTabCut()
                scope = rememberCoroutineScope()
                ThreePagePager(state, cut)
            }
            waitForIdle()

            scope.launch { state.goToTab(1, cut) }
            mainClock.advanceTimeBy(1000)
            waitForIdle()

            assertEquals(1, state.currentPage, "Default must still land on the requested page")
            assertEquals(0, cut.cuts, "Default never counts a cut (D3)")
            assertEquals(
                PageColors[1],
                captureToImage().toPixelMap()[150, 50],
                "Default draws the destination page once its slide settles",
            )
        }
    }
}
