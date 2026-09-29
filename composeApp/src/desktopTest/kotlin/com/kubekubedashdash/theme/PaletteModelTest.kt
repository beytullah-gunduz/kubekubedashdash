package com.kubekubedashdash.theme

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdSyntaxString
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the palette model (theme-expansion plan §3.4, D9-D11): how a style resolves to a palette, what
 * the colour-blind switch swaps, that the `Kd*` getters follow the resolved colours, that
 * [ThemeManager.paletteKey] tracks every axis, and the two colour helpers. Every switch goes through
 * the `sync*FromPreferences` functions, never a `set*`, so nothing here persists. Runs only against
 * the Gradle test-data store; every axis it touches is restored after each case.
 */
class PaletteModelTest {

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
    fun `STYLE resolves to the style's own palette`() {
        assertSame(DefaultPalette, kdPaletteSpec(ThemeStyle.DEFAULT, ThemePalette.STYLE))
        assertSame(RetroPalette, kdPaletteSpec(ThemeStyle.RETRO, ThemePalette.STYLE))
    }

    @Test
    fun `cvd swaps exactly the status quartet and onError`() {
        listOf(DefaultPalette, RetroPalette).forEach { spec ->
            listOf(spec.dark, spec.light).forEach { v ->
                assertEquals(
                    v.colors,
                    v.cvdColors.copy(
                        success = v.colors.success,
                        warning = v.colors.warning,
                        error = v.colors.error,
                        info = v.colors.info,
                        onError = v.colors.onError,
                    ),
                    "cvdColors differs from colors outside the status quartet and onError (isDark = ${v.isDark})",
                )
            }
        }
    }

    @Test
    fun `the cvd scheme changes only error and onError for a hand-written scheme`() {
        val v = RetroPalette.dark
        assertEquals(v.cvd.error, v.cvdScheme.error)
        assertEquals(v.cvd.onError, v.cvdScheme.onError)
        assertEquals(v.scheme.primary, v.cvdScheme.primary)
    }

    @Test
    fun `Kd getters follow the resolved colours`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        assertEquals(RetroDarkColors.surface, KdSurface)

        ThemeManager.syncCvdFromPreferences(true)
        assertEquals(RetroDarkCvd.error, KdError)
        // Syntax colours are never part of the colour-blind swap (D14).
        assertEquals(RetroDarkColors.syntaxString, KdSyntaxString)
    }

    @Test
    fun `paletteKey changes on every axis`() {
        val k0 = ThemeManager.paletteKey
        assertEquals(k0, ThemeManager.paletteKey, "the key is stable while no axis changes")

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        val k1 = ThemeManager.paletteKey
        assertNotEquals(k0, k1, "style")

        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        val k2 = ThemeManager.paletteKey
        assertNotEquals(k1, k2, "mode")

        ThemeManager.syncCvdFromPreferences(true)
        val k3 = ThemeManager.paletteKey
        assertNotEquals(k2, k3, "cvd")

        // palette axis: WS4 adds HIGH_CONTRAST here
    }

    @Test
    fun `kdMix and kdInkOn pins`() {
        assertNear(Color(0xFF808080), kdMix(Color.Black, Color.White, 0.5f), "kdMix(Black, White, 0.5)")
        assertNear(
            Color(0xFF4C333A),
            kdMix(Color(0xFF2A3038), Color(0xFFE54343), 0.18f),
            "kdMix(#2A3038, #E54343, 0.18)",
        )

        assertEquals(Color.Black, kdInkOn(Color.White), "ink on White")
        assertEquals(Color.White, kdInkOn(Color.Black), "ink on Black")
        assertEquals(Color.Black, kdInkOn(Color(0xFFFDD835)), "ink on #FDD835")
        assertEquals(Color.Black, kdInkOn(Color(0xFF1E88E5)), "ink on #1E88E5")
        assertEquals(Color.Black, kdInkOn(Color(0xFFE53935)), "ink on #E53935")
    }

    @Test
    fun `a variant rejects an ansi list that is not 16 long`() {
        assertFailsWith<IllegalArgumentException> {
            KdPaletteVariant(DefaultDarkColors, DefaultDarkCvd, isDark = true, ansi = List(15) { Color.Black })
        }
        // Sixteen is accepted.
        KdPaletteVariant(DefaultDarkColors, DefaultDarkCvd, isDark = true, ansi = List(16) { Color.Black })
    }
}
