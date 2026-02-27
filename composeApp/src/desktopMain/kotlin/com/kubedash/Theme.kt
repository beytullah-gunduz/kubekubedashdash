package com.kubedash

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val KdBackground = Color(0xFF1E2124)
val KdSidebarBg = Color(0xFF161819)
val KdSurface = Color(0xFF252A31)
val KdSurfaceVariant = Color(0xFF2C3038)
val KdPrimary = Color(0xFF3D90CE)
val KdOnPrimary = Color.White
val KdTextPrimary = Color(0xFFC8D1DC)
val KdTextSecondary = Color(0xFF6B7280)
val KdSuccess = Color(0xFF48C744)
val KdWarning = Color(0xFFE8A030)
val KdError = Color(0xFFE54343)
val KdInfo = Color(0xFF3D90CE)
val KdBorder = Color(0xFF2E3440)
val KdHover = Color(0xFF2A2F36)
val KdSelected = Color(0xFF1A3A5C)

private val DarkColorScheme = darkColorScheme(
    primary = KdPrimary,
    onPrimary = KdOnPrimary,
    secondary = KdPrimary,
    background = KdBackground,
    surface = KdSurface,
    surfaceVariant = KdSurfaceVariant,
    onBackground = KdTextPrimary,
    onSurface = KdTextPrimary,
    onSurfaceVariant = KdTextSecondary,
    error = KdError,
    outline = KdBorder,
    outlineVariant = KdBorder,
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
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content,
    )
}
