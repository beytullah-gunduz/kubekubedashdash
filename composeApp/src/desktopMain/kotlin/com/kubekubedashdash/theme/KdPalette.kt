package com.kubekubedashdash.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.RippleThemeConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Every colour token of one palette in one mode (D9). The `Kd*` getters in Theme.kt read these
 * through ThemeManager.colors; UI code never reads a KdColors directly.
 */
@Immutable
data class KdColors(
    val background: Color,
    val sidebarBg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    val textBright: Color,
    val border: Color,
    val hover: Color,
    val selected: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onError: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    /** M3 `secondary`: the focus ring's outer stroke. */
    val focus: Color,
    /** M3 `onSecondary`: the focus ring's inner stroke. */
    val onFocus: Color,
    val seriesMemory: Color,
    val graphEdge: Color,
    val syntaxKey: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxBool: Color,
    val syntaxComment: Color,
    val terminalFg: Color,
    val terminalBg: Color,
)

/** The colour-blind-safe status set that replaces the status quartet when the switch is on (D5). */
@Immutable
data class KdStatusColors(
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val onError: Color,
)

/** One palette in one mode, with its colour-blind variant and both M3 schemes built once. */
class KdPaletteVariant(
    val colors: KdColors,
    val cvd: KdStatusColors,
    val isDark: Boolean,
    /** The terminal's 16 ANSI colours (index 0-15), or null for jediterm's default xterm palette (D13). */
    val ansi: List<Color>? = null,
    /** A hand-written M3 scheme (Retro, D11; Default until WS2). Null derives one from [colors]. */
    schemeOverride: ColorScheme? = null,
) {
    init {
        require(ansi == null || ansi.size == 16) { "ansi must hold exactly 16 colours" }
    }

    val cvdColors: KdColors = colors.copy(
        success = cvd.success,
        warning = cvd.warning,
        error = cvd.error,
        info = cvd.info,
        onError = cvd.onError,
    )
    val scheme: ColorScheme = schemeOverride ?: kdDeriveScheme(colors, isDark)
    val cvdScheme: ColorScheme =
        schemeOverride?.copy(error = cvd.error, onError = cvd.onError) ?: kdDeriveScheme(cvdColors, isDark)
}

/** A palette in both modes plus its non-colour traits (D2). */
class KdPaletteSpec(
    val dark: KdPaletteVariant,
    val light: KdPaletteVariant,
    /** Width of every card and field outline (kdOutlineWidth, WS4): 2 dp only in High Contrast. */
    val outlineWidth: Dp = 1.dp,
    /** The app-wide focus ring (LocalRippleThemeConfiguration). */
    val focusRing: RippleThemeConfiguration = RippleDefaults.InsetFocusRingRippleThemeConfiguration,
) {
    init {
        require(dark.isDark && !light.isDark) { "dark/light variants swapped" }
    }

    fun variant(dark: Boolean): KdPaletteVariant = if (dark) this.dark else light
}

/**
 * 8-bit sRGB channel lerp, rounded half up — the formula §3.3's derived literals were computed
 * with. Never Compose's lerp, which interpolates in Oklab.
 */
fun kdMix(a: Color, b: Color, t: Float): Color {
    fun channel(x: Float, y: Float): Float {
        val x8 = (x * 255f).roundToInt()
        val y8 = (y * 255f).roundToInt()
        return floor(x8 + (y8 - x8) * t + 0.5f) / 255f
    }
    return Color(channel(a.red, b.red), channel(a.green, b.green), channel(a.blue, b.blue))
}

/** Black or white, whichever has the higher WCAG contrast on [fill] (ties go to black). */
fun kdInkOn(fill: Color): Color {
    val l = fill.luminance()
    val black = (l + 0.05f) / 0.05f
    val white = 1.05f / (l + 0.05f)
    return if (black >= white) Color.Black else Color.White
}

/** A grey with the same WCAG relative luminance as [c] (Monochrome kind colours, D15). */
fun kdGreyOf(c: Color): Color {
    val y = c.luminance()
    val v = if (y <= 0.0031308f) 12.92f * y else 1.055f * y.pow(1f / 2.4f) - 0.055f
    return Color(v, v, v)
}

/**
 * The full M3 role set from a palette's tokens (D7). Every role a material3 component reads is
 * set explicitly so no component falls back to the Material baseline (lavender) colours. The
 * twelve `*Fixed` roles stay at baseline: no material3 1.12.0-alpha03 component token reads them.
 */
fun kdDeriveScheme(c: KdColors, dark: Boolean): ColorScheme = if (dark) {
    val highest = kdMix(c.surfaceVariant, c.textPrimary, 0.08f)
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.selected,
        onPrimaryContainer = c.textPrimary,
        inversePrimary = c.primary,
        secondary = c.focus,
        onSecondary = c.onFocus,
        secondaryContainer = c.selected,
        onSecondaryContainer = c.textPrimary,
        tertiary = c.accent,
        onTertiary = kdInkOn(c.accent),
        tertiaryContainer = c.selected,
        onTertiaryContainer = c.textPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceVariant,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.primary,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.surface,
        error = c.error,
        onError = c.onError,
        errorContainer = kdMix(c.surface, c.error, 0.18f),
        onErrorContainer = c.textPrimary,
        outline = c.border,
        outlineVariant = c.border,
        scrim = Color.Black,
        surfaceBright = highest,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceVariant,
        surfaceContainerHighest = highest,
        surfaceContainerLow = c.background,
        surfaceContainerLowest = c.sidebarBg,
        surfaceDim = c.sidebarBg,
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.selected,
        onPrimaryContainer = c.textPrimary,
        inversePrimary = c.primary,
        secondary = c.focus,
        onSecondary = c.onFocus,
        secondaryContainer = c.selected,
        onSecondaryContainer = c.textPrimary,
        tertiary = c.accent,
        onTertiary = kdInkOn(c.accent),
        tertiaryContainer = c.selected,
        onTertiaryContainer = c.textPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceVariant,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.primary,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.surface,
        error = c.error,
        onError = c.onError,
        errorContainer = kdMix(c.surface, c.error, 0.18f),
        onErrorContainer = c.textPrimary,
        outline = c.border,
        outlineVariant = c.border,
        scrim = Color.Black,
        surfaceBright = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = kdMix(c.surface, c.surfaceVariant, 0.5f),
        surfaceContainerHighest = c.surfaceVariant,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.surface,
        surfaceDim = kdMix(c.background, c.textPrimary, 0.06f),
    )
}
