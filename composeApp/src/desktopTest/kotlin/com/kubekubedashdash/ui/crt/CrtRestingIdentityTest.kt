package com.kubekubedashdash.ui.crt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [drawCrtFrame]'s resting-state identity (WS3 "The frame" spec, step 5): at
 * ignite = open = glow = 1, the two edges coincide with size / 2, so both glass bands are
 * zero height, the wash is 0 and the edge alpha is 0 — the output must be pixel-identical to
 * having no modifier at all. Renders to a raster (Skiko) surface via [runSkikoComposeUiTest],
 * so it is headless-safe on every CI OS; `captureToImage()` is a member of
 * `SkikoComposeUiTest`, not of the `ComposeUiTest` receiver `runComposeUiTest` gives.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrtRestingIdentityTest {

    private fun captureContent(withFrame: Boolean, look: CrtLook): IntArray {
        var buffer = IntArray(0)
        runSkikoComposeUiTest(size = Size(200f, 120f), density = Density(1f)) {
            setContent {
                Box(
                    modifier = Modifier
                        .size(200.dp, 120.dp)
                        .then(
                            if (withFrame) {
                                Modifier.drawWithContent { drawCrtFrame(ignite = 1f, open = 1f, glow = 1f, look = look) }
                            } else {
                                Modifier
                            },
                        )
                        .background(Color(0xFF336699)),
                )
            }
            waitForIdle()
            buffer = captureToImage().toPixelMap().buffer
        }
        return buffer
    }

    @Test
    fun `resting frame is pixel-identical to no modifier, in retro-dark`() {
        val look = crtLook(CrtScale.SCREEN, dark = true, primary = Color(0xFF7FD8EA))
        val plain = captureContent(withFrame = false, look = look)
        val framed = captureContent(withFrame = true, look = look)
        assertTrue(plain.contentEquals(framed), "resting drawCrtFrame must not change any pixel (retro-dark, glass included)")
    }

    @Test
    fun `resting frame is pixel-identical to no modifier, in retro-light`() {
        val look = crtLook(CrtScale.SCREEN, dark = false, primary = Color(0xFF00606B))
        val plain = captureContent(withFrame = false, look = look)
        val framed = captureContent(withFrame = true, look = look)
        assertTrue(plain.contentEquals(framed), "resting drawCrtFrame must not change any pixel (retro-light, glass included)")
    }

    // Content that draws outside its own bounds, like a Material Surface's shadow layer. A frame
    // that still clipped at rest would cut the overflow, which the in-bounds tests cannot see.
    private fun captureOverflow(withFrame: Boolean, look: CrtLook): IntArray {
        var buffer = IntArray(0)
        runSkikoComposeUiTest(size = Size(200f, 120f), density = Density(1f)) {
            setContent {
                Box(Modifier.size(200.dp, 120.dp).background(Color.White)) {
                    Box(
                        modifier = Modifier
                            .padding(40.dp)
                            .size(120.dp, 40.dp)
                            .then(
                                if (withFrame) {
                                    Modifier.drawWithContent { drawCrtFrame(ignite = 1f, open = 1f, glow = 1f, look = look) }
                                } else {
                                    Modifier
                                },
                            )
                            .drawBehind {
                                drawRect(Color.Red, topLeft = Offset(-20f, -20f), size = Size(size.width + 40f, size.height + 40f))
                            }
                            .background(Color(0xFF336699)),
                    )
                }
            }
            waitForIdle()
            buffer = captureToImage().toPixelMap().buffer
        }
        return buffer
    }

    @Test
    fun `resting frame does not clip content drawn outside the node`() {
        val look = crtLook(CrtScale.SCREEN, dark = true, primary = Color(0xFF7FD8EA))
        val plain = captureOverflow(withFrame = false, look = look)
        val framed = captureOverflow(withFrame = true, look = look)
        // (30, 30) lies in the 20 px overflow band outside the 120x40 box at (40, 40).
        assertTrue(plain[30 * 200 + 30] == Color.Red.toArgb(), "the overflow must be visible without a frame")
        assertTrue(plain.contentEquals(framed), "a resting frame must not clip content drawn outside the node")
    }

    // Canary for the two identity tests above: they would also pass if the capture came back
    // blank. A shut aperture paints glass over almost the whole box, so it must differ.
    @Test
    fun `a closed aperture does change pixels, so the capture is sensitive`() {
        val look = crtLook(CrtScale.SCREEN, dark = true, primary = Color(0xFF7FD8EA))
        val plain = captureContent(withFrame = false, look = look)
        var closed = IntArray(0)
        runSkikoComposeUiTest(size = Size(200f, 120f), density = Density(1f)) {
            setContent {
                Box(
                    modifier = Modifier
                        .size(200.dp, 120.dp)
                        .drawWithContent { drawCrtFrame(ignite = 1f, open = 0f, glow = 1f, look = look) }
                        .background(Color(0xFF336699)),
                )
            }
            waitForIdle()
            closed = captureToImage().toPixelMap().buffer
        }
        assertTrue(plain.isNotEmpty() && plain.any { it == 0xFF336699.toInt() }, "the plain capture must contain the box colour")
        assertTrue(!plain.contentEquals(closed), "a shut aperture must change pixels")
    }
}
