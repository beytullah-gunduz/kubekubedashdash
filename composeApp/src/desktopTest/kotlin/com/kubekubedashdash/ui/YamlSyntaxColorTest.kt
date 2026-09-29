package com.kubekubedashdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.kubekubedashdash.KdSyntaxBool
import com.kubekubedashdash.KdSyntaxKey
import com.kubekubedashdash.KdSyntaxNumber
import com.kubekubedashdash.KdSyntaxString
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.ui.screens.highlightYamlLine
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The YAML tab colours keys, strings, numbers, booleans and comments from the palette's own syntax
 * tokens (theme-expansion plan WS2, step 2.5), not from the status colours: a colour-blind status
 * switch must not recolour a YAML string. Every switch goes through the `sync*FromPreferences`
 * functions, so nothing here persists; every axis it touches is restored after each case.
 */
class YamlSyntaxColorTest {

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
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
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

    /** The colour of the span that covers exactly `[start, end)` of [text]. */
    private fun spanColor(text: AnnotatedString, start: Int, end: Int): Color {
        val span = text.spanStyles.filter { it.start == start && it.end == end }
        assertEquals(1, span.size, "expected one span over [$start, $end) in '${text.text}', got ${text.spanStyles}")
        return span.single().item.color
    }

    @Test
    fun `a key and its string value take the syntax key and string colours`() {
        val line = "name: \"x\""
        val text = highlightYamlLine(line)

        assertEquals(KdSyntaxKey, spanColor(text, 0, 4), "key colour")
        assertEquals(KdSyntaxString, spanColor(text, 6, 9), "string value colour")
    }

    @Test
    fun `the colour-blind status switch does not recolour a string value`() {
        val line = "name: \"x\""
        val before = spanColor(highlightYamlLine(line), 6, 9)

        ThemeManager.syncCvdFromPreferences(true)

        val after = spanColor(highlightYamlLine(line), 6, 9)
        assertEquals(before, after, "string value colour under the colour-blind switch")
        assertEquals(KdSyntaxString, after)
    }

    @Test
    fun `numbers and booleans follow the syntax tokens, not the status colours`() {
        ThemeManager.syncCvdFromPreferences(true)

        // "replicas: 3" -> key [0, 8), ": " [8, 10), value [10, 11)
        assertEquals(KdSyntaxNumber, spanColor(highlightYamlLine("replicas: 3"), 10, 11), "number colour")
        // "paused: true" -> key [0, 6), ": " [6, 8), value [8, 12)
        val bool = spanColor(highlightYamlLine("paused: true"), 8, 12)
        assertEquals(KdSyntaxBool, bool, "boolean colour")
        assertNotEquals(KdWarning, bool, "the boolean colour must not be the status warning, which the switch swaps")
    }
}
