package com.kubekubedashdash.ui.crt

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.Screen
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure cases for [crtFrameFor], [crtSwapStyle] and [crtLook] (WS3 "The frame" spec, plan §5
 * "WS3 — CRT motion", tests table). None of these touch composition or [ThemeManager], so
 * there is no data-directory guard to copy here.
 */
class CrtFrameTest {

    // The window power-on's ignite fraction (120 ms of a 370 ms run), used throughout as a
    // representative non-zero igniteFraction.
    private val igniteFraction = 120f / 370f

    // --- crtFrameFor ---

    @Test
    fun `enter at progress 0 is fully closed`() {
        val frame = crtFrameFor(progress = 0f, entering = true, igniteFraction = igniteFraction)
        assertEquals(0f, frame.ignite)
        assertEquals(0f, frame.open)
        assertEquals(0f, frame.glow)
    }

    @Test
    fun `enter at the ignite fraction has ignited but not yet opened`() {
        val frame = crtFrameFor(progress = igniteFraction, entering = true, igniteFraction = igniteFraction)
        assertEquals(1f, frame.ignite)
        assertEquals(0f, frame.open)
        assertEquals(0f, frame.glow)
    }

    @Test
    fun `enter at progress 1 is fully settled`() {
        val frame = crtFrameFor(progress = 1f, entering = true, igniteFraction = igniteFraction)
        assertEquals(1f, frame.ignite)
        assertEquals(1f, frame.open)
        assertEquals(1f, frame.glow)
    }

    @Test
    fun `enter with no ignite fraction opens and glows directly from progress`() {
        val frame = crtFrameFor(progress = 0.5f, entering = true, igniteFraction = 0f)
        assertEquals(1f, frame.ignite)
        assertEquals(0.5f, frame.glow)
    }

    @Test
    fun `exit at progress 1 is the resting state`() {
        val frame = crtFrameFor(progress = 1f, entering = false, igniteFraction = igniteFraction)
        assertEquals(1f, frame.ignite)
        assertEquals(1f, frame.open)
        assertEquals(1f, frame.glow)
    }

    @Test
    fun `exit at progress 0 is fully collapsed, so edge is 1 and wash is 0`() {
        val frame = crtFrameFor(progress = 0f, entering = false, igniteFraction = igniteFraction)
        assertEquals(0f, frame.open)
        assertTrue(abs(frame.glow - 0.65f) < 0.0001f, "expected glow ~= 0.65, was ${frame.glow}")

        val edge = ((1f - frame.glow) / 0.35f).coerceIn(0f, 1f)
        val wash = (1f - frame.glow / 0.6f).coerceIn(0f, 1f)
        assertEquals(1f, edge)
        assertEquals(0f, wash)
    }

    // --- crtSwapStyle ---

    @Test
    fun `Connecting to ClusterOverview is a compressed power-on`() {
        assertEquals(
            SwapStyle.COMPRESSED_ON,
            crtSwapStyle(Screen.Main.Connecting, Screen.Main.ClusterOverview),
        )
    }

    @Test
    fun `a ConnectionError countdown tick is not a swap`() {
        assertEquals(
            SwapStyle.NONE,
            crtSwapStyle(Screen.Main.ConnectionError("e", 5), Screen.Main.ConnectionError("e", 4)),
        )
    }

    @Test
    fun `ClusterOverview to Connecting is a power-off cut`() {
        assertEquals(
            SwapStyle.POWER_OFF_CUT,
            crtSwapStyle(Screen.Main.ClusterOverview, Screen.Main.Connecting),
        )
    }

    @Test
    fun `Nodes to Pods is a channel cut`() {
        assertEquals(
            SwapStyle.CUT,
            crtSwapStyle(Screen.Main.Nodes(), Screen.Main.Pods()),
        )
    }

    @Test
    fun `re-keying Nodes with a different selection is not a swap`() {
        assertEquals(
            SwapStyle.NONE,
            crtSwapStyle(Screen.Main.Nodes(selectNodeName = "a"), Screen.Main.Nodes(selectNodeName = "b")),
        )
    }

    @Test
    fun `hopping between two different CRDs is still a channel cut`() {
        assertEquals(
            SwapStyle.CUT,
            crtSwapStyle(
                Screen.Main.CustomResource("a.io", "v1", "A", "as", true),
                Screen.Main.CustomResource("b.io", "v1", "B", "bs", true),
            ),
        )
    }

    @Test
    fun `an equal CustomResource is not a swap`() {
        val screen = Screen.Main.CustomResource("a.io", "v1", "A", "as", true)
        assertEquals(SwapStyle.NONE, crtSwapStyle(screen, screen))
    }

    // --- crtLook ---

    @Test
    fun `crtLook matches the per-mode look table`() {
        val retroDarkPrimary = Color(0xFF7FD8EA)
        val retroLightPrimary = Color(0xFF00606B)

        val cardDark = crtLook(CrtScale.CARD, dark = true, primary = retroDarkPrimary)
        assertEquals(Color(0xFFD9F3F9), cardDark.line)
        assertNull(cardDark.glass)
        assertEquals(0.55f, cardDark.washCap)

        val cardLight = crtLook(CrtScale.CARD, dark = false, primary = retroLightPrimary)
        assertEquals(retroLightPrimary, cardLight.line)
        assertNull(cardLight.glass)
        assertEquals(0f, cardLight.washCap)

        val screenDark = crtLook(CrtScale.SCREEN, dark = true, primary = retroDarkPrimary)
        assertEquals(Color(0xFFD9F3F9), screenDark.line)
        assertEquals(Color(0xFF05060F), screenDark.glass)
        assertEquals(0.35f, screenDark.washCap)

        val screenLight = crtLook(CrtScale.SCREEN, dark = false, primary = retroLightPrimary)
        assertEquals(Color(0xFFFFF4DC), screenLight.line)
        assertEquals(Color(0xFF1F1B14), screenLight.glass)
        assertEquals(0f, screenLight.washCap)
    }
}
