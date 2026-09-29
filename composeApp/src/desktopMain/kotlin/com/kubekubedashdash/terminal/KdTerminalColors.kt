package com.kubekubedashdash.terminal

import androidx.compose.ui.graphics.Color
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.theme.KdPaletteVariant
import kotlin.math.roundToInt

/** Compose colour → jediterm/AWT colour (opaque; alpha is ignored). */
internal fun Color.toJediterm(): com.jediterm.core.Color = com.jediterm.core.Color((red * 255f).roundToInt(), (green * 255f).roundToInt(), (blue * 255f).roundToInt())

internal fun Color.toAwt(): java.awt.Color = java.awt.Color((red * 255f).roundToInt(), (green * 255f).roundToInt(), (blue * 255f).roundToInt())

/** A palette's 16 ANSI colours (§3.5); the same colour serves as foreground and background. */
internal class KdAnsiPalette(private val colors: List<Color>) : ColorPalette() {
    init {
        require(colors.size == 16) { "an ANSI palette holds exactly 16 colours" }
    }

    override fun getForegroundByColorIndex(colorIndex: Int): com.jediterm.core.Color = colors[colorIndex].toJediterm()

    override fun getBackgroundByColorIndex(colorIndex: Int): com.jediterm.core.Color = colors[colorIndex].toJediterm()
}

/**
 * Settings that read the active palette at call time (D13), so an open terminal re-themes on
 * repaint. [variant] is injectable for tests.
 */
internal class KdTerminalSettings(
    private val variant: () -> KdPaletteVariant = { ThemeManager.variant },
) : DefaultSettingsProvider() {
    /** jediterm's own platform palette (xterm; its Windows palette on Windows) for palettes with no ANSI list. */
    private val platformDefault: ColorPalette = super.getTerminalColorPalette()

    /**
     * Supplier-backed: cells copy the default style's TerminalColor objects by reference
     * (StyleState.reset), so a fixed colour would pin text already on screen to the old palette.
     * A supplier re-resolves the active palette at every paint.
     */
    private fun defaultFg(): TerminalColor = TerminalColor { variant().colors.terminalFg.toJediterm() }

    private fun defaultBg(): TerminalColor = TerminalColor { variant().colors.terminalBg.toJediterm() }

    fun defaultTextStyle(): TextStyle = TextStyle(defaultFg(), defaultBg())

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getDefaultStyle(): TextStyle = defaultTextStyle()

    override fun getDefaultForeground(): TerminalColor = defaultFg()

    override fun getDefaultBackground(): TerminalColor = defaultBg()

    override fun getTerminalColorPalette(): ColorPalette = variant().ansi?.let { KdAnsiPalette(it) } ?: platformDefault

    @Suppress("DEPRECATION")
    override fun useAntialiasing(): Boolean = true

    override fun scrollToBottomOnTyping(): Boolean = true
}
