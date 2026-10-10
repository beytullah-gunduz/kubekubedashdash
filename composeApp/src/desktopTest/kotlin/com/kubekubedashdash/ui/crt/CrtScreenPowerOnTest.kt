package com.kubekubedashdash.ui.crt

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window power-on must already be dark in the composition that enters Retro. In the app the
 * frame drawn right after that composition can run before any `LaunchedEffect` body (effects go
 * through the window's dispatcher), so a progress still at 1 there draws the finished Retro screen
 * for a frame — longer when that first Retro frame is slow — before the tube collapses and powers
 * on. The value is read in the composition body itself, before this composition's effects exist,
 * so the check does not depend on when a dispatcher runs them (the test's runs them during apply).
 * Flips the style and mode through the sync entry points, which never write the preference store,
 * and restores both afterwards.
 */
@OptIn(ExperimentalTestApi::class)
class CrtScreenPowerOnTest {

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
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    /** (retro, dark, progress) in each composition pass, before that pass's effects exist. */
    private fun recordCompositions(steps: List<() -> Unit>): List<Triple<Boolean, Boolean, Float>> {
        val seen = mutableListOf<Triple<Boolean, Boolean, Float>>()
        runComposeUiTest {
            setContent {
                val progress = rememberCrtScreenPowerOn()
                seen += Triple(ThemeManager.isRetro, ThemeManager.isDarkTheme, progress.value)
            }
            waitForIdle()
            for (step in steps) {
                runOnUiThread(step)
                waitForIdle()
            }
        }
        return seen
    }

    @Test
    fun `entering Retro starts the power-on dark in the same composition`() {
        val seen = recordCompositions(listOf({ ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO) }))
        val firstRetro = seen.first { it.first }
        assertEquals(0f, firstRetro.third, "the first Retro composition must hand the draw a dark tube, not the finished screen")
    }

    @Test
    fun `a dark-light flip in Retro powers on again from dark`() {
        val seen = recordCompositions(
            listOf(
                { ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO) },
                { ThemeManager.syncFromPreferences(ThemeMode.LIGHT) },
            ),
        )
        val firstLight = seen.first { it.first && !it.second }
        assertEquals(0f, firstLight.third, "the mode flip in Retro must start its power-on dark in the same composition")
    }

    @Test
    fun `leaving Retro never shows a power-on`() {
        val seen = recordCompositions(
            listOf(
                { ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO) },
                { ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT) },
            ),
        )
        val backToDefault = seen.last { !it.first }
        assertEquals(1f, backToDefault.third, "Default must draw the screen as is")
    }
}
