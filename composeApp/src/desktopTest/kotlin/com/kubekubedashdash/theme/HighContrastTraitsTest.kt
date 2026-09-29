package com.kubekubedashdash.theme

import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.RippleThemeConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.kdOutlineWidth
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins what makes High Contrast more than a colour swap (theme-expansion plan D2, §3.4): a 2 dp
 * outline width, a 3 dp + 3 dp inset focus ring, and the M3 scheme derived from its literals. Every
 * switch goes through the `sync*FromPreferences` functions, so nothing here persists. Runs only
 * against the Gradle test-data store; every axis it touches is restored after each case.
 */
class HighContrastTraitsTest {

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

    private fun assertNear(expected: Color, actual: Color, message: String) {
        val tolerance = 1f / 255f + 1e-4f
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
            "$message: expected $expected, got $actual",
        )
    }

    @Test
    fun `only High Contrast has a 2 dp outline, in either style`() {
        ThemeStyle.entries.forEach { style ->
            ThemePalette.entries.forEach { palette ->
                val expected = if (palette == ThemePalette.HIGH_CONTRAST) 2.dp else 1.dp
                assertEquals(expected, kdPaletteSpec(style, palette).outlineWidth, "outlineWidth of $style + $palette")
            }
        }
    }

    @Test
    fun `the High Contrast focus ring is a 3 dp outer and 3 dp inner inset ring`() {
        val ring = HighContrastPalette.focusRing.focus
        assertIs<RippleThemeConfiguration.Focus.InsetRing>(ring)
        // Inner stroke first, outer on top; the inner is 4 dp wide at inset 2 so 1 dp of it is
        // covered, the same overlap the stock ring has (outer 0/2, inner 1/3).
        assertEquals(0.dp, ring.outerStrokeInset)
        assertEquals(3.dp, ring.outerStrokeWidth)
        assertEquals(2.dp, ring.innerStrokeInset)
        assertEquals(4.dp, ring.innerStrokeWidth)
    }

    @Test
    fun `every other palette keeps the stock focus ring`() {
        val stock = RippleDefaults.InsetFocusRingRippleThemeConfiguration.focus
        listOf(DefaultPalette, RetroPalette, MonochromePalette).forEach { spec ->
            assertEquals(stock, spec.focusRing.focus)
        }
    }

    @Test
    fun `kdOutlineWidth follows the palette`() {
        assertEquals(1.dp, kdOutlineWidth, "STYLE")

        ThemeManager.syncPaletteFromPreferences(ThemePalette.HIGH_CONTRAST)
        assertEquals(2.dp, kdOutlineWidth, "HIGH_CONTRAST")

        // The palette is orthogonal to the style: Retro keeps High Contrast's thick outline.
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(2.dp, kdOutlineWidth, "HIGH_CONTRAST + RETRO")

        ThemeManager.syncPaletteFromPreferences(ThemePalette.MONOCHROME)
        assertEquals(1.dp, kdOutlineWidth, "MONOCHROME")
    }

    @Test
    fun `the High Contrast dark scheme matches the derivation pins`() {
        val scheme = HighContrastPalette.dark.scheme
        assertNear(Color(0xFF2E1919), scheme.errorContainer, "errorContainer")
        assertNear(Color(0xFF2C2C2C), scheme.surfaceContainerHighest, "surfaceContainerHighest")
        assertNear(Color(0xFF1A1A1A), scheme.surfaceContainerHigh, "surfaceContainerHigh")
        assertNear(Color(0xFF000000), scheme.surfaceDim, "surfaceDim")
    }
}
