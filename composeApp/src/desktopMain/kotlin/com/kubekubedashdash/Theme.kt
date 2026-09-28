package com.kubekubedashdash

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

object ThemeManager {
    private var _mode by mutableStateOf(PreferenceRepository.themeMode.value)
    private var _isDarkTheme by mutableStateOf(_mode != ThemeMode.LIGHT)
    private var _style by mutableStateOf(PreferenceRepository.themeStyle.value)

    val mode: ThemeMode get() = _mode

    // For SYSTEM mode, KubeDashTheme keeps this in sync via applySystemDarkTheme.
    val isDarkTheme: Boolean get() = _isDarkTheme

    /** Orthogonal to [mode] (D1) — see [ThemeStyle]. */
    val style: ThemeStyle get() = _style

    /** True only for [ThemeStyle.RETRO], independent of [mode]. */
    val isRetro: Boolean get() = _style == ThemeStyle.RETRO

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

    /** Key any `remember` that computes colours on this — it changes exactly when a computed `Kd*` value would. */
    val paletteKey: Any get() = _style to _isDarkTheme
}

private val KdBackgroundDark = Color(0xFF1E2124)
private val KdSidebarBgDark = Color(0xFF161819)

// KdSurfaceDark sits on top of KdBackgroundDark (e.g. cards on the cluster
// overview). The previous #252A31 was only ~7 lightness units above the
// background, making cards almost float; #2A3038 widens the gap so Surface,
// SurfaceVariant, and Background each have a visible step.
private val KdSurfaceDark = Color(0xFF2A3038)
private val KdSurfaceVariantDark = Color(0xFF323845)
private val KdTextPrimaryDark = Color(0xFFC8D1DC)
private val KdTextSecondaryDark = Color(0xFF8B95A1)

// Brighter than KdTextSecondary so placeholder/hint text stays readable
// against KdSurface and KdSurfaceVariant. Light value matches secondary
// because light-mode contrast is already sufficient.
private val KdTextPlaceholderDark = Color(0xFF94A3B8)

// Maximum-contrast body text for dense readouts on KdSurface — the drawer's
// capture pane, where KdTextPrimary's 8.6:1 tested as legible but read as
// washed out at labelSmall sizes. Light value matches primary, which is
// already 14.6:1 on white.
private val KdTextBrightDark = Color(0xFFFFFFFF)

// More visible border on dark — the old #2E3440 was indistinguishable from
// the surface so card outlines never registered. #3A4150 shows a soft
// hairline without competing with the content.
private val KdBorderDark = Color(0xFF3A4150)
private val KdHoverDark = Color(0xFF333944)
private val KdSelectedDark = Color(0xFF1A3A5C)

private val KdBackgroundLight = Color(0xFFF8FAFC)
private val KdSidebarBgLight = Color(0xFFFFFFFF)
private val KdSurfaceLight = Color(0xFFFFFFFF)
private val KdSurfaceVariantLight = Color(0xFFF1F5F9)
private val KdTextPrimaryLight = Color(0xFF1E293B)
private val KdTextSecondaryLight = Color(0xFF64748B)
private val KdTextPlaceholderLight = Color(0xFF64748B)
private val KdBorderLight = Color(0xFFE2E8F0)
private val KdHoverLight = Color(0xFFF1F5F9)
private val KdSelectedLight = Color(0xFFDBEAFE)

// Retro-dark (§3.1): CRT arcade — deep blue-black tube, cyan phosphor
// primary, coin-gold heading accent (KdAccent, below).
private val KdBackgroundRetroDark = Color(0xFF12142B)
private val KdSidebarBgRetroDark = Color(0xFF0A0B1A)
private val KdSurfaceRetroDark = Color(0xFF1E2240)
private val KdSurfaceVariantRetroDark = Color(0xFF272C50)
private val KdTextPrimaryRetroDark = Color(0xFFE3E6F5)
private val KdTextSecondaryRetroDark = Color(0xFFADB3D6)
private val KdTextPlaceholderRetroDark = Color(0xFFADB3D6)
private val KdTextBrightRetroDark = Color(0xFFFFFFFF)
private val KdBorderRetroDark = Color(0xFF3B4275)
private val KdHoverRetroDark = Color(0xFF2E3460)
private val KdSelectedRetroDark = Color(0xFF1E4A56)

// Retro-light (§3.1): "paper terminal" — cream field, dark-brown ink,
// deep-teal primary, plum accent. Rejected the reference's Game Boy
// pea-green LCD because it erases "green = healthy" (D12).
private val KdBackgroundRetroLight = Color(0xFFEFE7D2)
private val KdSidebarBgRetroLight = Color(0xFFE6DCC3)
private val KdSurfaceRetroLight = Color(0xFFF8F2E3)
private val KdSurfaceVariantRetroLight = Color(0xFFE8DEC6)
private val KdTextPrimaryRetroLight = Color(0xFF2B2418)
private val KdTextSecondaryRetroLight = Color(0xFF5A4F3C)
private val KdTextPlaceholderRetroLight = Color(0xFF5A4F3C)
private val KdTextBrightRetroLight = Color(0xFF2B2418)
private val KdBorderRetroLight = Color(0xFFCBBE9E)
private val KdHoverRetroLight = Color(0xFFE3D8BC)
private val KdSelectedRetroLight = Color(0xFFCFE3E0)

/**
 * Every `Kd*` getter branches through this: Retro first, then dark/light.
 * `ThemeManager.isRetro` and `isDarkTheme` are the same two reads every
 * getter already made, just factored once (D2, D3).
 */
private fun pick(dark: Color, light: Color, retroDark: Color, retroLight: Color): Color = if (ThemeManager.isRetro) {
    if (ThemeManager.isDarkTheme) retroDark else retroLight
} else {
    if (ThemeManager.isDarkTheme) dark else light
}

val KdBackground: Color get() = pick(KdBackgroundDark, KdBackgroundLight, KdBackgroundRetroDark, KdBackgroundRetroLight)
val KdSidebarBg: Color get() = pick(KdSidebarBgDark, KdSidebarBgLight, KdSidebarBgRetroDark, KdSidebarBgRetroLight)
val KdSurface: Color get() = pick(KdSurfaceDark, KdSurfaceLight, KdSurfaceRetroDark, KdSurfaceRetroLight)
val KdSurfaceVariant: Color get() = pick(KdSurfaceVariantDark, KdSurfaceVariantLight, KdSurfaceVariantRetroDark, KdSurfaceVariantRetroLight)

// Default primary/accent are identical (§3.1: KdAccent == KdPrimary in
// Default); Retro splits them so gold headings don't read as KdWarning amber
// next to a cyan-primary UI (D11).
private val KdPrimaryDefault = Color(0xFF3D90CE)
private val KdPrimaryRetroDark = Color(0xFF7FD8EA)
private val KdPrimaryRetroLight = Color(0xFF00606B)
private val KdOnPrimaryRetroDark = Color(0xFF00363F)
private val KdAccentRetroDark = Color(0xFFFFD23E)
private val KdAccentRetroLight = Color(0xFF6B2F5B)
private val KdOnErrorRetroDark = Color(0xFF3B0010)

val KdPrimary: Color get() = pick(KdPrimaryDefault, KdPrimaryDefault, KdPrimaryRetroDark, KdPrimaryRetroLight)
val KdOnPrimary: Color get() = pick(Color.White, Color.White, KdOnPrimaryRetroDark, Color.White)

/** Heading-only arcade accent (D5, D11). Value-identical to [KdPrimary] in Default. */
val KdAccent: Color get() = pick(KdPrimaryDefault, KdPrimaryDefault, KdAccentRetroDark, KdAccentRetroLight)

/** Content colour for a [KdError]-filled surface. Value-identical to white in Default. */
val KdOnError: Color get() = pick(Color.White, Color.White, KdOnErrorRetroDark, Color.White)

val KdTextPrimary: Color get() = pick(KdTextPrimaryDark, KdTextPrimaryLight, KdTextPrimaryRetroDark, KdTextPrimaryRetroLight)
val KdTextSecondary: Color get() = pick(KdTextSecondaryDark, KdTextSecondaryLight, KdTextSecondaryRetroDark, KdTextSecondaryRetroLight)
val KdTextPlaceholder: Color get() = pick(KdTextPlaceholderDark, KdTextPlaceholderLight, KdTextPlaceholderRetroDark, KdTextPlaceholderRetroLight)
val KdTextBright: Color get() = pick(KdTextBrightDark, KdTextPrimaryLight, KdTextBrightRetroDark, KdTextBrightRetroLight)

// Status colors. The dark variants stay vivid (good contrast on near-black);
// the light variants are darkened so they still meet WCAG AA on white card
// backgrounds. The previous single value (e.g. KdSuccess #48C744) on
// KdSurfaceLight #FFFFFF only reached ~2.4:1, well below the 4.5:1 bar for
// normal text.
private val KdSuccessDark = Color(0xFF48C744)
private val KdSuccessLight = Color(0xFF2E7D32)
private val KdWarningDark = Color(0xFFE8A030)
private val KdWarningLight = Color(0xFFB26A00)
private val KdErrorDark = Color(0xFFE54343)
private val KdErrorLight = Color(0xFFC62828)
private val KdInfoDark = Color(0xFF3D90CE)
private val KdInfoLight = Color(0xFF1E73B8)

// Retro status colours (§3.1). Status meaning is kept in both variants (D8).
private val KdSuccessRetroDark = Color(0xFF3CE66B)
private val KdSuccessRetroLight = Color(0xFF2E6B2F)
private val KdWarningRetroDark = Color(0xFFFFA300)
private val KdWarningRetroLight = Color(0xFF8F5300)
private val KdErrorRetroDark = Color(0xFFFF5C7A)
private val KdErrorRetroLight = Color(0xFFA8231C)
private val KdInfoRetroDark = Color(0xFF5CBDFF)
private val KdInfoRetroLight = Color(0xFF1D5A9E)

val KdSuccess: Color get() = pick(KdSuccessDark, KdSuccessLight, KdSuccessRetroDark, KdSuccessRetroLight)
val KdWarning: Color get() = pick(KdWarningDark, KdWarningLight, KdWarningRetroDark, KdWarningRetroLight)
val KdError: Color get() = pick(KdErrorDark, KdErrorLight, KdErrorRetroDark, KdErrorRetroLight)
val KdInfo: Color get() = pick(KdInfoDark, KdInfoLight, KdInfoRetroDark, KdInfoRetroLight)
val KdBorder: Color get() = pick(KdBorderDark, KdBorderLight, KdBorderRetroDark, KdBorderRetroLight)
val KdHover: Color get() = pick(KdHoverDark, KdHoverLight, KdHoverRetroDark, KdHoverRetroLight)
val KdSelected: Color get() = pick(KdSelectedDark, KdSelectedLight, KdSelectedRetroDark, KdSelectedRetroLight)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3D90CE),
    onPrimary = Color.White,
    secondary = Color(0xFF3D90CE),
    background = KdBackgroundDark,
    surface = KdSurfaceDark,
    surfaceVariant = KdSurfaceVariantDark,
    onBackground = KdTextPrimaryDark,
    onSurface = KdTextPrimaryDark,
    onSurfaceVariant = KdTextSecondaryDark,
    error = KdErrorDark,
    outline = KdBorderDark,
    outlineVariant = KdBorderDark,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3D90CE),
    onPrimary = Color.White,
    secondary = Color(0xFF3D90CE),
    background = KdBackgroundLight,
    surface = KdSurfaceLight,
    surfaceVariant = KdSurfaceVariantLight,
    onBackground = KdTextPrimaryLight,
    onSurface = KdTextPrimaryLight,
    onSurfaceVariant = KdTextSecondaryLight,
    error = KdErrorLight,
    outline = KdBorderLight,
    outlineVariant = KdBorderLight,
)

// Retro-dark M3 roles (§3.1, M3 role table). Unlike the default schemes,
// this sets the full surfaceContainer ramp because SettingsSection reads
// `surfaceContainer` (SettingsScreen.kt:223).
private val RetroDarkColorScheme = darkColorScheme(
    primary = KdPrimaryRetroDark,
    onPrimary = KdOnPrimaryRetroDark,
    primaryContainer = Color(0xFF1E4A56),
    onPrimaryContainer = Color(0xFFBDEBF7),
    secondary = KdAccentRetroDark,
    onSecondary = Color(0xFF241A00),
    secondaryContainer = Color(0xFF5C4A00),
    onSecondaryContainer = Color(0xFFFFE99C),
    tertiary = Color(0xFFFF77A8),
    onTertiary = Color(0xFF54082C),
    background = KdBackgroundRetroDark,
    surface = KdSurfaceRetroDark,
    surfaceVariant = KdSurfaceVariantRetroDark,
    onBackground = KdTextPrimaryRetroDark,
    onSurface = KdTextPrimaryRetroDark,
    onSurfaceVariant = KdTextSecondaryRetroDark,
    error = KdErrorRetroDark,
    onError = KdOnErrorRetroDark,
    outline = KdBorderRetroDark,
    outlineVariant = KdBorderRetroDark,
    surfaceContainerLowest = Color(0xFF0A0B1A),
    surfaceContainerLow = Color(0xFF191C38),
    surfaceContainer = Color(0xFF1E2240),
    surfaceContainerHigh = Color(0xFF272C50),
    surfaceContainerHighest = Color(0xFF313763),
    surfaceDim = Color(0xFF12142B),
    surfaceBright = Color(0xFF313763),
)

// Retro-light M3 roles (§3.1).
private val RetroLightColorScheme = lightColorScheme(
    primary = KdPrimaryRetroLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE3E0),
    onPrimaryContainer = Color(0xFF00363B),
    secondary = KdAccentRetroLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBD6E3),
    onSecondaryContainer = Color(0xFF2E0F27),
    tertiary = Color(0xFF6B4F1D),
    onTertiary = Color.White,
    background = KdBackgroundRetroLight,
    surface = KdSurfaceRetroLight,
    surfaceVariant = KdSurfaceVariantRetroLight,
    onBackground = KdTextPrimaryRetroLight,
    onSurface = KdTextPrimaryRetroLight,
    onSurfaceVariant = KdTextSecondaryRetroLight,
    error = KdErrorRetroLight,
    onError = Color.White,
    outline = KdBorderRetroLight,
    outlineVariant = KdBorderRetroLight,
    surfaceContainerLowest = Color(0xFFFFFBF2),
    surfaceContainerLow = Color(0xFFF8F2E3),
    surfaceContainer = Color(0xFFF2EAD6),
    surfaceContainerHigh = Color(0xFFEBE2CB),
    surfaceContainerHighest = Color(0xFFE3D8BC),
    surfaceDim = Color(0xFFE6DCC3),
    surfaceBright = Color(0xFFFFFBF2),
)

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
    val systemIsDark = isSystemInDarkTheme()
    LaunchedEffect(systemIsDark, ThemeManager.mode) {
        ThemeManager.applySystemDarkTheme(systemIsDark)
    }
    val colorScheme = if (ThemeManager.isRetro) {
        if (ThemeManager.isDarkTheme) RetroDarkColorScheme else RetroLightColorScheme
    } else {
        if (ThemeManager.isDarkTheme) DarkColorScheme else LightColorScheme
    }
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
            LocalRippleThemeConfiguration provides RippleDefaults.InsetFocusRingRippleThemeConfiguration,
        ) {
            CopyFeedbackHost { ActionFeedbackHost { content() } }
        }
    }
}
