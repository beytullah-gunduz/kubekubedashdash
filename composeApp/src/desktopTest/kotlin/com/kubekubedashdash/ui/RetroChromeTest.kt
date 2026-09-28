package com.kubekubedashdash.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdCorner
import com.kubekubedashdash.retroChrome
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [com.kubekubedashdash.retroChrome] and [com.kubekubedashdash.kdCorner] (WS2,
 * plan §5 "WS2 — Retro chrome"). Does **not** wrap its content in
 * `KubeDashTheme`: that composable's startup `LaunchedEffect`s re-sync
 * [ThemeManager] from the persisted store (Theme.kt:318-321, and the style
 * sync next to it) on first composition, which would silently undo this
 * test's [ThemeManager.syncStyleFromPreferences] call (rejected reviewer
 * amendment, plan §9 m7). Every switch goes through `sync*`, never a `set*`,
 * so nothing here ever persists. Runs only against the Gradle test-data
 * store; the manager's prior mode and style are restored after each case.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class RetroChromeTest {

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

    private val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    @Test
    fun `retroChrome is a no-op under Default`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)

        var captured: TextStyle? = null
        setContent {
            captured = base.retroChrome(10.sp)
        }
        waitForIdle()

        assertEquals(base, captured)
    }

    @Test
    fun `retroChrome swaps in the pixel face under Retro, keeping line height`() = runComposeUiTest {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)

        var captured: TextStyle? = null
        setContent {
            captured = base.retroChrome(10.sp)
        }
        waitForIdle()

        val result = captured
        assertNotNull(result)
        assertNotNull(result.fontFamily)
        assertNotEquals(base.fontFamily, result.fontFamily)
        assertEquals(10.sp, result.fontSize)
        assertEquals(20.sp, result.lineHeight)
    }

    @Test
    fun `kdCorner keeps the radius under Default and squares it under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals(RoundedCornerShape(12.dp), 12.dp.kdCorner)

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(RoundedCornerShape(0.dp), 12.dp.kdCorner)
    }
}
