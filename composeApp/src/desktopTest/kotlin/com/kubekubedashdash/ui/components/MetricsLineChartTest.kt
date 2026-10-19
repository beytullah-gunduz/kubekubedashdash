package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdTypography
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CARD_W = 320
private const val CARD_H = 300
private const val CARD_TAG = "metricsCard"
private const val COLLECTING = "Collecting data…"

/**
 * [MetricsLineChart]'s "Collecting data" state. With fewer than two samples the plot shows the shared
 * in-plot loading look, and the Min/Max row is reserved (empty) so the card is exactly as tall as it
 * is once the second sample arrives. Every theme switch goes through `sync*`, never a `set*`, so nothing
 * here ever persists; the manager's prior mode, style and palette are restored after each case and the
 * test runs only against the Gradle test-data store (harness copied from [TimeSeriesChartSkeletonTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class MetricsLineChartTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalPalette: ThemePalette
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalPalette = ThemeManager.palette
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        ThemeManager.syncPaletteFromPreferences(ThemePalette.STYLE)
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ScreenshotHooks.crtTimeScale.value = 1
    }

    @AfterTest
    fun restore() {
        ScreenshotHooks.crtTimeScale.value = 1
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    private fun SkikoComposeUiTest.show(values: List<Long>): MutableState<List<Long>> {
        val state = mutableStateOf(values)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(CARD_W.dp, CARD_H.dp).background(Color.White)) {
                    MetricsLineChart(
                        values = state.value,
                        label = "CPU",
                        currentText = "100m",
                        formatValue = { "${it}m" },
                        lineColor = Color.Red,
                        modifier = Modifier.fillMaxWidth().testTag(CARD_TAG),
                    )
                }
            }
        }
        waitForIdle()
        return state
    }

    private fun SkikoComposeUiTest.cardHeight() = onNodeWithTag(CARD_TAG).getBoundsInRoot().let { it.bottom - it.top }

    private fun runCard(block: SkikoComposeUiTest.() -> Unit) = runSkikoComposeUiTest(size = Size(CARD_W.toFloat(), CARD_H.toFloat()), density = Density(1f), block = block)

    @Test
    fun `the card is as tall with one sample as with two`() = runCard {
        val state = show(listOf(100L))
        val one = cardHeight()
        state.value = listOf(100L, 200L)
        waitForIdle()
        assertEquals(one, cardHeight(), "the reserved Min/Max row keeps the card's height at the second sample")
    }

    @Test
    fun `Collecting data shows with one sample and goes away with two`() = runCard {
        val state = show(listOf(100L))
        onNodeWithText(COLLECTING).assertExists()
        state.value = listOf(100L, 200L)
        waitForIdle()
        onAllNodesWithText(COLLECTING).assertCountEquals(0)
    }

    @Test
    fun `no Min or Max text before the second sample, both after`() = runCard {
        val state = show(listOf(100L))
        onAllNodesWithText("Min: ", substring = true).assertCountEquals(0)
        onAllNodesWithText("Max: ", substring = true).assertCountEquals(0)
        state.value = listOf(100L, 200L)
        waitForIdle()
        onNodeWithText("Min: 100m").assertExists()
        onNodeWithText("Max: 200m").assertExists()
    }

    @Test
    fun `Retro shows the upper-case caption without the ellipsis`() = runCard {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        show(listOf(100L))
        onNodeWithText("COLLECTING DATA").assertExists()
    }
}
