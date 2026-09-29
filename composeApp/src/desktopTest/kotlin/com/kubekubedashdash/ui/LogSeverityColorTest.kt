package com.kubekubedashdash.ui

import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.ui.screens.logviewer.logSeverityColor
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A log line with no severity word reads in the palette's primary text colour, not a fixed blue-grey
 * that vanishes on a light background (theme-expansion plan WS2, step 2.8). Every switch goes through
 * the `sync*FromPreferences` functions, so nothing here persists; every axis it touches is restored
 * after each case.
 */
class LogSeverityColorTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true
    private lateinit var originalPalette: ThemePalette
    private var originalCvd = false

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
        originalPalette = ThemeManager.palette
        originalCvd = ThemeManager.cvdSafeStatus

        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        ThemeManager.syncPaletteFromPreferences(ThemePalette.STYLE)
        ThemeManager.syncCvdFromPreferences(false)
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
        ThemeManager.syncPaletteFromPreferences(originalPalette)
        ThemeManager.syncCvdFromPreferences(originalCvd)
    }

    @Test
    fun `a plain line reads in the primary text colour under Default light`() {
        assertEquals(KdTextPrimary, logSeverityColor("plain line"))
    }

    @Test
    fun `an ERROR line reads in the error colour under Default light`() {
        assertEquals(KdError, logSeverityColor("2026-01-01 ERROR something failed"))
    }
}
