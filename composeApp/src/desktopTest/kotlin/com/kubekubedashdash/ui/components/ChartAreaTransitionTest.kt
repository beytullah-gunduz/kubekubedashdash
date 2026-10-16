package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val AREA_W = 240
private const val AREA_H = 120

/** The swap states of the tests: the class is the key, the text is what the content renders. */
private sealed interface Area {
    val text: String
}

private data class First(override val text: String) : Area

private data class Second(override val text: String) : Area

/** Counts how many times the content entered the composition (a `remember` inside it re-runs on re-entry). */
private class Probe {
    var created = 0
}

/**
 * [ChartAreaTransition]: a new state with the same key updates the content in place (no animation, no
 * recreation); a key change fades through in Default and powers off then on like a CRT in Retro; the
 * outgoing content renders the state it was handed; a swap interrupted back to the shown content
 * ends with that content fully on; [LocalChartAreaPoweredOn] says whether a Retro swap lit the
 * content. Every theme switch goes through `sync*`, never a `set*`, so nothing here ever persists
 * (harness copied from [TimeSeriesChartSkeletonTest]).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class ChartAreaTransitionTest {

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

    /**
     * Shows the area over [initial]. Each content is a coloured box (red for [First], green for [Second])
     * with its text; [exposeFlag] adds [LocalChartAreaPoweredOn] to the text.
     */
    private fun SkikoComposeUiTest.show(initial: Area, probe: Probe = Probe(), exposeFlag: Boolean = false): MutableState<Area> {
        val state = mutableStateOf(initial)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(AREA_W.dp, AREA_H.dp).background(Color.White)) {
                    ChartAreaTransition(state.value, contentKey = { it::class }) { a ->
                        remember { probe.created++ }
                        Column {
                            Box(Modifier.size(120.dp, 40.dp).background(if (a is First) Color.Red else Color.Green)) {
                                // Read at birth, as TimeSeriesChart does: the flag is cleared once the power-on is over.
                                val flag = LocalChartAreaPoweredOn.current
                                val powered = remember { flag }
                                Text(if (exposeFlag) "${a.text} powered=$powered" else a.text)
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        return state
    }

    private fun SkikoComposeUiTest.image(): PixelMap = onRoot().captureToImage().toPixelMap()

    private fun PixelMap.sameAs(other: PixelMap) = width == other.width && height == other.height && buffer.contentEquals(other.buffer)

    private fun runArea(block: SkikoComposeUiTest.() -> Unit) = runSkikoComposeUiTest(size = Size(AREA_W.toFloat(), AREA_H.toFloat()), density = Density(1f), block = block)

    private fun retro() = ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)

    /** The picture of [state] composed with the area and left alone. */
    private fun settled(state: Area): PixelMap {
        var out: PixelMap? = null
        runArea {
            mainClock.autoAdvance = false
            show(state)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            out = image()
        }
        return out!!
    }

    // ── Default ─────────────────────────────────────────────────────────────────

    @Test
    fun `Default same key new state updates in place without a second content or a recreation`() = runArea {
        mainClock.autoAdvance = false
        val probe = Probe()
        val state = show(First("one"), probe)
        mainClock.advanceTimeByFrame()
        waitForIdle()
        state.value = First("two")
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onAllNodesWithText("two").assertCountEquals(1)
        onAllNodesWithText("one").assertCountEquals(0)
        assertEquals(1, probe.created, "the content kept its remembered state")
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onAllNodesWithText("two").assertCountEquals(1)
        assertEquals(1, probe.created)
    }

    @Test
    fun `Default key change ends with only the new content`() = runArea {
        val state = show(First("one"))
        onNodeWithText("one").assertExists()
        state.value = Second("two")
        waitForIdle()
        onAllNodesWithText("two").assertCountEquals(1)
        onAllNodesWithText("one").assertCountEquals(0)
    }

    @Test
    fun `Default outgoing content renders its own state not the outer one`() = runArea {
        mainClock.autoAdvance = false
        val state = show(First("old"))
        mainClock.advanceTimeByFrame()
        waitForIdle()
        state.value = Second("new")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(50)
        waitForIdle()
        // Mid fade: the old content is still there, showing "old" rather than the outer "new".
        onAllNodesWithText("old").assertCountEquals(1)
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onAllNodesWithText("old").assertCountEquals(0)
        onAllNodesWithText("new").assertCountEquals(1)
    }

    // ── Retro ───────────────────────────────────────────────────────────────────

    @Test
    fun `Retro key change powers off then on and settles on the new content`() {
        retro()
        val settledFirst = settled(First("alpha"))
        val settledSecond = settled(Second("beta"))
        assertFalse(settledFirst.sameAs(settledSecond), "the two contents look different")
        runArea {
            mainClock.autoAdvance = false
            val state = show(First("alpha"))
            mainClock.advanceTimeByFrame()
            waitForIdle()
            state.value = Second("beta")
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(100)
            waitForIdle()
            val mid = image()
            assertFalse(mid.sameAs(settledFirst), "mid power-off is not the old content")
            assertFalse(mid.sameAs(settledSecond), "mid power-off is not the new content either")
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(image().sameAs(settledSecond), "after the swap the new content is settled")
        }
    }

    @Test
    fun `Retro flipping back mid power-off ends with the original content fully on`() {
        retro()
        val settledFirst = settled(First("alpha"))
        runArea {
            mainClock.autoAdvance = false
            val state = show(First("alpha"))
            mainClock.advanceTimeByFrame()
            waitForIdle()
            state.value = Second("beta")
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(100)
            waitForIdle()
            onAllNodesWithText("beta").assertCountEquals(0)
            state.value = First("alpha")
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onAllNodesWithText("beta").assertCountEquals(0)
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            onAllNodesWithText("beta").assertCountEquals(0)
            assertTrue(image().sameAs(settledFirst), "the interrupted swap finished powering the old content on, it is not invisible")
        }
    }

    @Test
    fun `Retro same key new state shows at once with no animation and no recreation`() {
        retro()
        val settledSecondText = settled(First("two"))
        runArea {
            mainClock.autoAdvance = false
            val probe = Probe()
            val state = show(First("one"), probe)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            state.value = First("two")
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertTrue(image().sameAs(settledSecondText), "no power-off: the new state is on screen after one frame")
            assertEquals(1, probe.created, "the content kept its remembered state")
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(image().sameAs(settledSecondText))
            assertEquals(1, probe.created)
        }
    }

    // ── LocalChartAreaPoweredOn ─────────────────────────────────────────────────

    @Test
    fun `Retro the content composed with the area is not powered on but one a swap lit is`() = runArea {
        retro()
        mainClock.autoAdvance = false
        val state = show(First("alpha"), exposeFlag = true)
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("alpha powered=false").assertExists()
        state.value = Second("beta")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onNodeWithText("beta powered=true").assertExists()
        state.value = First("alpha")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onNodeWithText("alpha powered=true").assertExists()
    }

    @Test
    fun `Retro content born after the power-on is over is not powered on`() = runArea {
        mainClock.autoAdvance = false
        retro()
        val state = mutableStateOf<Area>(First("alpha"))
        val late = mutableStateOf(false)
        setContent {
            MaterialTheme(typography = kdTypography()) {
                Box(Modifier.size(AREA_W.dp, AREA_H.dp).background(Color.White)) {
                    ChartAreaTransition(state.value, contentKey = { it::class }) { a ->
                        Column {
                            val flag = LocalChartAreaPoweredOn.current
                            val atBirth = remember { flag }
                            Text("${a.text} powered=$atBirth")
                            if (late.value) {
                                val lateFlag = LocalChartAreaPoweredOn.current
                                val lateAtBirth = remember { lateFlag }
                                Text("late powered=$lateAtBirth")
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        state.value = Second("beta")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onNodeWithText("beta powered=true").assertExists()
        late.value = true
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("late powered=false").assertExists()
    }

    @Test
    fun `the area keeps its full width in both themes, also mid-swap, whatever the content's width`() {
        for (style in listOf(ThemeStyle.DEFAULT, ThemeStyle.RETRO)) {
            runArea {
                mainClock.autoAdvance = false
                ThemeManager.syncStyleFromPreferences(style)
                val state = mutableStateOf<Area>(First("alpha"))
                setContent {
                    MaterialTheme(typography = kdTypography()) {
                        Box(Modifier.size(AREA_W.dp, AREA_H.dp)) {
                            ChartAreaTransition(state.value, contentKey = { it::class }, modifier = Modifier.testTag("area")) { a ->
                                // A short one-line note, far narrower than the area.
                                Text(a.text)
                            }
                        }
                    }
                }
                mainClock.advanceTimeByFrame()
                waitForIdle()
                assertEquals(AREA_W.dp, onNodeWithTag("area").getBoundsInRoot().width, "$style: settled")
                state.value = Second("beta")
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeBy(150)
                assertEquals(AREA_W.dp, onNodeWithTag("area").getBoundsInRoot().width, "$style: mid-swap")
            }
        }
    }

    @Test
    fun `Default content is never powered on, also after a key change`() = runArea {
        val state = show(First("alpha"), exposeFlag = true)
        onNodeWithText("alpha powered=false").assertExists()
        state.value = Second("beta")
        waitForIdle()
        onNodeWithText("beta powered=false").assertExists()
    }
}
