package com.kubekubedashdash.terminal

import androidx.compose.ui.graphics.Color
import com.jediterm.terminal.TerminalColor
import com.kubekubedashdash.theme.DefaultDarkColors
import com.kubekubedashdash.theme.DefaultDarkCvd
import com.kubekubedashdash.theme.DefaultPalette
import com.kubekubedashdash.theme.KdPaletteVariant
import com.kubekubedashdash.theme.RetroDarkColors
import com.kubekubedashdash.theme.RetroPalette
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the terminal's palette wiring (theme-expansion plan WS3, D13): the compose → jediterm colour
 * conversion, the ANSI palette's index mapping, and that [KdTerminalSettings] reads the injected
 * palette variant at call time rather than when it was built. It builds no widget and touches no
 * [com.kubekubedashdash.ThemeManager] state: the variant is injected, so nothing here can leak into
 * another test class.
 */
class KdTerminalColorsTest {

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private fun ansiList(): List<Color> = List(16) { Color(it * 16, 255 - it * 8, it) }

    @Test
    fun `a compose colour converts to opaque jediterm and awt colours`() {
        val c = Color(0xD4, 0x1A, 0xF5, alpha = 0x40)

        val jt = c.toJediterm()
        assertEquals(listOf(0xD4, 0x1A, 0xF5), listOf(jt.red, jt.green, jt.blue))
        assertEquals(0xFF, jt.alpha)

        val awt = c.toAwt()
        assertEquals(listOf(0xD4, 0x1A, 0xF5), listOf(awt.red, awt.green, awt.blue))
        assertEquals(0xFF, awt.alpha)
    }

    @Test
    fun `the ansi palette maps every index to its list entry for foreground and background`() {
        val colors = ansiList()
        val palette = KdAnsiPalette(colors)

        for (i in 0 until 16) {
            val expected = colors[i].toJediterm()
            assertEquals(expected, palette.getForeground(TerminalColor.index(i)), "foreground index $i")
            assertEquals(expected, palette.getBackground(TerminalColor.index(i)), "background index $i")
        }
    }

    @Test
    fun `the ansi palette rejects a list that is not 16 long`() {
        for (size in listOf(0, 8, 15, 17)) {
            val failure = runCatching { KdAnsiPalette(List(size) { Color.Black }) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException, "size $size must be rejected, got $failure")
        }
    }

    @Test
    fun `the default foreground and background come from the injected variant`() {
        val settings = KdTerminalSettings { RetroPalette.dark }

        assertEquals(RetroDarkColors.terminalFg.toJediterm().rgb, settings.defaultForeground.toColor().rgb)
        assertEquals(RetroDarkColors.terminalBg.toJediterm().rgb, settings.defaultBackground.toColor().rgb)

        val style = settings.defaultTextStyle()
        assertEquals(RetroDarkColors.terminalFg.toJediterm().rgb, style.foreground!!.toColor().rgb)
        assertEquals(RetroDarkColors.terminalBg.toJediterm().rgb, style.background!!.toColor().rgb)
    }

    @Test
    fun `a palette with no ansi list keeps jediterm's own palette`() {
        val settings = KdTerminalSettings { DefaultPalette.dark }

        assertFalse(settings.terminalColorPalette is KdAnsiPalette)
        // Stateless and cached: every paint gets the same platform palette, not a fresh copy.
        assertSame(settings.terminalColorPalette, settings.terminalColorPalette)
    }

    @Test
    fun `a palette with an ansi list gets a KdAnsiPalette over it`() {
        val variant = KdPaletteVariant(DefaultDarkColors, DefaultDarkCvd, true, ansi = List(16) { Color(it * 16, 0, 0) })
        val palette = KdTerminalSettings { variant }.terminalColorPalette

        assertTrue(palette is KdAnsiPalette)
        val five = palette.getForeground(TerminalColor.index(5))
        assertEquals(listOf(80, 0, 0), listOf(five.red, five.green, five.blue))
    }

    @Test
    fun `the palette follows the variant after the settings were built`() {
        var variant = DefaultPalette.dark
        val settings = KdTerminalSettings { variant }
        assertFalse(settings.terminalColorPalette is KdAnsiPalette)

        variant = KdPaletteVariant(DefaultDarkColors, DefaultDarkCvd, true, ansi = ansiList())
        assertTrue(settings.terminalColorPalette is KdAnsiPalette)
    }

    @Test
    fun `a default style built earlier follows the variant when it is read later`() {
        var variant = RetroPalette.dark
        val settings = KdTerminalSettings { variant }
        val style = settings.defaultTextStyle()
        val fg = settings.defaultForeground
        val bg = settings.defaultBackground
        assertEquals(RetroDarkColors.terminalFg.toJediterm().rgb, style.foreground!!.toColor().rgb)

        // The cells already in a widget hold these very TerminalColor objects, so a palette switch
        // must change what they resolve to without anyone replacing them.
        variant = DefaultPalette.dark

        val fgExpected = DefaultDarkColors.terminalFg.toJediterm().rgb
        val bgExpected = DefaultDarkColors.terminalBg.toJediterm().rgb
        assertEquals(fgExpected, style.foreground!!.toColor().rgb)
        assertEquals(bgExpected, style.background!!.toColor().rgb)
        assertEquals(fgExpected, fg.toColor().rgb)
        assertEquals(bgExpected, bg.toColor().rgb)
    }
}
