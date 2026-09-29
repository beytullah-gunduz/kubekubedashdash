package com.kubekubedashdash.theme

import androidx.compose.ui.graphics.Color

// Colours adapted from the palettes' public specs for WCAG contrast (plan D3, D12). Not official ports.
// Solarized © Ethan Schoonover (MIT) · Gruvbox © Pavel Pertsev (MIT/X11) · Catppuccin © Catppuccin (MIT)
// · Nord © Sven Greb (MIT) · Dracula/Alucard © Dracula Theme (MIT). Sources in README "Colour palette credits".
//
// The *Colors / *Cvd / *Ansi* vals come first, then the specs that read them: Kotlin initialises a
// file's top-level vals in textual order, so a forward reference would read null.

internal val SolarizedDarkColors = KdColors(
    background = Color(0xFF002B36), // Solarized base03
    sidebarBg = Color(0xFF002029), // derived (8-bit mix, see §3.4)
    surface = Color(0xFF073642), // Solarized base02
    surfaceVariant = Color(0xFF17414C), // derived (8-bit mix, see §3.4)
    textPrimary = Color(0xFFD8E7E7), // Solarized base1 #93A1A1, lightness-adjusted for contrast (D3)
    textSecondary = Color(0xFFABBCBF), // Solarized base0 #839496, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFF99ABAD), // Solarized base0 #839496, lightness-adjusted for contrast (D3)
    textBright = Color(0xFFEEE8D5), // Solarized base2
    border = Color(0xFF2B4F59), // derived (8-bit mix, see §3.4)
    hover = Color(0xFF113D48), // derived (8-bit mix, see §3.4)
    selected = Color(0xFF104E6A), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF41A1EA), // Solarized blue #268BD2, lightness-adjusted for contrast (D3)
    onPrimary = Color(0xFF002B36), // Solarized base03
    accent = Color(0xFF8C93E9), // Solarized violet #6C71C4, lightness-adjusted for contrast (D3)
    onError = Color(0xFF002B36), // Solarized base03
    success = Color(0xFF9CB130), // Solarized green #859900, lightness-adjusted for contrast (D3)
    warning = Color(0xFFCDA130), // Solarized yellow #B58900, lightness-adjusted for contrast (D3)
    error = Color(0xFFFE8377), // Solarized red #DC322F, lightness-adjusted for contrast (D3)
    info = Color(0xFF4FAEF7), // Solarized blue #268BD2, lightness-adjusted for contrast (D3)
    focus = Color(0xFF41A1EA),
    onFocus = Color(0xFF002B36),
    seriesMemory = Color(0xFF8C93E9),
    graphEdge = Color(0xFF58757D),
    syntaxKey = Color(0xFF41A1EA),
    syntaxString = Color(0xFF38ABA2),
    syntaxNumber = Color(0xFFFE60A6),
    syntaxBool = Color(0xFFF77344),
    syntaxComment = Color(0xFFABBCBF),
    terminalFg = Color(0xFFD8E7E7),
    terminalBg = Color(0xFF002B36),
)
internal val SolarizedDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF8370), info = Color(0xFFE29CC6), onError = Color(0xFF002B36))

internal val SolarizedLightColors = KdColors(
    background = Color(0xFFF8F1DE), // derived (8-bit mix, see §3.4)
    sidebarBg = Color(0xFFFDF6E3), // Solarized base3
    surface = Color(0xFFFDF6E3), // Solarized base3
    surfaceVariant = Color(0xFFEEE8D5), // Solarized base2
    textPrimary = Color(0xFF344950), // Solarized base01 #586E75, lightness-adjusted for contrast (D3)
    textSecondary = Color(0xFF50656D), // Solarized base00 #657B83, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFF556A72), // Solarized base00 #657B83, lightness-adjusted for contrast (D3)
    textBright = Color(0xFF073642), // Solarized base02
    border = Color(0xFFCECFC3), // derived (8-bit mix, see §3.4)
    hover = Color(0xFFF4EEDB), // derived (8-bit mix, see §3.4)
    selected = Color(0xFFD6E3E0), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF0071B3), // Solarized blue #268BD2, lightness-adjusted for contrast (D3)
    onPrimary = Color(0xFFFDF6E3), // Solarized base3
    accent = Color(0xFF6064B6), // Solarized violet #6C71C4, lightness-adjusted for contrast (D3)
    onError = Color(0xFFFDF6E3), // Solarized base3
    success = Color(0xFF5F6E00), // Solarized green #859900, lightness-adjusted for contrast (D3)
    warning = Color(0xFF836205), // Solarized yellow #B58900, lightness-adjusted for contrast (D3)
    error = Color(0xFFCB1B1F), // Solarized red #DC322F, lightness-adjusted for contrast (D3)
    info = Color(0xFF036BAA), // Solarized blue #268BD2, lightness-adjusted for contrast (D3)
    focus = Color(0xFF0071B3),
    onFocus = Color(0xFFFDF6E3),
    seriesMemory = Color(0xFF6064B6),
    graphEdge = Color(0xFF838B86),
    syntaxKey = Color(0xFF0071B3),
    syntaxString = Color(0xFF077A73),
    syntaxNumber = Color(0xFFC82979),
    syntaxBool = Color(0xFFC14204),
    syntaxComment = Color(0xFF50656D),
    terminalFg = Color(0xFF344950),
    terminalBg = Color(0xFFF8F1DE),
)
internal val SolarizedLightCvd = KdStatusColors(success = Color(0xFF2567B5), warning = Color(0xFF7E6403), error = Color(0xFF80011D), info = Color(0xFF9E4A7C), onError = Color(0xFFFDF6E3))

internal val SolarizedAnsiDark: List<Color> = listOf(
    Color(0xFF073642), Color(0xFFDC322F), Color(0xFF859900), Color(0xFFB58900),
    Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198), Color(0xFFEEE8D5),
    Color(0xFF002B36), Color(0xFFCB4B16), Color(0xFF586E75), Color(0xFF657B83),
    Color(0xFF839496), Color(0xFF6C71C4), Color(0xFF93A1A1), Color(0xFFFDF6E3),
)
internal val SolarizedAnsiLight: List<Color> = listOf(
    Color(0xFFEEE8D5), Color(0xFFDC322F), Color(0xFF859900), Color(0xFFB58900),
    Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198), Color(0xFF073642),
    Color(0xFFFDF6E3), Color(0xFFCB4B16), Color(0xFF93A1A1), Color(0xFF839496),
    Color(0xFF657B83), Color(0xFF6C71C4), Color(0xFF586E75), Color(0xFF002B36),
)

internal val GruvboxDarkColors = KdColors(
    background = Color(0xFF282828), // Gruvbox bg0
    sidebarBg = Color(0xFF1D2021), // Gruvbox bg0_h
    surface = Color(0xFF32302F), // Gruvbox bg0_s
    surfaceVariant = Color(0xFF3C3836), // Gruvbox bg1
    textPrimary = Color(0xFFF8E8BE), // Gruvbox l1 #EBDBB2, lightness-adjusted for contrast (D3)
    textSecondary = Color(0xFFCABBA5), // Gruvbox l4 #A89984, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFFB0A191), // Gruvbox gray #928374, lightness-adjusted for contrast (D3)
    textBright = Color(0xFFFBF1C7), // Gruvbox l0
    border = Color(0xFF504945), // Gruvbox bg2
    hover = Color(0xFF373433), // derived (8-bit mix, see §3.4)
    selected = Color(0xFF464D49), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF83A598), // Gruvbox bright_blue
    onPrimary = Color(0xFF282828), // Gruvbox bg0
    accent = Color(0xFFD3869B), // Gruvbox bright_purple
    onError = Color(0xFF282828), // Gruvbox bg0
    success = Color(0xFFB8BB26), // Gruvbox bright_green
    warning = Color(0xFFFABD2F), // Gruvbox bright_yellow
    error = Color(0xFFFF7C68), // Gruvbox bright_red #FB4934, lightness-adjusted for contrast (D3)
    info = Color(0xFF88AB9E), // Gruvbox bright_blue #83A598, lightness-adjusted for contrast (D3)
    focus = Color(0xFF83A598),
    onFocus = Color(0xFF282828),
    seriesMemory = Color(0xFFD3869B),
    graphEdge = Color(0xFF7B7167),
    syntaxKey = Color(0xFF83A598),
    syntaxString = Color(0xFFB8BB26),
    syntaxNumber = Color(0xFFD3869B),
    syntaxBool = Color(0xFFFE8019),
    syntaxComment = Color(0xFFCABBA5),
    terminalFg = Color(0xFFF8E8BE),
    terminalBg = Color(0xFF282828),
)
internal val GruvboxDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF7A66), info = Color(0xFFE29CC6), onError = Color(0xFF282828))

internal val GruvboxLightColors = KdColors(
    background = Color(0xFFFBF1C7), // Gruvbox l0
    sidebarBg = Color(0xFFF9F5D7), // Gruvbox l0_h
    surface = Color(0xFFF9F5D7), // Gruvbox l0_h
    surfaceVariant = Color(0xFFF2E5BC), // Gruvbox l0_s
    textPrimary = Color(0xFF3C3836), // Gruvbox bg1
    textSecondary = Color(0xFF6A5D53), // Gruvbox bg4 #7C6F64, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFF716355), // Gruvbox gray #928374, lightness-adjusted for contrast (D3)
    textBright = Color(0xFF282828), // Gruvbox bg0
    border = Color(0xFFD5C4A1), // Gruvbox l2
    hover = Color(0xFFF5EBC7), // derived (8-bit mix, see §3.4)
    selected = Color(0xFFD5E0C9), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF076678), // Gruvbox faded_blue
    onPrimary = Color(0xFFF9F5D7), // Gruvbox l0_h
    accent = Color(0xFF8F3F71), // Gruvbox faded_purple
    onError = Color(0xFFF9F5D7), // Gruvbox l0_h
    success = Color(0xFF6C6804), // Gruvbox faded_green #79740E, lightness-adjusted for contrast (D3)
    warning = Color(0xFF8E5A03), // Gruvbox faded_yellow #B57614, lightness-adjusted for contrast (D3)
    error = Color(0xFF9D0006), // Gruvbox faded_red
    info = Color(0xFF076678), // Gruvbox faded_blue
    focus = Color(0xFF076678),
    onFocus = Color(0xFFF9F5D7),
    seriesMemory = Color(0xFF8F3F71),
    graphEdge = Color(0xFF96866D),
    syntaxKey = Color(0xFF076678),
    syntaxString = Color(0xFF746F03),
    syntaxNumber = Color(0xFF8F3F71),
    syntaxBool = Color(0xFFAF3A03),
    syntaxComment = Color(0xFF6A5D53),
    terminalFg = Color(0xFF3C3836),
    terminalBg = Color(0xFFFBF1C7),
)
internal val GruvboxLightCvd = KdStatusColors(success = Color(0xFF2366B4), warning = Color(0xFF7C6205), error = Color(0xFF80011D), info = Color(0xFF9C487A), onError = Color(0xFFF9F5D7))

internal val GruvboxAnsiDark: List<Color> = listOf(
    Color(0xFF282828), Color(0xFFCC241D), Color(0xFF98971A), Color(0xFFD79921),
    Color(0xFF458588), Color(0xFFB16286), Color(0xFF689D6A), Color(0xFFA89984),
    Color(0xFF928374), Color(0xFFFB4934), Color(0xFFB8BB26), Color(0xFFFABD2F),
    Color(0xFF83A598), Color(0xFFD3869B), Color(0xFF8EC07C), Color(0xFFEBDBB2),
)
internal val GruvboxAnsiLight: List<Color> = listOf(
    Color(0xFFFBF1C7), Color(0xFFCC241D), Color(0xFF98971A), Color(0xFFD79921),
    Color(0xFF458588), Color(0xFFB16286), Color(0xFF689D6A), Color(0xFF7C6F64),
    Color(0xFF928374), Color(0xFF9D0006), Color(0xFF79740E), Color(0xFFB57614),
    Color(0xFF076678), Color(0xFF8F3F71), Color(0xFF427B58), Color(0xFF3C3836),
)

internal val CatppuccinDarkColors = KdColors(
    background = Color(0xFF1E1E2E), // Mocha base
    sidebarBg = Color(0xFF181825), // Mocha mantle
    surface = Color(0xFF313244), // Mocha s0
    surfaceVariant = Color(0xFF45475A), // Mocha s1
    textPrimary = Color(0xFFEBF0FE), // Mocha text #CDD6F4, lightness-adjusted for contrast (D3)
    textSecondary = Color(0xFFBBC2DD), // Mocha sub0 #A6ADC8, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFFB1B8D1), // Mocha ov2 #9399B2, lightness-adjusted for contrast (D3)
    textBright = Color(0xFFCDD6F4), // Mocha text
    border = Color(0xFF585B70), // Mocha s2
    hover = Color(0xFF3B3D4F), // derived (8-bit mix, see §3.4)
    selected = Color(0xFF444F6C), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF89B4FA), // Mocha blue
    onPrimary = Color(0xFF1E1E2E), // Mocha base
    accent = Color(0xFFCBA6F7), // Mocha mauve
    onError = Color(0xFF1E1E2E), // Mocha base
    success = Color(0xFFA6E3A1), // Mocha green
    warning = Color(0xFFF9E2AF), // Mocha yellow
    error = Color(0xFFFF9CB6), // Mocha red #F38BA8, lightness-adjusted for contrast (D3)
    info = Color(0xFF94E2D5), // Mocha teal
    focus = Color(0xFF89B4FA),
    onFocus = Color(0xFF1E1E2E),
    seriesMemory = Color(0xFFCBA6F7),
    graphEdge = Color(0xFF7B7F96),
    syntaxKey = Color(0xFF89B4FA),
    syntaxString = Color(0xFFA6E3A1),
    syntaxNumber = Color(0xFFFAB387),
    syntaxBool = Color(0xFFFAB387),
    syntaxComment = Color(0xFFBBC2DD),
    terminalFg = Color(0xFFEBF0FE),
    terminalBg = Color(0xFF1E1E2E),
)
internal val CatppuccinDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF9F8E), info = Color(0xFFEAA4CE), onError = Color(0xFF1E1E2E))

internal val CatppuccinLightColors = KdColors(
    background = Color(0xFFE6E9EF), // Latte mantle
    sidebarBg = Color(0xFFEFF1F5), // Latte base
    surface = Color(0xFFEFF1F5), // Latte base
    surfaceVariant = Color(0xFFDCE0E8), // Latte crust
    textPrimary = Color(0xFF3F425B), // Latte text #4C4F69, lightness-adjusted for contrast (D3)
    textSecondary = Color(0xFF5C5E74), // Latte sub0 #6C6F85, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFF5E6176), // Latte sub0 #6C6F85, lightness-adjusted for contrast (D3)
    textBright = Color(0xFF4C4F69), // Latte text
    border = Color(0xFFBCC0CC), // Latte s1
    hover = Color(0xFFE4E7ED), // derived (8-bit mix, see §3.4)
    selected = Color(0xFFD0DCF5), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF135CEA), // Latte blue #1E66F5, lightness-adjusted for contrast (D3)
    onPrimary = Color(0xFFEFF1F5), // Latte base
    accent = Color(0xFF8636EC), // Latte mauve #8839EF, lightness-adjusted for contrast (D3)
    onError = Color(0xFFEFF1F5), // Latte base
    success = Color(0xFF1D7102), // Latte green #40A02B, lightness-adjusted for contrast (D3)
    warning = Color(0xFF8C5602), // Latte yellow #DF8E1D, lightness-adjusted for contrast (D3)
    error = Color(0xFFC40433), // Latte red #D20F39, lightness-adjusted for contrast (D3)
    info = Color(0xFF046D73), // Latte teal #179299, lightness-adjusted for contrast (D3)
    focus = Color(0xFF135CEA),
    onFocus = Color(0xFFEFF1F5),
    seriesMemory = Color(0xFF8636EC),
    graphEdge = Color(0xFF7F8391),
    syntaxKey = Color(0xFF135CEA),
    syntaxString = Color(0xFF1F7803),
    syntaxNumber = Color(0xFFB34403),
    syntaxBool = Color(0xFFB34403),
    syntaxComment = Color(0xFF5C5E74),
    terminalFg = Color(0xFF3F425B),
    terminalBg = Color(0xFFE6E9EF),
)
internal val CatppuccinLightCvd = KdStatusColors(success = Color(0xFF1F62B0), warning = Color(0xFF785F05), error = Color(0xFF80011D), info = Color(0xFF984476), onError = Color(0xFFEFF1F5))

internal val CatppuccinAnsiDark: List<Color> = listOf(
    Color(0xFF45475A), Color(0xFFF38BA8), Color(0xFFA6E3A1), Color(0xFFF9E2AF),
    Color(0xFF89B4FA), Color(0xFFF5C2E7), Color(0xFF94E2D5), Color(0xFFA6ADC8),
    Color(0xFF585B70), Color(0xFFF37799), Color(0xFF89D88B), Color(0xFFEBD391),
    Color(0xFF74A8FC), Color(0xFFF2AEDE), Color(0xFF6BD7CA), Color(0xFFBAC2DE),
)
internal val CatppuccinAnsiLight: List<Color> = listOf(
    Color(0xFF5C5F77), Color(0xFFD20F39), Color(0xFF40A02B), Color(0xFFDF8E1D),
    Color(0xFF1E66F5), Color(0xFFEA76CB), Color(0xFF179299), Color(0xFFACB0BE),
    Color(0xFF6C6F85), Color(0xFFDE293E), Color(0xFF49AF3D), Color(0xFFEEA02D),
    Color(0xFF456EFF), Color(0xFFFE85D8), Color(0xFF2D9FA8), Color(0xFFBCC0CC),
)

internal val NordDarkColors = KdColors(
    background = Color(0xFF2E3440), // Nord nord0
    sidebarBg = Color(0xFF262B34), // derived (8-bit mix, see §3.4)
    surface = Color(0xFF3B4252), // Nord nord1
    surfaceVariant = Color(0xFF434C5E), // Nord nord2
    textPrimary = Color(0xFFF6F9FE), // Nord nord6 #ECEFF4, lightness-adjusted for contrast (D3)
    textSecondary = Color(0xFFC4CBD9), // derived #AEB5C3, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFFB7BECC), // derived #AEB5C3, lightness-adjusted for contrast (D3)
    textBright = Color(0xFFECEFF4), // Nord nord6
    border = Color(0xFF4C566A), // Nord nord3
    hover = Color(0xFF3F4758), // derived (8-bit mix, see §3.4)
    selected = Color(0xFF46556D), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF88C0D0), // Nord nord8
    onPrimary = Color(0xFF2E3440), // Nord nord0
    accent = Color(0xFFCAA4C3), // Nord nord15 #B48EAD, lightness-adjusted for contrast (D3)
    onError = Color(0xFF2E3440), // Nord nord0
    success = Color(0xFFAAC693), // Nord nord14 #A3BE8C, lightness-adjusted for contrast (D3)
    warning = Color(0xFFEBCB8B), // Nord nord13
    error = Color(0xFFFFA5AB), // Nord nord11 #BF616A, lightness-adjusted for contrast (D3)
    info = Color(0xFFA0C1E2), // Nord nord9 #81A1C1, lightness-adjusted for contrast (D3)
    focus = Color(0xFF88C0D0),
    onFocus = Color(0xFF2E3440),
    seriesMemory = Color(0xFFCAA4C3),
    graphEdge = Color(0xFF767F91),
    syntaxKey = Color(0xFF8FBCBB),
    syntaxString = Color(0xFFA3BE8C),
    syntaxNumber = Color(0xFFCAA4C3),
    syntaxBool = Color(0xFF92B3D3),
    syntaxComment = Color(0xFFC4CBD9),
    terminalFg = Color(0xFFF6F9FE),
    terminalBg = Color(0xFF2E3440),
)
internal val NordDarkCvd = KdStatusColors(success = Color(0xFF89C3FE), warning = Color(0xFFFAF170), error = Color(0xFFFFA899), info = Color(0xFFEFA8D2), onError = Color(0xFF2E3440))

internal val NordLightColors = KdColors(
    background = Color(0xFFE9ECF2), // derived (8-bit mix, see §3.4)
    sidebarBg = Color(0xFFECEFF4), // Nord nord6
    surface = Color(0xFFECEFF4), // Nord nord6
    surfaceVariant = Color(0xFFE5E9F0), // Nord nord5
    textPrimary = Color(0xFF2E3440), // Nord nord0
    textSecondary = Color(0xFF4C566A), // Nord nord3
    textPlaceholder = Color(0xFF4C566A), // Nord nord3
    textBright = Color(0xFF2E3440), // Nord nord0
    border = Color(0xFFD8DEE9), // Nord nord4
    hover = Color(0xFFE5E9F0), // Nord nord5
    selected = Color(0xFFD0D9E6), // derived (8-bit mix, see §3.4)
    primary = Color(0xFF496B95), // Nord nord10 #5E81AC, lightness-adjusted for contrast (D3)
    onPrimary = Color(0xFFECEFF4), // Nord nord6
    accent = Color(0xFF825E7C), // Nord nord15 #B48EAD, lightness-adjusted for contrast (D3)
    onError = Color(0xFFECEFF4), // Nord nord6
    success = Color(0xFF566E40), // Nord nord14 #A3BE8C, lightness-adjusted for contrast (D3)
    warning = Color(0xFF806322), // Nord nord13 #EBCB8B, lightness-adjusted for contrast (D3)
    error = Color(0xFFA64A54), // Nord nord11 #BF616A, lightness-adjusted for contrast (D3)
    info = Color(0xFF476993), // Nord nord10 #5E81AC, lightness-adjusted for contrast (D3)
    focus = Color(0xFF496B95),
    onFocus = Color(0xFFECEFF4),
    seriesMemory = Color(0xFF825E7C),
    graphEdge = Color(0xFF7E8593),
    syntaxKey = Color(0xFF496B95),
    syntaxString = Color(0xFF587042),
    syntaxNumber = Color(0xFF825E7C),
    syntaxBool = Color(0xFF4E6C8A),
    syntaxComment = Color(0xFF4C566A),
    terminalFg = Color(0xFF2E3440),
    terminalBg = Color(0xFFE9ECF2),
)
internal val NordLightCvd = KdStatusColors(success = Color(0xFF2668B7), warning = Color(0xFF7F6404), error = Color(0xFF80011D), info = Color(0xFF9F4A7C), onError = Color(0xFFECEFF4))

internal val NordAnsi: List<Color> = listOf(
    Color(0xFF3B4252), Color(0xFFBF616A), Color(0xFFA3BE8C), Color(0xFFEBCB8B),
    Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFF88C0D0), Color(0xFFE5E9F0),
    Color(0xFF4C566A), Color(0xFFBF616A), Color(0xFFA3BE8C), Color(0xFFEBCB8B),
    Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFF8FBCBB), Color(0xFFECEFF4),
)

internal val DraculaDarkColors = KdColors(
    background = Color(0xFF282A36), // Dracula bg
    sidebarBg = Color(0xFF21222C), // Dracula bgdark
    surface = Color(0xFF343746), // Dracula bglight
    surfaceVariant = Color(0xFF424450), // Dracula bglighter
    textPrimary = Color(0xFFF8F8F2), // Dracula fg
    textSecondary = Color(0xFFADBFF6), // Dracula comment #6272A4, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFF9FB2E8), // Dracula comment #6272A4, lightness-adjusted for contrast (D3)
    textBright = Color(0xFFFFFFFF), // derived (8-bit mix, see §3.4)
    border = Color(0xFF505976), // derived (8-bit mix, see §3.4)
    hover = Color(0xFF3B3E4B), // derived (8-bit mix, see §3.4)
    selected = Color(0xFF4F496A), // derived (8-bit mix, see §3.4)
    primary = Color(0xFFBD93F9), // Dracula purple
    onPrimary = Color(0xFF282A36), // Dracula bg
    accent = Color(0xFFFF79C6), // Dracula pink
    onError = Color(0xFF282A36), // Dracula bg
    success = Color(0xFF50FA7B), // Dracula green
    warning = Color(0xFFFFB86C), // Dracula orange
    error = Color(0xFFFF9790), // Dracula red #FF5555, lightness-adjusted for contrast (D3)
    info = Color(0xFF8BE9FD), // Dracula cyan
    focus = Color(0xFFBD93F9),
    onFocus = Color(0xFF282A36),
    seriesMemory = Color(0xFFFF79C6),
    graphEdge = Color(0xFF717DA3),
    syntaxKey = Color(0xFF8BE9FD),
    syntaxString = Color(0xFFF1FA8C),
    syntaxNumber = Color(0xFFBD93F9),
    syntaxBool = Color(0xFFBD93F9),
    syntaxComment = Color(0xFFADBFF6),
    terminalFg = Color(0xFFF8F8F2),
    terminalBg = Color(0xFF282A36),
)
internal val DraculaDarkCvd = KdStatusColors(success = Color(0xFF7CBDFF), warning = Color(0xFFFAF170), error = Color(0xFFFF9786), info = Color(0xFFE29CC6), onError = Color(0xFF282A36))

internal val DraculaLightColors = KdColors(
    background = Color(0xFFECE9DF), // Alucard bglighter
    sidebarBg = Color(0xFFFFFBEB), // Alucard bg
    surface = Color(0xFFFFFBEB), // Alucard bg
    surfaceVariant = Color(0xFFEFEDDC), // Alucard floating
    textPrimary = Color(0xFF1F1F1F), // Alucard fg
    textSecondary = Color(0xFF5E583E), // Alucard comment #6C664B, lightness-adjusted for contrast (D3)
    textPlaceholder = Color(0xFF6C664B), // Alucard comment
    textBright = Color(0xFF1F1F1F), // Alucard fg
    border = Color(0xFFCECDC0), // Alucard bgdark
    hover = Color(0xFFF4F1E1), // derived (8-bit mix, see §3.4)
    selected = Color(0xFFCFCFDE), // Alucard selection
    primary = Color(0xFF644AC9), // Alucard purple
    onPrimary = Color(0xFFFFFBEB), // Alucard bg
    accent = Color(0xFFA3144D), // Alucard pink
    onError = Color(0xFFFFFBEB), // Alucard bg
    success = Color(0xFF14710A), // Alucard green
    warning = Color(0xFFA34D14), // Alucard orange
    error = Color(0xFFC23021), // Alucard red #CB3A2A, lightness-adjusted for contrast (D3)
    info = Color(0xFF036A96), // Alucard cyan
    focus = Color(0xFF644AC9),
    onFocus = Color(0xFFFFFBEB),
    seriesMemory = Color(0xFFA3144D),
    graphEdge = Color(0xFF868373),
    syntaxKey = Color(0xFF036A96),
    syntaxString = Color(0xFF7B6503),
    syntaxNumber = Color(0xFF644AC9),
    syntaxBool = Color(0xFF644AC9),
    syntaxComment = Color(0xFF5E583E),
    terminalFg = Color(0xFF1F1F1F),
    terminalBg = Color(0xFFECE9DF),
)
internal val DraculaLightCvd = KdStatusColors(success = Color(0xFF2668B7), warning = Color(0xFF7F6404), error = Color(0xFF80011D), info = Color(0xFF9F4A7C), onError = Color(0xFFFFFBEB))

internal val DraculaAnsiDark: List<Color> = listOf(
    Color(0xFF21222C), Color(0xFFFF5555), Color(0xFF50FA7B), Color(0xFFF1FA8C),
    Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD), Color(0xFFF8F8F2),
    Color(0xFF6272A4), Color(0xFFFF6E6E), Color(0xFF69FF94), Color(0xFFFFFFA5),
    Color(0xFFD6ACFF), Color(0xFFFF92DF), Color(0xFFA4FFFF), Color(0xFFFFFFFF),
)
internal val DraculaAnsiLight: List<Color> = listOf(
    Color(0xFFFFFBEB), Color(0xFFCB3A2A), Color(0xFF14710A), Color(0xFF846E15),
    Color(0xFF644AC9), Color(0xFFA3144D), Color(0xFF036A96), Color(0xFF1F1F1F),
    Color(0xFF6C664B), Color(0xFFD74C3D), Color(0xFF198D0C), Color(0xFF9E841A),
    Color(0xFF7862D0), Color(0xFFBF185A), Color(0xFF047FB4), Color(0xFF2C2B31),
)

internal val SolarizedPalette = KdPaletteSpec(
    dark = KdPaletteVariant(SolarizedDarkColors, SolarizedDarkCvd, isDark = true, ansi = SolarizedAnsiDark),
    light = KdPaletteVariant(SolarizedLightColors, SolarizedLightCvd, isDark = false, ansi = SolarizedAnsiLight),
)
internal val GruvboxPalette = KdPaletteSpec(
    dark = KdPaletteVariant(GruvboxDarkColors, GruvboxDarkCvd, isDark = true, ansi = GruvboxAnsiDark),
    light = KdPaletteVariant(GruvboxLightColors, GruvboxLightCvd, isDark = false, ansi = GruvboxAnsiLight),
)
internal val CatppuccinPalette = KdPaletteSpec(
    dark = KdPaletteVariant(CatppuccinDarkColors, CatppuccinDarkCvd, isDark = true, ansi = CatppuccinAnsiDark),
    light = KdPaletteVariant(CatppuccinLightColors, CatppuccinLightCvd, isDark = false, ansi = CatppuccinAnsiLight),
)
internal val NordPalette = KdPaletteSpec(
    dark = KdPaletteVariant(NordDarkColors, NordDarkCvd, isDark = true, ansi = NordAnsi),
    light = KdPaletteVariant(NordLightColors, NordLightCvd, isDark = false, ansi = NordAnsi),
)
internal val DraculaPalette = KdPaletteSpec(
    dark = KdPaletteVariant(DraculaDarkColors, DraculaDarkCvd, isDark = true, ansi = DraculaAnsiDark),
    light = KdPaletteVariant(DraculaLightColors, DraculaLightCvd, isDark = false, ansi = DraculaAnsiLight),
)
