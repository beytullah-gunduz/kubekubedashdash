package com.kubekubedashdash.theme

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.util.SystemDirectories
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Default palette's derived Material scheme (theme-expansion plan §3.2 and §3.4, D7): the
 * hand-written Default schemes are gone, so the roles a material3 component reads must come from the
 * palette tokens, and the four derived roles must match the plan's hex pins. Reads the palette table
 * directly; touches no [com.kubekubedashdash.ThemeManager] state.
 */
class DefaultSchemeTest {

    @BeforeTest
    fun guardDataDirectory() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
    }

    // kdMix runs in float32, so the plan's 8-bit pins are compared at 1/255 per channel.
    private fun assertNear(expected: Color, actual: Color, message: String) {
        val tolerance = 1f / 255f + 1e-4f
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
            "$message: expected $expected, got $actual",
        )
    }

    private fun assertTokenRoles(variant: KdPaletteVariant, label: String) {
        val scheme = variant.scheme
        val colors = variant.colors
        assertEquals(colors.surface, scheme.surfaceContainer, "$label surfaceContainer == surface")
        assertEquals(colors.selected, scheme.secondaryContainer, "$label secondaryContainer == selected")
        assertEquals(colors.selected, scheme.primaryContainer, "$label primaryContainer == selected")
        assertEquals(colors.onError, scheme.onError, "$label onError == the onError token")
        assertEquals(colors.primary, scheme.secondary, "$label secondary == primary (Default keeps focus == primary)")
    }

    @Test
    fun `Default dark scheme takes its roles from the tokens`() {
        assertTokenRoles(DefaultPalette.dark, "Default dark")
    }

    @Test
    fun `Default light scheme takes its roles from the tokens`() {
        assertTokenRoles(DefaultPalette.light, "Default light")
    }

    @Test
    fun `Default dark scheme matches the plan's derived pins`() {
        val scheme = DefaultPalette.dark.scheme
        assertNear(Color(0xFF503D42), scheme.errorContainer, "Default dark errorContainer")
        assertNear(Color(0xFF3E4451), scheme.surfaceContainerHighest, "Default dark surfaceContainerHighest")
        assertNear(Color(0xFF323845), scheme.surfaceContainerHigh, "Default dark surfaceContainerHigh")
        assertNear(Color(0xFF161819), scheme.surfaceDim, "Default dark surfaceDim")
    }

    @Test
    fun `Default light scheme matches the plan's derived pins`() {
        val scheme = DefaultPalette.light.scheme
        assertNear(Color(0xFFF5D8D8), scheme.errorContainer, "Default light errorContainer")
        assertNear(Color(0xFFF1F5F9), scheme.surfaceContainerHighest, "Default light surfaceContainerHighest")
        assertNear(Color(0xFFF8FAFC), scheme.surfaceContainerHigh, "Default light surfaceContainerHigh")
        assertNear(Color(0xFFEBEDF0), scheme.surfaceDim, "Default light surfaceDim")
    }
}
