package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val HostSize = Size(120f, 40f)
private val Backdrop = Color.White
private val Fill = Color.Red
private val Track = Color.Blue

/**
 * [UsageBar] on a 120 x 40 px host over a white backdrop: a 100 x 20 px bar with a blue track and a red
 * fill at the top-left. Default rounds both ends to 10 px; Retro squares them. Every switch goes
 * through `sync*`, never a `set*`, so nothing here ever persists; the manager's prior mode and style
 * are restored after each case and the test runs only against the Gradle test-data store (harness
 * copied from [com.kubekubedashdash.ui.RetroPanelChromeTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class UsageBarTest {

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

    private fun SkikoComposeUiTest.showBar(style: ThemeStyle, fraction: Float?): PixelMap {
        ThemeManager.syncStyleFromPreferences(style)
        setContent {
            Box(Modifier.size(120.dp, 40.dp).background(Backdrop)) {
                UsageBar(fraction, Fill, Modifier.width(100.dp), height = 20.dp, trackColor = Track)
            }
        }
        waitForIdle()
        return captureToImage().toPixelMap()
    }

    private fun PixelMap.assertColor(x: Int, y: Int, expected: Color) {
        val actual = this[x, y]
        val close = abs(actual.red - expected.red) < 0.05f &&
            abs(actual.green - expected.green) < 0.05f &&
            abs(actual.blue - expected.blue) < 0.05f &&
            abs(actual.alpha - expected.alpha) < 0.05f
        assertTrue(close, "pixel ($x, $y) should be $expected but is $actual")
    }

    @Test
    fun `a bar with a fraction is exposed as progress and a null one is not`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        var fraction by mutableStateOf<Float?>(0.5f)
        setContent { UsageBar(fraction, Fill, Modifier.width(100.dp), trackColor = Track) }
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertExists()

        fraction = 1.7f
        waitForIdle()
        onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 0f..1f))).assertExists()

        fraction = null
        waitForIdle()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test
    fun `Default rounds the track ends and fills from the start`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.DEFAULT, fraction = 0.5f)
        map.assertColor(0, 0, Backdrop)
        map.assertColor(10, 10, Fill)
        map.assertColor(75, 10, Track)
        map.assertColor(99, 0, Backdrop)
    }

    @Test
    fun `Retro squares the track ends`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.RETRO, fraction = 0.5f)
        map.assertColor(0, 0, Fill)
        map.assertColor(99, 0, Track)
        map.assertColor(99, 19, Track)
    }

    @Test
    fun `a null fraction draws the track alone`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.DEFAULT, fraction = null)
        map.assertColor(10, 10, Track)
    }

    @Test
    fun `a fraction above one fills the whole track`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.RETRO, fraction = 1.7f)
        map.assertColor(99, 10, Fill)
    }

    @Test
    fun `a negative fraction draws the track alone`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.RETRO, fraction = -0.3f)
        map.assertColor(1, 10, Track)
    }

    @Test
    fun `a NaN fraction draws the track alone and does not throw`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.RETRO, fraction = Float.NaN)
        map.assertColor(10, 10, Track)
    }

    @Test
    fun `litCells rounds to the nearest cell and shows any non-zero value`() {
        assertEquals(0, litCells(0f, 10))
        assertEquals(0, litCells(Float.NaN, 10))
        assertEquals(0, litCells(-0.3f, 10))
        assertEquals(1, litCells(0.01f, 10))
        assertEquals(1, litCells(0.04f, 10))
        assertEquals(5, litCells(0.5f, 10))
        assertEquals(8, litCells(0.84f, 10))
        assertEquals(9, litCells(0.86f, 10))
        assertEquals(10, litCells(1f, 10))
        assertEquals(10, litCells(1.7f, 10))
        assertEquals(9, litCells(0.5f, 18))
        assertEquals(11, litCells(0.62f, 18))
        assertEquals(17, litCells(0.97f, 18))
        assertEquals(18, litCells(0.98f, 18))
    }

    @Test
    fun `Retro draws ten cells`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.RETRO, fraction = 0.5f)
        // Cells span [floor(i * 10.1), floor((i + 1) * 10.1) - 1): cell 0 is [0, 9), cell 1 starts at 10.
        map.assertColor(5, 10, Fill)
        map.assertColor(45, 10, Fill)
        map.assertColor(55, 10, Track)
        map.assertColor(9, 10, Backdrop)
        map.assertColor(95, 10, Track)
    }

    @Test
    fun `Retro lights one cell for a small value`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        val map = showBar(ThemeStyle.RETRO, fraction = 0.02f)
        map.assertColor(5, 10, Fill)
        map.assertColor(15, 10, Track)
    }

    @Test
    fun `Retro fills from the right in RTL`() = runSkikoComposeUiTest(size = HostSize, density = Density(1f)) {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(120.dp, 40.dp).background(Backdrop)) {
                    UsageBar(0.2f, Fill, Modifier.width(100.dp), height = 20.dp, trackColor = Track)
                }
            }
        }
        waitForIdle()
        val map = captureToImage().toPixelMap()
        // In RTL the Box places the 100 px bar at its start, the right edge: x 20..120.
        map.assertColor(10, 10, Backdrop)
        map.assertColor(115, 10, Fill)
        map.assertColor(25, 10, Track)
    }
}
