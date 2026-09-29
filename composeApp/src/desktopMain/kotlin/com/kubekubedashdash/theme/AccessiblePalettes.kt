package com.kubekubedashdash.theme

import androidx.compose.material3.RippleThemeConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// High Contrast and Monochrome (D2, D15). The *Colors / *Cvd vals come first, then the specs that
// read them: Kotlin initialises a file's top-level vals in textual order, so a forward reference
// would read null.

internal val HighContrastDarkColors = KdColors(
    background = Color(0xFF000000),
    sidebarBg = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF1A1A1A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFD0D0D0),
    textPlaceholder = Color(0xFFBDBDBD),
    textBright = Color(0xFFFFFFFF),
    border = Color(0xFFBFBFBF),
    hover = Color(0xFF262626),
    selected = Color(0xFF00315C),
    primary = Color(0xFF75C8FF),
    onPrimary = Color(0xFF000000),
    accent = Color(0xFF75C8FF),
    onError = Color(0xFF000000),
    success = Color(0xFF6EF08E),
    warning = Color(0xFFFFD24D),
    error = Color(0xFFFF8A8A),
    info = Color(0xFF75C8FF),
    focus = Color(0xFFFFFFFF),
    onFocus = Color(0xFF000000),
    seriesMemory = Color(0xFFD0A8FF),
    graphEdge = Color(0xFFC5C5C5),
    syntaxKey = Color(0xFF75C8FF),
    syntaxString = Color(0xFF6EF08E),
    syntaxNumber = Color(0xFF6EF08E),
    syntaxBool = Color(0xFFFFD24D),
    syntaxComment = Color(0xFFD0D0D0),
    terminalFg = Color(0xFFFFFFFF),
    terminalBg = Color(0xFF000000),
)
internal val HighContrastDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF826E), info = Color(0xFFE29CC6), onError = Color(0xFF000000))

internal val HighContrastLightColors = KdColors(
    background = Color(0xFFFFFFFF),
    sidebarBg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F0),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF333333),
    textPlaceholder = Color(0xFF474747),
    textBright = Color(0xFF000000),
    border = Color(0xFF4D4D4D),
    hover = Color(0xFFE6E6E6),
    selected = Color(0xFFD6E8FF),
    primary = Color(0xFF00458F),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF00458F),
    onError = Color(0xFFFFFFFF),
    success = Color(0xFF015D26), // tuned from 00652A to pass the gate
    warning = Color(0xFF704400),
    error = Color(0xFFA2041C), // tuned from A8001C to pass the gate
    info = Color(0xFF00458F),
    focus = Color(0xFF000000),
    onFocus = Color(0xFFFFFFFF),
    seriesMemory = Color(0xFF5B2A9E),
    graphEdge = Color(0xFF444444),
    syntaxKey = Color(0xFF00458F),
    syntaxString = Color(0xFF015D26),
    syntaxNumber = Color(0xFF015D26),
    syntaxBool = Color(0xFF704400),
    syntaxComment = Color(0xFF333333),
    terminalFg = Color(0xFF000000),
    terminalBg = Color(0xFFFFFFFF),
)
internal val HighContrastLightCvd = KdStatusColors(success = Color(0xFF16307A), warning = Color(0xFF5F4F01), error = Color(0xFF5B021A), info = Color(0xFF833163), onError = Color(0xFFFFFFFF))

internal val MonochromeDarkColors = KdColors(
    background = Color(0xFF1F1F1F),
    sidebarBg = Color(0xFF171717),
    surface = Color(0xFF2B2B2B),
    surfaceVariant = Color(0xFF333333),
    textPrimary = Color(0xFFE1E1E1), // tuned from D6D6D6 to pass the gate
    textSecondary = Color(0xFFB7B7B7), // tuned from A6A6A6 to pass the gate
    textPlaceholder = Color(0xFFA6A6A6),
    textBright = Color(0xFFFFFFFF),
    border = Color(0xFF444444),
    hover = Color(0xFF363636),
    selected = Color(0xFF474747),
    primary = Color(0xFFE0E0E0),
    onPrimary = Color(0xFF1F1F1F),
    accent = Color(0xFFF2F2F2),
    onError = Color(0xFF2B0000),
    success = Color(0xFF48C744),
    warning = Color(0xFFE8A030),
    error = Color(0xFFFF7971),
    info = Color(0xFF57A9E9),
    focus = Color(0xFFE0E0E0),
    onFocus = Color(0xFF1F1F1F),
    seriesMemory = Color(0xFF8C8C8C),
    graphEdge = Color(0xFF6C6C6C),
    syntaxKey = Color(0xFFFFFFFF),
    syntaxString = Color(0xFFE1E1E1),
    syntaxNumber = Color(0xFFE1E1E1),
    syntaxBool = Color(0xFFE1E1E1),
    syntaxComment = Color(0xFFB7B7B7),
    terminalFg = Color(0xFFE1E1E1),
    terminalBg = Color(0xFF1F1F1F),
)
internal val MonochromeDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF7A66), info = Color(0xFFE29CC6), onError = Color(0xFF2B0000))

internal val MonochromeLightColors = KdColors(
    background = Color(0xFFF7F7F7),
    sidebarBg = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F0),
    textPrimary = Color(0xFF1F1F1F),
    textSecondary = Color(0xFF5E5E5E),
    textPlaceholder = Color(0xFF5E5E5E),
    textBright = Color(0xFF1F1F1F),
    border = Color(0xFFDEDEDE),
    hover = Color(0xFFF0F0F0),
    selected = Color(0xFFE3E3E3),
    primary = Color(0xFF262626),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF000000),
    onError = Color(0xFFFFFFFF),
    success = Color(0xFF2C7B30), // tuned from 2E7D32 to pass the gate
    warning = Color(0xFF9D5D01), // tuned from A05F00 to pass the gate
    error = Color(0xFFC62828),
    info = Color(0xFF1671AD), // tuned from 1973AF to pass the gate
    focus = Color(0xFF262626),
    onFocus = Color(0xFFFFFFFF),
    seriesMemory = Color(0xFF8C8C8C),
    graphEdge = Color(0xFF8C8C8C),
    syntaxKey = Color(0xFF1F1F1F),
    syntaxString = Color(0xFF1F1F1F),
    syntaxNumber = Color(0xFF1F1F1F),
    syntaxBool = Color(0xFF1F1F1F),
    syntaxComment = Color(0xFF5E5E5E),
    terminalFg = Color(0xFF1F1F1F),
    terminalBg = Color(0xFFF7F7F7),
)
internal val MonochromeLightCvd = KdStatusColors(success = Color(0xFF2A6DBB), warning = Color(0xFF846902), error = Color(0xFF80011D), info = Color(0xFFA34E80), onError = Color(0xFFFFFFFF))

internal val HighContrastPalette = KdPaletteSpec(
    dark = KdPaletteVariant(HighContrastDarkColors, HighContrastDarkCvd, isDark = true),
    light = KdPaletteVariant(HighContrastLightColors, HighContrastLightCvd, isDark = false),
    outlineWidth = 2.dp,
    // A 3 dp outer + 3 dp inner ring in black/white (focus/onFocus), visible on any fill (D2).
    // The inner stroke is drawn first and the outer on top, both inward from the outline; the
    // inner is 4 dp wide at inset 2 so the outer covers 1 dp of it, the stock ring's own overlap.
    focusRing = RippleThemeConfiguration(
        RippleThemeConfiguration.Focus.InsetRing(
            outerStrokeInset = 0.dp,
            outerStrokeWidth = 3.dp,
            innerStrokeInset = 2.dp,
            innerStrokeWidth = 4.dp,
        ),
    ),
)

internal val MonochromePalette = KdPaletteSpec(
    dark = KdPaletteVariant(MonochromeDarkColors, MonochromeDarkCvd, isDark = true),
    light = KdPaletteVariant(MonochromeLightColors, MonochromeLightCvd, isDark = false),
)
