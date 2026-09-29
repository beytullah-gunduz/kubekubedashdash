package com.kubekubedashdash.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.ThemePalette
import com.kubekubedashdash.ThemeStyle

// Default and Retro palettes (D9, D11). The *Colors / *Cvd vals come first, then Retro's
// hand-written M3 schemes that read them (Default derives its scheme), then the specs:
// Kotlin initialises a file's top-level vals in textual order, so a forward reference
// would read null.

internal val DefaultDarkColors = KdColors(
    background = Color(0xFF1E2124),
    sidebarBg = Color(0xFF161819),
    surface = Color(0xFF2A3038),
    surfaceVariant = Color(0xFF323845),
    textPrimary = Color(0xFFC8D1DC),
    textSecondary = Color(0xFF9BA5B1),
    textPlaceholder = Color(0xFF94A3B8),
    textBright = Color(0xFFFFFFFF),
    border = Color(0xFF3A4150),
    hover = Color(0xFF333944),
    selected = Color(0xFF1A3A5C),
    primary = Color(0xFF4D9FDE),
    onPrimary = Color(0xFF0B1B2B),
    accent = Color(0xFF4D9FDE),
    onError = Color(0xFF2B0000),
    success = Color(0xFF48C744),
    warning = Color(0xFFE8A030),
    error = Color(0xFFFF7971),
    info = Color(0xFF57A9E9),
    focus = Color(0xFF4D9FDE),
    onFocus = Color(0xFF0B1B2B),
    seriesMemory = Color(0xFF8B5CF6),
    graphEdge = Color(0xFF636E7C),
    syntaxKey = Color(0xFF4D9FDE),
    syntaxString = Color(0xFF48C744),
    syntaxNumber = Color(0xFF48C744),
    syntaxBool = Color(0xFFE8A030),
    syntaxComment = Color(0xFF9BA5B1),
    terminalFg = Color(0xFFD4D4D4),
    terminalBg = Color(0xFF1A1A1A),
)
internal val DefaultDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF7A66), info = Color(0xFFE29CC6), onError = Color(0xFF2B0000))

internal val DefaultLightColors = KdColors(
    background = Color(0xFFF8FAFC),
    sidebarBg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    textPrimary = Color(0xFF1E293B),
    textSecondary = Color(0xFF59687F),
    textPlaceholder = Color(0xFF607087),
    textBright = Color(0xFF1E293B),
    border = Color(0xFFE2E8F0),
    hover = Color(0xFFF1F5F9),
    selected = Color(0xFFDBEAFE),
    primary = Color(0xFF2077B4),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF2077B4),
    onError = Color(0xFFFFFFFF),
    success = Color(0xFF2E7D32),
    warning = Color(0xFFA05F00),
    error = Color(0xFFC62828),
    info = Color(0xFF1E73B8),
    focus = Color(0xFF2077B4),
    onFocus = Color(0xFFFFFFFF),
    seriesMemory = Color(0xFF8B5CF6),
    graphEdge = Color(0xFF505A68),
    syntaxKey = Color(0xFF2077B4),
    syntaxString = Color(0xFF2E7D32),
    syntaxNumber = Color(0xFF2E7D32),
    syntaxBool = Color(0xFFA05F00),
    syntaxComment = Color(0xFF59687F),
    terminalFg = Color(0xFF1A1A1A),
    terminalBg = Color(0xFFF5F5F5),
)
internal val DefaultLightCvd = KdStatusColors(success = Color(0xFF2D6FBE), warning = Color(0xFF876B01), error = Color(0xFF80011D), info = Color(0xFFA34E80), onError = Color(0xFFFFFFFF))

// Retro-dark (Retro plan §3.1): CRT arcade — deep blue-black tube, cyan phosphor
// primary, coin-gold heading accent.
internal val RetroDarkColors = KdColors(
    background = Color(0xFF12142B),
    sidebarBg = Color(0xFF0A0B1A),
    surface = Color(0xFF1E2240),
    surfaceVariant = Color(0xFF272C50),
    textPrimary = Color(0xFFE3E6F5),
    textSecondary = Color(0xFFADB3D6),
    textPlaceholder = Color(0xFFADB3D6),
    textBright = Color(0xFFFFFFFF),
    border = Color(0xFF3B4275),
    hover = Color(0xFF2E3460),
    selected = Color(0xFF1E4A56),
    primary = Color(0xFF7FD8EA),
    onPrimary = Color(0xFF00363F),
    accent = Color(0xFFFFD23E),
    onError = Color(0xFF3B0010),
    success = Color(0xFF3CE66B),
    warning = Color(0xFFFFA300),
    error = Color(0xFFFF5C7A),
    info = Color(0xFF5CBDFF),
    focus = Color(0xFFFFD23E),
    onFocus = Color(0xFF241A00),
    seriesMemory = Color(0xFFFFD23E),
    graphEdge = Color(0xFF5C6674),
    syntaxKey = Color(0xFF7FD8EA),
    syntaxString = Color(0xFF3CE66B),
    syntaxNumber = Color(0xFF3CE66B),
    syntaxBool = Color(0xFFFFA300),
    syntaxComment = Color(0xFFADB3D6),
    terminalFg = Color(0xFFE3E6F5),
    terminalBg = Color(0xFF12142B),
)
internal val RetroDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF7A66), info = Color(0xFFE29CC6), onError = Color(0xFF3B0010))

// Retro-light (Retro plan §3.1): "paper terminal" — cream field, dark-brown ink,
// deep-teal primary, plum accent. Rejected the reference's Game Boy pea-green LCD
// because it erases "green = healthy" (Retro plan D12).
internal val RetroLightColors = KdColors(
    background = Color(0xFFEFE7D2),
    sidebarBg = Color(0xFFE6DCC3),
    surface = Color(0xFFF8F2E3),
    surfaceVariant = Color(0xFFE8DEC6),
    textPrimary = Color(0xFF2B2418),
    textSecondary = Color(0xFF5A4F3C),
    textPlaceholder = Color(0xFF5A4F3C),
    textBright = Color(0xFF2B2418),
    border = Color(0xFFCBBE9E),
    hover = Color(0xFFE3D8BC),
    selected = Color(0xFFCFE3E0),
    primary = Color(0xFF00606B),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF6B2F5B),
    onError = Color(0xFFFFFFFF),
    success = Color(0xFF2E6B2F),
    warning = Color(0xFF8F5300),
    error = Color(0xFFA8231C),
    info = Color(0xFF1D5A9E),
    focus = Color(0xFF6B2F5B),
    onFocus = Color(0xFFFFFFFF),
    seriesMemory = Color(0xFF6B2F5B),
    graphEdge = Color(0xFF505A68),
    syntaxKey = Color(0xFF00606B),
    syntaxString = Color(0xFF2E6B2F),
    syntaxNumber = Color(0xFF2E6B2F),
    syntaxBool = Color(0xFF8F5300),
    syntaxComment = Color(0xFF5A4F3C),
    terminalFg = Color(0xFF2B2418),
    terminalBg = Color(0xFFEFE7D2),
)
internal val RetroLightCvd = KdStatusColors(success = Color(0xFF1E61AF), warning = Color(0xFF775E03), error = Color(0xFF80011D), info = Color(0xFF974375), onError = Color(0xFFFFFFFF))

// Retro-dark M3 roles (Retro plan §3.1, M3 role table). This sets the full
// surfaceContainer ramp because SettingsSection reads
// `surfaceContainer` (SettingsScreen.kt:231).
private val RetroDarkColorScheme = darkColorScheme(
    primary = RetroDarkColors.primary,
    onPrimary = RetroDarkColors.onPrimary,
    primaryContainer = Color(0xFF1E4A56),
    onPrimaryContainer = Color(0xFFBDEBF7),
    secondary = RetroDarkColors.accent,
    onSecondary = Color(0xFF241A00),
    secondaryContainer = Color(0xFF5C4A00),
    onSecondaryContainer = Color(0xFFFFE99C),
    tertiary = Color(0xFFFF77A8),
    onTertiary = Color(0xFF54082C),
    background = RetroDarkColors.background,
    surface = RetroDarkColors.surface,
    surfaceVariant = RetroDarkColors.surfaceVariant,
    onBackground = RetroDarkColors.textPrimary,
    onSurface = RetroDarkColors.textPrimary,
    onSurfaceVariant = RetroDarkColors.textSecondary,
    error = RetroDarkColors.error,
    onError = RetroDarkColors.onError,
    outline = RetroDarkColors.border,
    outlineVariant = RetroDarkColors.border,
    surfaceContainerLowest = Color(0xFF0A0B1A),
    surfaceContainerLow = Color(0xFF191C38),
    surfaceContainer = Color(0xFF1E2240),
    surfaceContainerHigh = Color(0xFF272C50),
    surfaceContainerHighest = Color(0xFF313763),
    surfaceDim = Color(0xFF12142B),
    surfaceBright = Color(0xFF313763),
)

// Retro-light M3 roles (Retro plan §3.1).
private val RetroLightColorScheme = lightColorScheme(
    primary = RetroLightColors.primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE3E0),
    onPrimaryContainer = Color(0xFF00363B),
    secondary = RetroLightColors.accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBD6E3),
    onSecondaryContainer = Color(0xFF2E0F27),
    tertiary = Color(0xFF6B4F1D),
    onTertiary = Color.White,
    background = RetroLightColors.background,
    surface = RetroLightColors.surface,
    surfaceVariant = RetroLightColors.surfaceVariant,
    onBackground = RetroLightColors.textPrimary,
    onSurface = RetroLightColors.textPrimary,
    onSurfaceVariant = RetroLightColors.textSecondary,
    error = RetroLightColors.error,
    onError = Color.White,
    outline = RetroLightColors.border,
    outlineVariant = RetroLightColors.border,
    surfaceContainerLowest = Color(0xFFFFFBF2),
    surfaceContainerLow = Color(0xFFF8F2E3),
    surfaceContainer = Color(0xFFF2EAD6),
    surfaceContainerHigh = Color(0xFFEBE2CB),
    surfaceContainerHighest = Color(0xFFE3D8BC),
    surfaceDim = Color(0xFFE6DCC3),
    surfaceBright = Color(0xFFFFFBF2),
)

internal val DefaultPalette = KdPaletteSpec(
    dark = KdPaletteVariant(DefaultDarkColors, DefaultDarkCvd, isDark = true),
    light = KdPaletteVariant(DefaultLightColors, DefaultLightCvd, isDark = false),
)

internal val RetroPalette = KdPaletteSpec(
    dark = KdPaletteVariant(RetroDarkColors, RetroDarkCvd, isDark = true, schemeOverride = RetroDarkColorScheme),
    light = KdPaletteVariant(RetroLightColors, RetroLightCvd, isDark = false, schemeOverride = RetroLightColorScheme),
)

internal fun kdPaletteSpec(style: ThemeStyle, palette: ThemePalette): KdPaletteSpec = when (palette) {
    ThemePalette.STYLE -> when (style) {
        ThemeStyle.DEFAULT -> DefaultPalette
        ThemeStyle.RETRO -> RetroPalette
    }
}
