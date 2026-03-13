package com.kubekubedashdash

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kubekubedashdash.data.repository.PreferenceRepository

object ThemeManager {
    private var _isDarkTheme by mutableStateOf(PreferenceRepository.darkTheme)

    var isDarkTheme: Boolean
        get() = _isDarkTheme
        set(value) {
            _isDarkTheme = value
            PreferenceRepository.darkTheme = value
        }
}

private val KdBackgroundDark = Color(0xFF1E2124)
private val KdSidebarBgDark = Color(0xFF161819)
private val KdSurfaceDark = Color(0xFF252A31)
private val KdSurfaceVariantDark = Color(0xFF2C3038)
private val KdTextPrimaryDark = Color(0xFFC8D1DC)
private val KdTextSecondaryDark = Color(0xFF6B7280)
private val KdBorderDark = Color(0xFF2E3440)
private val KdHoverDark = Color(0xFF2A2F36)
private val KdSelectedDark = Color(0xFF1A3A5C)

private val KdBackgroundLight = Color(0xFFF8FAFC)
private val KdSidebarBgLight = Color(0xFFFFFFFF)
private val KdSurfaceLight = Color(0xFFFFFFFF)
private val KdSurfaceVariantLight = Color(0xFFF1F5F9)
private val KdTextPrimaryLight = Color(0xFF1E293B)
private val KdTextSecondaryLight = Color(0xFF64748B)
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
val KdSuccess = Color(0xFF48C744)
val KdWarning = Color(0xFFE8A030)
val KdError = Color(0xFFE54343)
val KdInfo = Color(0xFF3D90CE)
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
    error = Color(0xFFE54343),
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
    error = Color(0xFFE54343),
    outline = KdBorderLight,
    outlineVariant = KdBorderLight,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun KubeDashTheme(content: @Composable () -> Unit) {
    val colorScheme = if (ThemeManager.isDarkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
