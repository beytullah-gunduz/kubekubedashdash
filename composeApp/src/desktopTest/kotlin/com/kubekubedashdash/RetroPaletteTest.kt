package com.kubekubedashdash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kubekubedashdash.theme.kdPaletteSpec
import com.kubekubedashdash.ui.screens.settings.ThemePreviewColors
import com.kubekubedashdash.ui.screens.settings.previewColorsFor
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the retro token table (plan §3.1) two ways: (a) a regression lock on
 * every Default value (D3), and (b) an exact match on every Retro value. (c)
 * then checks the WCAG floors §3.2 depends on, and (d) checks that the Settings
 * preview-card resolver (D10) reads the palette table. Every switch goes through
 * [ThemeManager.syncFromPreferences] / [ThemeManager.syncStyleFromPreferences]
 * — never a `set*` — so nothing here ever persists. Runs only against the
 * Gradle test-data store; the manager's prior mode, style, palette and
 * colour-blind switch are restored after each case.
 */
class RetroPaletteTest {

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

    private class TokenExpectation(
        val name: String,
        val getter: () -> Color,
        val defaultDark: Color,
        val defaultLight: Color,
        val retroDark: Color,
        val retroLight: Color,
    )

    // Plan §3.1's token table, one row per token. Default columns are today's
    // literals (Theme.kt) — the regression lock (D3).
    private val tokens = listOf(
        TokenExpectation("KdBackground", { KdBackground }, Color(0xFF1E2124), Color(0xFFF8FAFC), Color(0xFF12142B), Color(0xFFEFE7D2)),
        TokenExpectation("KdSidebarBg", { KdSidebarBg }, Color(0xFF161819), Color(0xFFFFFFFF), Color(0xFF0A0B1A), Color(0xFFE6DCC3)),
        TokenExpectation("KdSurface", { KdSurface }, Color(0xFF2A3038), Color(0xFFFFFFFF), Color(0xFF1E2240), Color(0xFFF8F2E3)),
        TokenExpectation("KdSurfaceVariant", { KdSurfaceVariant }, Color(0xFF323845), Color(0xFFF1F5F9), Color(0xFF272C50), Color(0xFFE8DEC6)),
        TokenExpectation("KdTextPrimary", { KdTextPrimary }, Color(0xFFC8D1DC), Color(0xFF1E293B), Color(0xFFE3E6F5), Color(0xFF2B2418)),
        TokenExpectation("KdTextSecondary", { KdTextSecondary }, Color(0xFF8B95A1), Color(0xFF64748B), Color(0xFFADB3D6), Color(0xFF5A4F3C)),
        TokenExpectation("KdTextPlaceholder", { KdTextPlaceholder }, Color(0xFF94A3B8), Color(0xFF64748B), Color(0xFFADB3D6), Color(0xFF5A4F3C)),
        TokenExpectation("KdTextBright", { KdTextBright }, Color(0xFFFFFFFF), Color(0xFF1E293B), Color(0xFFFFFFFF), Color(0xFF2B2418)),
        TokenExpectation("KdBorder", { KdBorder }, Color(0xFF3A4150), Color(0xFFE2E8F0), Color(0xFF3B4275), Color(0xFFCBBE9E)),
        TokenExpectation("KdHover", { KdHover }, Color(0xFF333944), Color(0xFFF1F5F9), Color(0xFF2E3460), Color(0xFFE3D8BC)),
        TokenExpectation("KdSelected", { KdSelected }, Color(0xFF1A3A5C), Color(0xFFDBEAFE), Color(0xFF1E4A56), Color(0xFFCFE3E0)),
        TokenExpectation("KdPrimary", { KdPrimary }, Color(0xFF3D90CE), Color(0xFF3D90CE), Color(0xFF7FD8EA), Color(0xFF00606B)),
        TokenExpectation("KdOnPrimary", { KdOnPrimary }, Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFF00363F), Color(0xFFFFFFFF)),
        TokenExpectation("KdAccent", { KdAccent }, Color(0xFF3D90CE), Color(0xFF3D90CE), Color(0xFFFFD23E), Color(0xFF6B2F5B)),
        TokenExpectation("KdOnError", { KdOnError }, Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFF3B0010), Color(0xFFFFFFFF)),
        TokenExpectation("KdSuccess", { KdSuccess }, Color(0xFF48C744), Color(0xFF2E7D32), Color(0xFF3CE66B), Color(0xFF2E6B2F)),
        TokenExpectation("KdWarning", { KdWarning }, Color(0xFFE8A030), Color(0xFFB26A00), Color(0xFFFFA300), Color(0xFF8F5300)),
        TokenExpectation("KdError", { KdError }, Color(0xFFE54343), Color(0xFFC62828), Color(0xFFFF5C7A), Color(0xFFA8231C)),
        TokenExpectation("KdInfo", { KdInfo }, Color(0xFF3D90CE), Color(0xFF1E73B8), Color(0xFF5CBDFF), Color(0xFF1D5A9E)),
    )

    @Test
    fun `Default lock - every public token equals today's literal, in dark and in light`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)

        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        tokens.forEach { assertEquals(it.defaultDark, it.getter(), "${it.name} in Default dark") }

        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        tokens.forEach { assertEquals(it.defaultLight, it.getter(), "${it.name} in Default light") }
    }

    @Test
    fun `Retro - every token equals its spec value, in dark and in light`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)

        ThemeManager.syncFromPreferences(ThemeMode.DARK)
        tokens.forEach { assertEquals(it.retroDark, it.getter(), "${it.name} in Retro dark") }

        ThemeManager.syncFromPreferences(ThemeMode.LIGHT)
        tokens.forEach { assertEquals(it.retroLight, it.getter(), "${it.name} in Retro light") }
    }

    // WCAG 2.x contrast ratio: (L_lighter + 0.05) / (L_darker + 0.05), where L
    // is Color.luminance() — the same relative-luminance formula §3.2's
    // numbers were computed with.
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `contrast guard - retro meets its WCAG floors in dark and in light`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)

        listOf(ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            ThemeManager.syncFromPreferences(mode)
            val label = "Retro $mode"

            val foregrounds = listOf(
                "TextPrimary" to KdTextPrimary,
                "TextSecondary" to KdTextSecondary,
                "Success" to KdSuccess,
                "Warning" to KdWarning,
                "Error" to KdError,
                "Info" to KdInfo,
                "Primary" to KdPrimary,
            )
            val backgrounds = listOf(
                "Background" to KdBackground,
                "Surface" to KdSurface,
                "SurfaceVariant" to KdSurfaceVariant,
            )
            foregrounds.forEach { (fgName, fg) ->
                backgrounds.forEach { (bgName, bg) ->
                    val ratio = contrastRatio(fg, bg)
                    assertTrue(ratio >= 4.5f, "$label: $fgName on $bgName is $ratio, want >= 4.5")
                }
            }

            val errorOnSelected = contrastRatio(KdError, KdSelected)
            assertTrue(errorOnSelected >= 3.0f, "$label: Error on Selected is $errorOnSelected, want >= 3.0")

            val onPrimaryOnPrimary = contrastRatio(KdOnPrimary, KdPrimary)
            assertTrue(onPrimaryOnPrimary >= 4.5f, "$label: OnPrimary on Primary is $onPrimaryOnPrimary, want >= 4.5")

            val onErrorOnError = contrastRatio(KdOnError, KdError)
            assertTrue(onErrorOnError >= 4.5f, "$label: OnError on Error is $onErrorOnError, want >= 4.5")

            val accentOnBackground = contrastRatio(KdAccent, KdBackground)
            assertTrue(accentOnBackground >= 4.5f, "$label: Accent on Background is $accentOnBackground, want >= 4.5")
        }
    }

    @Test
    fun `previewColorsFor reads the palette table for both styles and both modes`() {
        listOf(ThemeStyle.DEFAULT, ThemeStyle.RETRO).forEach { style ->
            listOf(true, false).forEach { dark ->
                val c = kdPaletteSpec(style, ThemePalette.STYLE).variant(dark).colors
                assertEquals(
                    ThemePreviewColors(c.sidebarBg, c.background, c.surface, c.textPrimary, c.border, c.primary),
                    previewColorsFor(style, ThemePalette.STYLE, dark),
                    "previewColorsFor($style, STYLE, dark = $dark)",
                )
            }
        }
    }
}
