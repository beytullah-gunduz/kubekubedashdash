package com.kubekubedashdash.theme

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The palette gate (theme-expansion plan §3.1): every palette x mode x colour-blind on/off must pass
 * the contrast rules G1-G13, and every colour-blind set must pass the separation rule G14. Each
 * failure is collected, not thrown, so one run lists every miss.
 *
 * Reads only the palette tables; touches no [com.kubekubedashdash.ThemeManager] state.
 */
class PaletteGateTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun palettes(): List<Pair<String, KdPaletteSpec>> = buildList {
        add("DEFAULT" to kdPaletteSpec(ThemeStyle.DEFAULT, ThemePalette.STYLE))
        add("RETRO" to kdPaletteSpec(ThemeStyle.RETRO, ThemePalette.STYLE))
        ThemePalette.entries.filter { it != ThemePalette.STYLE }
            .forEach { add(it.name to kdPaletteSpec(ThemeStyle.DEFAULT, it)) }
    }

    /** Palettes exempt from the gate. WS2 fixed Default's values, so this stays empty. */
    private val pendingWs2 = emptySet<String>()

    private fun gateFailures(name: String, dark: Boolean, cvd: Boolean, c: KdColors): List<String> {
        val t = if (name == "HIGH_CONTRAST") 7f else 4.5f
        val tn = if (name == "HIGH_CONTRAST") 4.5f else 3f
        val prefix = "$name ${if (dark) "dark" else "light"} ${if (cvd) "cvd" else "std"}"
        val failures = mutableListOf<String>()

        fun rule(g: String, fgName: String, fg: Color, bgName: String, bg: Color, min: Float) {
            val ratio = contrast(fg, bg)
            if (ratio < min) failures += "$prefix $g $fgName/$bgName = $ratio"
        }

        val sixSurfaces = listOf(
            "background" to c.background,
            "sidebarBg" to c.sidebarBg,
            "surface" to c.surface,
            "surfaceVariant" to c.surfaceVariant,
            "selected" to c.selected,
            "hover" to c.hover,
        )
        val backgroundSurfaceVariant = listOf(
            "background" to c.background,
            "surface" to c.surface,
            "surfaceVariant" to c.surfaceVariant,
        )
        val backgroundSurface = listOf("background" to c.background, "surface" to c.surface)

        // G1: textPrimary on all six, 7.0 in every palette.
        sixSurfaces.forEach { (bgName, bg) -> rule("G1", "textPrimary", c.textPrimary, bgName, bg, 7f) }
        // G2: textSecondary on the same six.
        sixSurfaces.forEach { (bgName, bg) -> rule("G2", "textSecondary", c.textSecondary, bgName, bg, t) }
        // G3: textPlaceholder on surface and surfaceVariant.
        listOf("surface" to c.surface, "surfaceVariant" to c.surfaceVariant).forEach { (bgName, bg) ->
            rule("G3", "textPlaceholder", c.textPlaceholder, bgName, bg, t)
        }
        // G4: the status quartet on background, surface, surfaceVariant.
        listOf("success" to c.success, "warning" to c.warning, "error" to c.error, "info" to c.info).forEach { (fgName, fg) ->
            backgroundSurfaceVariant.forEach { (bgName, bg) -> rule("G4", fgName, fg, bgName, bg, t) }
        }
        // G5: error on selected (3.0; 4.5 in High Contrast).
        rule("G5", "error", c.error, "selected", c.selected, if (name == "HIGH_CONTRAST") 4.5f else 3f)
        // G6: primary and accent on background and surface.
        listOf("primary" to c.primary, "accent" to c.accent).forEach { (fgName, fg) ->
            backgroundSurface.forEach { (bgName, bg) -> rule("G6", fgName, fg, bgName, bg, t) }
        }
        // G7: the on-colours on their fills.
        rule("G7", "onPrimary", c.onPrimary, "primary", c.primary, t)
        rule("G7", "onError", c.onError, "error", c.error, t)
        // G8: textBright on surface.
        rule("G8", "textBright", c.textBright, "surface", c.surface, t)
        // G9: focus on background, non-text 3.0 in every palette.
        rule("G9", "focus", c.focus, "background", c.background, 3f)
        // G10: High Contrast only, the border on background and surface.
        if (name == "HIGH_CONTRAST") {
            backgroundSurface.forEach { (bgName, bg) -> rule("G10", "border", c.border, bgName, bg, 4.5f) }
        }
        // G11: every syntax colour on background and surface.
        listOf(
            "syntaxKey" to c.syntaxKey,
            "syntaxString" to c.syntaxString,
            "syntaxNumber" to c.syntaxNumber,
            "syntaxBool" to c.syntaxBool,
            "syntaxComment" to c.syntaxComment,
        ).forEach { (fgName, fg) ->
            backgroundSurface.forEach { (bgName, bg) -> rule("G11", fgName, fg, bgName, bg, t) }
        }
        // G12: seriesMemory on background and surface, graphEdge on background (non-text, Tn).
        backgroundSurface.forEach { (bgName, bg) -> rule("G12", "seriesMemory", c.seriesMemory, bgName, bg, tn) }
        rule("G12", "graphEdge", c.graphEdge, "background", c.background, tn)
        // G13: the terminal's default pair.
        rule("G13", "terminalFg", c.terminalFg, "terminalBg", c.terminalBg, t)

        return failures
    }

    @Test
    fun `every palette passes the contrast gate`() {
        val failures = mutableListOf<String>()
        for ((name, spec) in palettes()) {
            if (name in pendingWs2) continue
            for (dark in listOf(true, false)) {
                val variant = spec.variant(dark)
                failures += gateFailures(name, dark, cvd = false, c = variant.colors)
                failures += gateFailures(name, dark, cvd = true, c = variant.cvdColors)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `every colour-blind set separates success, warning and error`() {
        val failures = mutableListOf<String>()
        for ((name, spec) in palettes()) {
            for (dark in listOf(true, false)) {
                val c = spec.variant(dark).cvdColors
                val pairs = listOf(
                    "success/warning" to (c.success to c.warning),
                    "success/error" to (c.success to c.error),
                    "warning/error" to (c.warning to c.error),
                )
                for (vision in Vision.entries) {
                    for ((pairName, pair) in pairs) {
                        val distance = oklabDistance(pair.first, pair.second, vision)
                        if (distance < 0.10f) {
                            failures += "$name ${if (dark) "dark" else "light"} cvd G14 $pairName $vision = $distance"
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `no palette is exempt from the contrast gate`() {
        assertTrue(pendingWs2.isEmpty(), "pendingWs2 must stay empty, but names: $pendingWs2")
    }
}
