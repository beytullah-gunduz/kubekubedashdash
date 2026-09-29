package com.kubekubedashdash

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.departure_mono_regular
import com.kubekubedashdash.resources.inter_medium
import com.kubekubedashdash.resources.inter_regular
import com.kubekubedashdash.resources.inter_semibold
import com.kubekubedashdash.resources.jetbrains_mono_regular
import com.kubekubedashdash.resources.sixtyfour_regular
import com.kubekubedashdash.theme.KdColors
import com.kubekubedashdash.theme.KdPaletteSpec
import com.kubekubedashdash.theme.KdPaletteVariant
import com.kubekubedashdash.theme.kdPaletteSpec
import com.kubekubedashdash.ui.components.CopyFeedbackHost
import com.kubekubedashdash.ui.feedback.ActionFeedbackHost
import org.jetbrains.compose.resources.Font

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * Orthogonal to [ThemeMode] (D1): Retro layers a pixel-chrome, squared-corner,
 * CRT-motion look on top of whichever mode is active — Dark, Light or System
 * keep choosing the palette; this chooses the voice.
 */
enum class ThemeStyle { DEFAULT, RETRO }

/**
 * Orthogonal to [ThemeMode] and [ThemeStyle] (D1): which colours. STYLE is "the style's own
 * palette" — Default's under Default, Retro's under Retro. Every other entry swaps colours only;
 * the style still owns fonts, corners and motion.
 */
enum class ThemePalette(val label: String) {
    STYLE(""), // the Settings card shows the current style's name instead
    HIGH_CONTRAST("High contrast"),
    MONOCHROME("Monochrome"),
}

object ThemeManager {
    private var _mode by mutableStateOf(PreferenceRepository.themeMode.value)
    private var _isDarkTheme by mutableStateOf(_mode != ThemeMode.LIGHT)
    private var _style by mutableStateOf(PreferenceRepository.themeStyle.value)
    private var _palette by mutableStateOf(PreferenceRepository.themePalette.value)
    private var _cvd by mutableStateOf(false) // WS6 seeds it from PreferenceRepository

    val mode: ThemeMode get() = _mode

    // For SYSTEM mode, KubeDashTheme keeps this in sync via applySystemDarkTheme.
    val isDarkTheme: Boolean get() = _isDarkTheme

    /** Orthogonal to [mode] (D1) — see [ThemeStyle]. */
    val style: ThemeStyle get() = _style

    /** True only for [ThemeStyle.RETRO], independent of [mode]. */
    val isRetro: Boolean get() = _style == ThemeStyle.RETRO

    /** Orthogonal to [mode] and [style] (D1) — see [ThemePalette]. */
    val palette: ThemePalette get() = _palette

    /** The colour-blind-safe status switch (D5). */
    val cvdSafeStatus: Boolean get() = _cvd

    /** The active palette in both modes (D9). */
    val spec: KdPaletteSpec get() = kdPaletteSpec(_style, _palette)

    /** The active palette in the active mode. */
    val variant: KdPaletteVariant get() = spec.variant(_isDarkTheme)

    /** Every Kd* getter reads this (D9, D10). */
    val colors: KdColors get() = variant.let { if (_cvd) it.cvdColors else it.colors }

    /** The M3 scheme KubeDashTheme installs. */
    val scheme: ColorScheme get() = variant.let { if (_cvd) it.cvdScheme else it.scheme }

    fun setMode(newMode: ThemeMode) {
        _mode = newMode
        PreferenceRepository.setThemeMode(newMode)
        when (newMode) {
            ThemeMode.LIGHT -> _isDarkTheme = false
            ThemeMode.DARK -> _isDarkTheme = true
            ThemeMode.SYSTEM -> Unit
        }
    }

    fun setStyle(newStyle: ThemeStyle) {
        _style = newStyle
        PreferenceRepository.setThemeStyle(newStyle)
    }

    fun setPalette(newPalette: ThemePalette) {
        _palette = newPalette
        PreferenceRepository.setThemePalette(newPalette)
    }

    internal fun applySystemDarkTheme(systemIsDark: Boolean) {
        if (_mode == ThemeMode.SYSTEM) {
            _isDarkTheme = systemIsDark
        }
    }

    /**
     * Applies the persisted mode without writing it back. `_mode` is read from
     * `PreferenceRepository.themeMode.value` once, at object init, which on a
     * cold launch is the compile-time default because the store is still
     * loading; KubeDashTheme collects the flow and calls this, so the saved
     * choice lands once the read completes (F11). After [setMode] the flow
     * re-emits the same value and this is a no-op — no loop. A choice made before
     * the store's first seed would be clobbered by that seed and synced back
     * here (PreferenceRepository's documented launch-time window). The splash
     * keeps Settings, the app's only [setMode] caller, unreachable until the
     * seed; the screenshot generator, the other caller, awaits the seed itself.
     */
    internal fun syncFromPreferences(persisted: ThemeMode) {
        if (_mode == persisted) return
        _mode = persisted
        when (persisted) {
            ThemeMode.LIGHT -> _isDarkTheme = false
            ThemeMode.DARK -> _isDarkTheme = true
            ThemeMode.SYSTEM -> Unit // KubeDashTheme's effect re-runs on the mode change and applies the system value
        }
    }

    /** Applies the persisted style without writing it back — the same F11 shape as [syncFromPreferences]. */
    internal fun syncStyleFromPreferences(persisted: ThemeStyle) {
        if (_style == persisted) return
        _style = persisted
    }

    /** Applies the persisted palette without writing it back (the F11 shape of [syncStyleFromPreferences]). */
    internal fun syncPaletteFromPreferences(persisted: ThemePalette) {
        if (_palette == persisted) return
        _palette = persisted
    }

    /** Applies the persisted colour-blind switch without writing it back. */
    internal fun syncCvdFromPreferences(persisted: Boolean) {
        if (_cvd == persisted) return
        _cvd = persisted
    }

    /** Key any `remember` that computes colours on this — it changes exactly when a computed `Kd*` value would. */
    val paletteKey: Any get() = PaletteKey(_style, _palette, _isDarkTheme, _cvd)
}

private data class PaletteKey(val style: ThemeStyle, val palette: ThemePalette, val dark: Boolean, val cvd: Boolean)

val KdBackground: Color get() = ThemeManager.colors.background
val KdSidebarBg: Color get() = ThemeManager.colors.sidebarBg

// KdSurface sits on top of KdBackground (e.g. cards on the cluster overview);
// Surface, SurfaceVariant and Background each keep a visible step.
val KdSurface: Color get() = ThemeManager.colors.surface
val KdSurfaceVariant: Color get() = ThemeManager.colors.surfaceVariant

// Default primary/accent are identical (§3.1: KdAccent == KdPrimary in
// Default); Retro splits them so gold headings don't read as KdWarning amber
// next to a cyan-primary UI (D11).
val KdPrimary: Color get() = ThemeManager.colors.primary
val KdOnPrimary: Color get() = ThemeManager.colors.onPrimary

/** Heading-only arcade accent (D5, D11). Value-identical to [KdPrimary] in Default. */
val KdAccent: Color get() = ThemeManager.colors.accent

/** Content colour for a [KdError]-filled surface. */
val KdOnError: Color get() = ThemeManager.colors.onError

val KdTextPrimary: Color get() = ThemeManager.colors.textPrimary
val KdTextSecondary: Color get() = ThemeManager.colors.textSecondary

// Brighter than KdTextSecondary in dark so placeholder/hint text stays readable
// against KdSurface and KdSurfaceVariant.
val KdTextPlaceholder: Color get() = ThemeManager.colors.textPlaceholder

// Maximum-contrast body text for dense readouts on KdSurface — the drawer's
// capture pane, where KdTextPrimary tested as legible but read as washed out
// at labelSmall sizes.
val KdTextBright: Color get() = ThemeManager.colors.textBright

// Status colors. The dark variants stay vivid (good contrast on near-black);
// the light variants are darkened so they still meet WCAG AA on white card
// backgrounds.
val KdSuccess: Color get() = ThemeManager.colors.success
val KdWarning: Color get() = ThemeManager.colors.warning
val KdError: Color get() = ThemeManager.colors.error
val KdInfo: Color get() = ThemeManager.colors.info

// A visible hairline that shows against KdSurface without competing with content.
val KdBorder: Color get() = ThemeManager.colors.border
val KdHover: Color get() = ThemeManager.colors.hover
val KdSelected: Color get() = ThemeManager.colors.selected

// Tokens the palette model added (D9), beyond the original 19.

/** The memory series in charts and usage bars. */
val KdSeriesMemory: Color get() = ThemeManager.colors.seriesMemory

/** Topology and deployment-graph edges. */
val KdGraphEdge: Color get() = ThemeManager.colors.graphEdge

/** YAML highlighter: keys, strings, numbers, booleans and comments (D14). */
val KdSyntaxKey: Color get() = ThemeManager.colors.syntaxKey
val KdSyntaxString: Color get() = ThemeManager.colors.syntaxString
val KdSyntaxNumber: Color get() = ThemeManager.colors.syntaxNumber
val KdSyntaxBool: Color get() = ThemeManager.colors.syntaxBool
val KdSyntaxComment: Color get() = ThemeManager.colors.syntaxComment

/** The embedded terminal's default foreground and background (D13). */
val KdTerminalFg: Color get() = ThemeManager.colors.terminalFg
val KdTerminalBg: Color get() = ThemeManager.colors.terminalBg

// "tnum" = OpenType tabular-numerals feature. Forces digits to a fixed
// advance width so columns of CPU / Memory / Pods / IPs / ports / ages
// align across rows without ad-hoc Modifier.width(...) per cell.
private const val TABULAR_NUMS = "\"tnum\" 1"

/**
 * Bundled UI font: Inter (Regular / Medium / SemiBold). OFL 1.1, see
 * `composeResources/font/inter-LICENSE.txt`. Replaces the platform-default
 * `FontFamily.SansSerif` so the same metrics ship on macOS, Windows, and
 * Linux.
 */
@Composable
fun kdSansFamily(): FontFamily {
    val regular = Font(Res.font.inter_regular, weight = FontWeight.Normal, style = FontStyle.Normal)
    val medium = Font(Res.font.inter_medium, weight = FontWeight.Medium, style = FontStyle.Normal)
    val semibold = Font(Res.font.inter_semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal)
    return remember(regular, medium, semibold) {
        FontFamily(regular, medium, semibold)
    }
}

/**
 * The code-surface face (YAML pane, log viewer, prerequisites modal, …): JetBrains Mono in
 * Default, Departure Mono in Retro (D18). Reads [ThemeManager.isRetro], so every caller
 * recomposes onto the right face when the style flips.
 */
@Composable
fun kdMonoFamily(): FontFamily = if (ThemeManager.isRetro) kdRetroFamily() else kdJetBrainsMonoFamily()

/**
 * Bundled monospace font: JetBrains Mono Regular. OFL 1.1, see
 * `composeResources/font/jetbrains-mono-OFL.txt`. Default's code face; callers go through
 * [kdMonoFamily]. Internal only so tests can compare against it.
 */
@Composable
internal fun kdJetBrainsMonoFamily(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, weight = FontWeight.Normal, style = FontStyle.Normal)
    return remember(regular) { FontFamily(regular) }
}

/**
 * Bundled retro reading font: Departure Mono Regular (v1.500). OFL 1.1, see
 * `composeResources/font/departure-mono-OFL.txt`. Retro's voice for everything that is not
 * Sixtyfour chrome — body text, data, table cells, buttons, fields, and code through
 * [kdMonoFamily] (D17, D18). Single weight: Compose desktop never synthesizes bold, so heavier
 * weight requests render Regular.
 */
@Composable
fun kdRetroFamily(): FontFamily {
    val regular = Font(Res.font.departure_mono_regular, weight = FontWeight.Normal, style = FontStyle.Normal)
    return remember(regular) { FontFamily(regular) }
}

/**
 * Bundled pixel-chrome font: Sixtyfour Regular. OFL 1.1, see
 * `composeResources/font/sixtyfour-OFL.txt`. Sixtyfour is a variable font
 * (axes SCAN and BLED); this loads the default instance — both axes at 0,
 * the plain C64-style 8×8 pixel face — with no variation settings applied.
 * Retro fixed chrome only (D5, D19): headings, section labels and table column headers — never
 * cluster/resource data, body text, table cells, logs or YAML, which use [kdRetroFamily] (D17).
 */
@Composable
fun kdPixelFamily(): FontFamily {
    val regular = Font(Res.font.sixtyfour_regular, weight = FontWeight.Normal, style = FontStyle.Normal)
    return remember(regular) { FontFamily(regular) }
}

/**
 * 8×8 home-computer pixel faces like Sixtyfour fill the em square, where
 * Inter leaves side bearings — so they render noticeably larger optically
 * than Inter at an equal sp size. Chrome call sites that don't pin an
 * explicit retro size (see [retroChrome]) scale down by this factor instead.
 */
const val RETRO_TYPE_SCALE = 0.72f

/**
 * Chrome-only pixel voice (D5): unchanged outside retro; in retro swaps in the pixel face at
 * [size] (or [RETRO_TYPE_SCALE] × the base size), Normal weight, zero tracking. Line height is kept
 * so rows never shift. Never use on cluster/resource data, body text, table cells, logs or YAML.
 */
@Composable
fun TextStyle.retroChrome(size: TextUnit = TextUnit.Unspecified): TextStyle {
    if (!ThemeManager.isRetro) return this
    return copy(
        fontFamily = kdPixelFamily(),
        fontSize = when {
            size.isSpecified -> size

            // An unspecified base size cannot be scaled (TextUnit arithmetic throws on it).
            fontSize.isSpecified -> fontSize * RETRO_TYPE_SCALE

            else -> fontSize
        },
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
}

@Composable
private fun appTypography(sans: FontFamily): Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    bodyMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    bodySmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    labelLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    labelMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
    labelSmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = TABULAR_NUMS,
    ),
)

/**
 * The app's typography for the current style. Default is exactly [appTypography] over Inter (D3).
 * Retro (D17): all 30 slots move to [kdRetroFamily] with font synthesis off, except
 * headlineLarge/Medium, which take the Sixtyfour chrome voice (D5). headlineSmall stays on the
 * reading face on purpose: ResourceDetail.kt renders a resource *name* in it. Sizes and line
 * heights are unchanged — the two faces share cap and x-height, so rows never shift.
 */
@Composable
internal fun kdTypography(): Typography {
    val base = appTypography(sans = kdSansFamily())
    if (!ThemeManager.isRetro) return base
    val readingFace = kdRetroFamily()
    val pixel = kdPixelFamily()
    fun TextStyle.reading() = copy(fontFamily = readingFace, fontSynthesis = FontSynthesis.None)
    return base.copy(
        displayLarge = base.displayLarge.reading(),
        displayMedium = base.displayMedium.reading(),
        displaySmall = base.displaySmall.reading(),
        headlineLarge = base.headlineLarge.copy(fontFamily = pixel, fontSize = 20.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        headlineMedium = base.headlineMedium.copy(fontFamily = pixel, fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp, fontSynthesis = FontSynthesis.None),
        headlineSmall = base.headlineSmall.reading(),
        titleLarge = base.titleLarge.reading(),
        titleMedium = base.titleMedium.reading(),
        titleSmall = base.titleSmall.reading(),
        bodyLarge = base.bodyLarge.reading(),
        bodyMedium = base.bodyMedium.reading(),
        bodySmall = base.bodySmall.reading(),
        labelLarge = base.labelLarge.reading(),
        labelMedium = base.labelMedium.reading(),
        labelSmall = base.labelSmall.reading(),
        displayLargeEmphasized = base.displayLargeEmphasized.reading(),
        displayMediumEmphasized = base.displayMediumEmphasized.reading(),
        displaySmallEmphasized = base.displaySmallEmphasized.reading(),
        headlineLargeEmphasized = base.headlineLargeEmphasized.reading(),
        headlineMediumEmphasized = base.headlineMediumEmphasized.reading(),
        headlineSmallEmphasized = base.headlineSmallEmphasized.reading(),
        titleLargeEmphasized = base.titleLargeEmphasized.reading(),
        titleMediumEmphasized = base.titleMediumEmphasized.reading(),
        titleSmallEmphasized = base.titleSmallEmphasized.reading(),
        bodyLargeEmphasized = base.bodyLargeEmphasized.reading(),
        bodyMediumEmphasized = base.bodyMediumEmphasized.reading(),
        bodySmallEmphasized = base.bodySmallEmphasized.reading(),
        labelLargeEmphasized = base.labelLargeEmphasized.reading(),
        labelMediumEmphasized = base.labelMediumEmphasized.reading(),
        labelSmallEmphasized = base.labelSmallEmphasized.reading(),
    )
}

/**
 * The system (unscaled) density, unaffected by UI zoom. `ui/App.kt` reads
 * this — not `LocalDensity.current` — when it converts Compose window
 * coordinates into AWT screen points for cluster-chip drag-to-merge hit
 * testing (see `ui/ScreenGeometry.kt`); every other `LocalDensity.current`
 * read in the app is supposed to see the zoomed value.
 */
val LocalSystemDensity = staticCompositionLocalOf<Density> { error("no system density") }

// All eight shape slots squared (D5). The 8-slot primary constructor is
// material3 1.12.0-alpha03 Shapes.kt:81-90.
private val RetroShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
    largeIncreased = RoundedCornerShape(0.dp),
    extraLargeIncreased = RoundedCornerShape(0.dp),
    extraExtraLarge = RoundedCornerShape(0.dp),
)

// The library default, held once so Default doesn't build a fresh Shapes()
// on every recomposition.
private val DefaultShapes = Shapes()

/** Squares in Retro (D5's corner sweep, WS2); a plain [RoundedCornerShape] of this radius in Default. */
val Dp.kdCorner: RoundedCornerShape get() = RoundedCornerShape(if (ThemeManager.isRetro) 0.dp else this)

/** Width of every card and field outline (D2): 1 dp, 2 dp in High Contrast. */
val kdOutlineWidth: Dp get() = ThemeManager.spec.outlineWidth

/** Status-dot shape (D24): a square pixel in Retro, a circle in Default. */
val kdDotShape: Shape get() = if (ThemeManager.isRetro) RectangleShape else CircleShape

/**
 * Line-end cap for charts and progress indicators (D24): flat in Retro, round in Default —
 * M3's own default for every progress indicator (ProgressIndicatorDefaults, material3
 * 1.12.0-alpha03 ProgressIndicator.kt:849-855), so Default call sites are value-identical.
 */
val kdStrokeCap: StrokeCap get() = if (ThemeManager.isRetro) StrokeCap.Butt else StrokeCap.Round

/** Fixed chrome in caps in Retro, like the column headers (D19, D24); unchanged in Default. */
fun String.retroCaps(): String = if (ThemeManager.isRetro) uppercase() else this

@Composable
fun KubeDashTheme(content: @Composable () -> Unit) {
    // The saved theme arrives after the first composition (the store loads
    // asynchronously); follow the flow so it applies at launch (F11).
    val persistedMode by PreferenceRepository.themeMode.collectAsState()
    LaunchedEffect(persistedMode) {
        ThemeManager.syncFromPreferences(persistedMode)
    }
    val persistedStyle by PreferenceRepository.themeStyle.collectAsState()
    LaunchedEffect(persistedStyle) {
        ThemeManager.syncStyleFromPreferences(persistedStyle)
    }
    val persistedPalette by PreferenceRepository.themePalette.collectAsState()
    LaunchedEffect(persistedPalette) {
        ThemeManager.syncPaletteFromPreferences(persistedPalette)
    }
    val systemIsDark = isSystemInDarkTheme()
    LaunchedEffect(systemIsDark, ThemeManager.mode) {
        ThemeManager.applySystemDarkTheme(systemIsDark)
    }
    val colorScheme = ThemeManager.scheme
    val typography = kdTypography()
    // Theme the right-click ContextMenuArea popup. Compose's default uses
    // its own foundation colors and clashes with the Kd palette — give it
    // KdSurface / KdTextPrimary / KdHover so it visually matches the
    // dropdown we already use for the row's overflow `⋮` menu.
    val contextMenuRepresentation = remember(ThemeManager.paletteKey) {
        DefaultContextMenuRepresentation(
            backgroundColor = KdSurface,
            textColor = KdTextPrimary,
            itemHoverColor = KdHover,
        )
    }
    // Compose Desktop's default scrollbar is ~12% alpha and 8dp wide — close
    // to invisible against KdSurface, so dense lists (events, pods, jobs)
    // gave no signal that more rows were below the fold. Bump the thumb to
    // ~35% / 65% alpha and 10dp so the thumb-to-track ratio communicates
    // "there's a lot of content" at a glance, even when not hovered.
    val scrollbarBase = KdTextSecondary
    val scrollbarStyle = remember(ThemeManager.paletteKey) {
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 10.dp,
            shape = 5.dp.kdCorner,
            hoverDurationMillis = 200,
            unhoverColor = scrollbarBase.copy(alpha = 0.35f),
            hoverColor = scrollbarBase.copy(alpha = 0.65f),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = if (ThemeManager.isRetro) RetroShapes else DefaultShapes,
    ) {
        // UI zoom: scale density (not fontScale) so dp and sp grow together —
        // a true zoom that makes the hard-coded sp literals in the log/YAML
        // panes readable without touching them. LocalSystemDensity carries
        // the unscaled value on for the one site that must not see it.
        val uiScalePercent by PreferenceRepository.uiScalePercent.collectAsState()
        val base = LocalDensity.current
        val scale = uiScalePercent / 100f
        CompositionLocalProvider(
            LocalContextMenuRepresentation provides contextMenuRepresentation,
            LocalScrollbarStyle provides scrollbarStyle,
            LocalDensity provides Density(base.density * scale, base.fontScale),
            LocalSystemDensity provides base,
            // A visible keyboard focus ring app-wide, from one line.
            //
            // MaterialTheme itself provides `ripple()` as LocalIndication, so
            // bare `Modifier.clickable` and the Material 3 button/Surface family
            // (which passes `indication = ripple()` explicitly and ignores
            // LocalIndication) both end up at the same ripple node — and that
            // node reads LocalRippleThemeConfiguration and draws a real inset
            // focus ring when its `focus` is an InsetRing. The stock default is
            // the opacity variant, which is why focus was a near-invisible wash
            // on these surfaces. Overriding LocalIndication instead would reach
            // the bare clickables only by REPLACING their ripple, losing press
            // and hover feedback across the app to gain what this line already
            // gives them.
            LocalRippleThemeConfiguration provides ThemeManager.spec.focusRing,
        ) {
            CopyFeedbackHost { ActionFeedbackHost { content() } }
        }
    }
}
