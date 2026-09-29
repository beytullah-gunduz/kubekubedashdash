package com.kubekubedashdash.theme

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks the five editor palettes (theme-expansion plan section 3.3.5 to 3.3.9, WS5) to their planned
 * literals: six tokens per palette and mode, and ANSI index 0 and 1 of every terminal list. It catches a
 * mis-pasted block; contrast is covered by PaletteGateTest. It reads the specs directly, so it never
 * touches ThemeManager or the preference store.
 */
class EditorPaletteLockTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    private val editorSpecs = mapOf(
        "Solarized" to SolarizedPalette,
        "Gruvbox" to GruvboxPalette,
        "Catppuccin" to CatppuccinPalette,
        "Nord" to NordPalette,
        "Dracula" to DraculaPalette,
    )

    private fun assertPinned(
        label: String,
        colors: KdColors,
        background: Color,
        textPrimary: Color,
        primary: Color,
        success: Color,
        error: Color,
        terminalBg: Color,
    ) {
        assertEquals(background, colors.background, "$label background")
        assertEquals(textPrimary, colors.textPrimary, "$label textPrimary")
        assertEquals(primary, colors.primary, "$label primary")
        assertEquals(success, colors.success, "$label success")
        assertEquals(error, colors.error, "$label error")
        assertEquals(terminalBg, colors.terminalBg, "$label terminalBg")
    }

    @Test
    fun `Solarized dark pins its six tokens`() {
        assertPinned(
            label = "Solarized dark",
            colors = SolarizedPalette.dark.colors,
            background = Color(0xFF002B36),
            textPrimary = Color(0xFFD8E7E7),
            primary = Color(0xFF41A1EA),
            success = Color(0xFF9CB130),
            error = Color(0xFFFE8377),
            terminalBg = Color(0xFF002B36),
        )
    }

    @Test
    fun `Solarized light pins its six tokens`() {
        assertPinned(
            label = "Solarized light",
            colors = SolarizedPalette.light.colors,
            background = Color(0xFFF8F1DE),
            textPrimary = Color(0xFF344950),
            primary = Color(0xFF0071B3),
            success = Color(0xFF5F6E00),
            error = Color(0xFFCB1B1F),
            terminalBg = Color(0xFFF8F1DE),
        )
    }

    @Test
    fun `Gruvbox dark pins its six tokens`() {
        assertPinned(
            label = "Gruvbox dark",
            colors = GruvboxPalette.dark.colors,
            background = Color(0xFF282828),
            textPrimary = Color(0xFFF8E8BE),
            primary = Color(0xFF83A598),
            success = Color(0xFFB8BB26),
            error = Color(0xFFFF7C68),
            terminalBg = Color(0xFF282828),
        )
    }

    @Test
    fun `Gruvbox light pins its six tokens`() {
        assertPinned(
            label = "Gruvbox light",
            colors = GruvboxPalette.light.colors,
            background = Color(0xFFFBF1C7),
            textPrimary = Color(0xFF3C3836),
            primary = Color(0xFF076678),
            success = Color(0xFF6C6804),
            error = Color(0xFF9D0006),
            terminalBg = Color(0xFFFBF1C7),
        )
    }

    @Test
    fun `Catppuccin dark pins its six tokens`() {
        assertPinned(
            label = "Catppuccin dark",
            colors = CatppuccinPalette.dark.colors,
            background = Color(0xFF1E1E2E),
            textPrimary = Color(0xFFEBF0FE),
            primary = Color(0xFF89B4FA),
            success = Color(0xFFA6E3A1),
            error = Color(0xFFFF9CB6),
            terminalBg = Color(0xFF1E1E2E),
        )
    }

    @Test
    fun `Catppuccin light pins its six tokens`() {
        assertPinned(
            label = "Catppuccin light",
            colors = CatppuccinPalette.light.colors,
            background = Color(0xFFE6E9EF),
            textPrimary = Color(0xFF3F425B),
            primary = Color(0xFF135CEA),
            success = Color(0xFF1D7102),
            error = Color(0xFFC40433),
            terminalBg = Color(0xFFE6E9EF),
        )
    }

    @Test
    fun `Nord dark pins its six tokens`() {
        assertPinned(
            label = "Nord dark",
            colors = NordPalette.dark.colors,
            background = Color(0xFF2E3440),
            textPrimary = Color(0xFFF6F9FE),
            primary = Color(0xFF88C0D0),
            success = Color(0xFFAAC693),
            error = Color(0xFFFFA5AB),
            terminalBg = Color(0xFF2E3440),
        )
    }

    @Test
    fun `Nord light pins its six tokens`() {
        assertPinned(
            label = "Nord light",
            colors = NordPalette.light.colors,
            background = Color(0xFFE9ECF2),
            textPrimary = Color(0xFF2E3440),
            primary = Color(0xFF496B95),
            success = Color(0xFF566E40),
            error = Color(0xFFA64A54),
            terminalBg = Color(0xFFE9ECF2),
        )
    }

    @Test
    fun `Dracula dark pins its six tokens`() {
        assertPinned(
            label = "Dracula dark",
            colors = DraculaPalette.dark.colors,
            background = Color(0xFF282A36),
            textPrimary = Color(0xFFF8F8F2),
            primary = Color(0xFFBD93F9),
            success = Color(0xFF50FA7B),
            error = Color(0xFFFF9790),
            terminalBg = Color(0xFF282A36),
        )
    }

    @Test
    fun `Dracula light pins its six tokens`() {
        assertPinned(
            label = "Dracula light",
            colors = DraculaPalette.light.colors,
            background = Color(0xFFECE9DF),
            textPrimary = Color(0xFF1F1F1F),
            primary = Color(0xFF644AC9),
            success = Color(0xFF14710A),
            error = Color(0xFFC23021),
            terminalBg = Color(0xFFECE9DF),
        )
    }

    @Test
    fun `every editor variant carries exactly 16 ANSI colours`() {
        for ((name, spec) in editorSpecs) {
            for ((mode, variant) in listOf("dark" to spec.dark, "light" to spec.light)) {
                val ansi = assertNotNull(variant.ansi, "$name $mode has no ANSI list")
                assertEquals(16, ansi.size, "$name $mode ANSI size")
            }
        }
    }

    @Test
    fun `Nord shares one ANSI list across both modes`() {
        assertSame(NordPalette.dark.ansi, NordPalette.light.ansi)
    }

    @Test
    fun `ANSI index 0 and 1 are pinned per list`() {
        val pins = listOf(
            Triple("Solarized dark", SolarizedPalette.dark.ansi, listOf(Color(0xFF073642), Color(0xFFDC322F))),
            Triple("Solarized light", SolarizedPalette.light.ansi, listOf(Color(0xFFEEE8D5), Color(0xFFDC322F))),
            Triple("Gruvbox dark", GruvboxPalette.dark.ansi, listOf(Color(0xFF282828), Color(0xFFCC241D))),
            Triple("Gruvbox light", GruvboxPalette.light.ansi, listOf(Color(0xFFFBF1C7), Color(0xFFCC241D))),
            Triple("Catppuccin dark", CatppuccinPalette.dark.ansi, listOf(Color(0xFF45475A), Color(0xFFF38BA8))),
            Triple("Catppuccin light", CatppuccinPalette.light.ansi, listOf(Color(0xFF5C5F77), Color(0xFFD20F39))),
            Triple("Nord (both modes)", NordPalette.dark.ansi, listOf(Color(0xFF3B4252), Color(0xFFBF616A))),
            Triple("Dracula dark", DraculaPalette.dark.ansi, listOf(Color(0xFF21222C), Color(0xFFFF5555))),
            Triple("Dracula light", DraculaPalette.light.ansi, listOf(Color(0xFFFFFBEB), Color(0xFFCB3A2A))),
        )
        for ((label, ansi, expected) in pins) {
            val list = assertNotNull(ansi, "$label has no ANSI list")
            assertEquals(expected, list.take(2), "$label ANSI index 0 and 1")
        }
    }
}
