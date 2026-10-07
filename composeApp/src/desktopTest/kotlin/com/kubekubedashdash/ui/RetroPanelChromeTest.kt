package com.kubekubedashdash.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.kdStrokeCap
import com.kubekubedashdash.retroCaps
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [com.kubekubedashdash.kdRoundShape], [com.kubekubedashdash.kdStrokeCap] and
 * [com.kubekubedashdash.retroCaps] (WS10, plan §12.2 "WS10 — Static sweep").
 * Pure — no composition needed. Every switch goes through `sync*`, never a
 * `set*`, so nothing here ever persists. Runs only against the Gradle
 * test-data store; the manager's prior mode and style are restored after
 * each case (harness copied from [RetroChromeTest]).
 */
class RetroPanelChromeTest {

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

    @Test
    fun `kdRoundShape is a circle under Default and a square under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals(CircleShape, kdRoundShape)

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(RoundedCornerShape(0.dp), kdRoundShape)
    }

    @Test
    fun `kdStrokeCap is Round under Default and Butt under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals(StrokeCap.Round, kdStrokeCap)

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(StrokeCap.Butt, kdStrokeCap)
    }

    @Test
    fun `retroCaps is a no-op under Default and upper-cases under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals("Pod Info", "Pod Info".retroCaps())

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals("POD INFO", "Pod Info".retroCaps())
    }
}
