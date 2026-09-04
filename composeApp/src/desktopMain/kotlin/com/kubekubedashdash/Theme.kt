package com.kubekubedashdash

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.data.repository.PreferenceRepository
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.inter_medium
import com.kubekubedashdash.resources.inter_regular
import com.kubekubedashdash.resources.inter_semibold
import com.kubekubedashdash.resources.jetbrains_mono_regular
import com.kubekubedashdash.ui.components.CopyFeedbackHost
import com.kubekubedashdash.ui.feedback.ActionFeedbackHost
import org.jetbrains.compose.resources.Font

enum class ThemeMode { LIGHT, DARK, SYSTEM }

object ThemeManager {
    private var _mode by mutableStateOf(PreferenceRepository.themeMode.value)
    private var _isDarkTheme by mutableStateOf(_mode != ThemeMode.LIGHT)

    val mode: ThemeMode get() = _mode

    // For SYSTEM mode, KubeDashTheme keeps this in sync via applySystemDarkTheme.
    val isDarkTheme: Boolean get() = _isDarkTheme

    fun setMode(newMode: ThemeMode) {
        _mode = newMode
        PreferenceRepository.setThemeMode(newMode)
        when (newMode) {
            ThemeMode.LIGHT -> _isDarkTheme = false
            ThemeMode.DARK -> _isDarkTheme = true
            ThemeMode.SYSTEM -> Unit
        }
    }

    internal fun applySystemDarkTheme(systemIsDark: Boolean) {
        if (_mode == ThemeMode.SYSTEM) {
            _isDarkTheme = systemIsDark
        }
    }
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

val KdBackground: Color get() = if (ThemeManager.isDarkTheme) KdBackgroundDark else KdBackgroundLight
val KdSidebarBg: Color get() = if (ThemeManager.isDarkTheme) KdSidebarBgDark else KdSidebarBgLight
val KdSurface: Color get() = if (ThemeManager.isDarkTheme) KdSurfaceDark else KdSurfaceLight
val KdSurfaceVariant: Color get() = if (ThemeManager.isDarkTheme) KdSurfaceVariantDark else KdSurfaceVariantLight
val KdPrimary = Color(0xFF3D90CE)
val KdOnPrimary = Color.White
val KdTextPrimary: Color get() = if (ThemeManager.isDarkTheme) KdTextPrimaryDark else KdTextPrimaryLight
val KdTextSecondary: Color get() = if (ThemeManager.isDarkTheme) KdTextSecondaryDark else KdTextSecondaryLight
val KdTextPlaceholder: Color get() = if (ThemeManager.isDarkTheme) KdTextPlaceholderDark else KdTextPlaceholderLight
val KdTextBright: Color get() = if (ThemeManager.isDarkTheme) KdTextBrightDark else KdTextPrimaryLight

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

val KdSuccess: Color get() = if (ThemeManager.isDarkTheme) KdSuccessDark else KdSuccessLight
val KdWarning: Color get() = if (ThemeManager.isDarkTheme) KdWarningDark else KdWarningLight
val KdError: Color get() = if (ThemeManager.isDarkTheme) KdErrorDark else KdErrorLight
val KdInfo: Color get() = if (ThemeManager.isDarkTheme) KdInfoDark else KdInfoLight
val KdBorder: Color get() = if (ThemeManager.isDarkTheme) KdBorderDark else KdBorderLight
val KdHover: Color get() = if (ThemeManager.isDarkTheme) KdHoverDark else KdHoverLight
val KdSelected: Color get() = if (ThemeManager.isDarkTheme) KdSelectedDark else KdSelectedLight

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
 * Bundled monospace font: JetBrains Mono Regular. OFL 1.1, see
 * `composeResources/font/jetbrains-mono-OFL.txt`. Pair with [kdSansFamily]
 * for code-shaped surfaces (YAML pane, log viewer, prerequisites modal).
 */
@Composable
fun kdMonoFamily(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, weight = FontWeight.Normal, style = FontStyle.Normal)
    return remember(regular) { FontFamily(regular) }
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
 * The system (unscaled) density, unaffected by UI zoom. `ui/App.kt` reads
 * this — not `LocalDensity.current` — when it converts Compose window
 * coordinates into AWT screen points for cluster-chip drag-to-merge hit
 * testing (see `ui/ScreenGeometry.kt`); every other `LocalDensity.current`
 * read in the app is supposed to see the zoomed value.
 */
val LocalSystemDensity = staticCompositionLocalOf<Density> { error("no system density") }

@Composable
fun KubeDashTheme(content: @Composable () -> Unit) {
    val systemIsDark = isSystemInDarkTheme()
    LaunchedEffect(systemIsDark, ThemeManager.mode) {
        ThemeManager.applySystemDarkTheme(systemIsDark)
    }
    val colorScheme = if (ThemeManager.isDarkTheme) DarkColorScheme else LightColorScheme
    val typography = appTypography(sans = kdSansFamily())
    // Theme the right-click ContextMenuArea popup. Compose's default uses
    // its own foundation colors and clashes with the Kd palette — give it
    // KdSurface / KdTextPrimary / KdHover so it visually matches the
    // dropdown we already use for the row's overflow `⋮` menu.
    val contextMenuRepresentation = remember(ThemeManager.isDarkTheme) {
        DefaultContextMenuRepresentation(
            backgroundColor = if (ThemeManager.isDarkTheme) KdSurfaceDark else KdSurfaceLight,
            textColor = if (ThemeManager.isDarkTheme) KdTextPrimaryDark else KdTextPrimaryLight,
            itemHoverColor = if (ThemeManager.isDarkTheme) KdHoverDark else KdHoverLight,
        )
    }
    // Compose Desktop's default scrollbar is ~12% alpha and 8dp wide — close
    // to invisible against KdSurface, so dense lists (events, pods, jobs)
    // gave no signal that more rows were below the fold. Bump the thumb to
    // ~35% / 65% alpha and 10dp so the thumb-to-track ratio communicates
    // "there's a lot of content" at a glance, even when not hovered.
    val scrollbarBase = if (ThemeManager.isDarkTheme) KdTextSecondaryDark else KdTextSecondaryLight
    val scrollbarStyle = remember(ThemeManager.isDarkTheme) {
        ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 10.dp,
            shape = RoundedCornerShape(5.dp),
            hoverDurationMillis = 200,
            unhoverColor = scrollbarBase.copy(alpha = 0.35f),
            hoverColor = scrollbarBase.copy(alpha = 0.65f),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
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
        ) {
            CopyFeedbackHost { ActionFeedbackHost { content() } }
        }
    }
}
