package com.kubekubedashdash.ui.crt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.screenshots.ScreenshotHooks
import kotlin.math.max

/** The three inputs [drawCrtFrame] needs for one frame: aperture ignite/open plus phosphor glow. */
internal data class CrtFrame(val ignite: Float, val open: Float, val glow: Float)

/** Pure; unit-tested. Enter: progress 0→1. Exit: progress 1→0 (Visible→PostExit). */
internal fun crtFrameFor(progress: Float, entering: Boolean, igniteFraction: Float): CrtFrame {
    if (!entering) {
        val e = 1f - progress
        return CrtFrame(1f, 1f - FastOutLinearInEasing.transform(e), 1f - 0.35f * e)
    }
    if (igniteFraction <= 0f) return CrtFrame(1f, LinearOutSlowInEasing.transform(progress), progress)
    return if (progress <= igniteFraction) {
        CrtFrame(progress / igniteFraction, 0f, 0f)
    } else {
        val p2 = (progress - igniteFraction) / (1f - igniteFraction)
        CrtFrame(1f, LinearOutSlowInEasing.transform(p2), p2)
    }
}

/** Per-mode CRT look (D4): the line colour, the tube-glass colour (screen scale only), and the additive-bloom cap. */
internal data class CrtLook(val line: Color, val glass: Color?, val washCap: Float)

internal enum class CrtScale { CARD, SCREEN, CUT }

// Literal colours (§"Per-mode look"). Never derived via lerp(Color, Color, Float) — it
// interpolates in Oklab (ui-graphics Color.kt:518-521), so it would not produce the sRGB mix.
private val RetroDarkCrtLine = Color(0xFFD9F3F9)
private val RetroLightScreenCrtLine = Color(0xFFFFF4DC)
private val RetroDarkGlass = Color(0xFF05060F)
private val RetroLightGlass = Color(0xFF1F1B14)

/**
 * Pure — takes [primary] and [dark] as parameters so it can be tested without composition.
 * Callers pass [KdPrimary] and `ThemeManager.isDarkTheme`. No bloom in retro-light at either
 * scale: additive light on the cream background clips straight to white (D4, R11/m5).
 *
 * Screen-switch look (D15): no glass, so the router's background shows around the aperture;
 * weak bloom in dark, none in light.
 */
internal fun crtLook(scale: CrtScale, dark: Boolean, primary: Color): CrtLook = when (scale) {
    CrtScale.CARD -> if (dark) {
        CrtLook(line = RetroDarkCrtLine, glass = null, washCap = 0.55f)
    } else {
        CrtLook(line = primary, glass = null, washCap = 0f)
    }

    CrtScale.SCREEN -> if (dark) {
        CrtLook(line = RetroDarkCrtLine, glass = RetroDarkGlass, washCap = 0.35f)
    } else {
        CrtLook(line = RetroLightScreenCrtLine, glass = RetroLightGlass, washCap = 0f)
    }

    CrtScale.CUT -> if (dark) {
        CrtLook(line = RetroDarkCrtLine, glass = null, washCap = 0.15f)
    } else {
        CrtLook(line = primary, glass = null, washCap = 0f)
    }
}

// (thickness, alpha) pairs for the two edge rules drawn in drawCrtFrame's step 4.
private val edgeRuleBands = listOf(12.dp to 0.10f, 6.dp to 0.25f, 2.dp to 1.0f)

/**
 * Draws the CRT aperture, tube glass, phosphor bloom and edge rules (WS3 "The frame" spec).
 * Clamps its own inputs, so callers can pass [crtFrameFor] values straight through. At
 * ignite = open = glow = 1 the output is pixel-identical to no modifier at all — the resting
 * identity CrtRestingIdentityTest pins.
 *
 * [content] draws whatever the frame reveals: a node's own live content (the `ContentDrawScope`
 * overload below), or a recorded `GraphicsLayer` snapshot (WS6 `crtCardReveal`, `CrtGhostExit`).
 */
internal fun DrawScope.drawCrtFrame(ignite: Float, open: Float, glow: Float, look: CrtLook, content: DrawScope.() -> Unit) {
    val ignite = ignite.coerceIn(0f, 1f)
    val open = open.coerceIn(0f, 1f)
    val glow = glow.coerceIn(0f, 1f)

    val cy = size.height / 2f
    val half = max(1.dp.toPx(), open * cy)

    // 1. Aperture. The aperture never fully closes; a 1 dp sliver is the scanline itself.
    // Fully open means no clip at all, not a full-bounds clip: the frame stays attached at rest
    // (a revealed dialog card, the router's resident child after a power-on), and a
    // full-bounds clip would still cut anything drawn outside the node, such as a Surface's
    // shadow layer.
    if (open >= 1f) {
        content()
    } else {
        clipRect(left = 0f, top = cy - half, right = size.width, bottom = cy + half) {
            content()
        }
    }

    // 2. Tube glass, painted only outside the aperture — never under the content, so it
    // cannot show through the content's transparent pixels inside the aperture.
    if (look.glass != null) {
        val topHeight = cy - half
        if (topHeight > 0f) {
            drawRect(color = look.glass, topLeft = Offset(0f, 0f), size = Size(size.width, topHeight))
        }
        val bottomTop = cy + half
        val bottomHeight = size.height - bottomTop
        if (bottomHeight > 0f) {
            drawRect(color = look.glass, topLeft = Offset(0f, bottomTop), size = Size(size.width, bottomHeight))
        }
    }

    // 3. Phosphor bloom. Additive (BlendMode.Plus) and banded: 12 stacked rects give a hot
    // centre (12 x bandAlpha) and a dim rim (1 x bandAlpha). Solid rects only — no shader, no
    // layer, no per-frame allocation.
    if (look.washCap > 0f) {
        val wash = (1f - glow / 0.6f).coerceIn(0f, 1f) * look.washCap
        if (wash > 0f) {
            val bandAlpha = wash * 1.1f / 12f
            for (k in 1..12) {
                val frac = k / 12f
                drawRect(
                    color = look.line.copy(alpha = bandAlpha),
                    topLeft = Offset(0f, cy - half * frac),
                    size = Size(size.width, 2f * half * frac),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    // 4. Edge rules, drawn outside the clip on purpose: they ride the aperture edges. While
    // the aperture is shut the two edges coincide, which makes a brighter line.
    val edge = ((1f - glow) / 0.35f).coerceIn(0f, 1f)
    if (edge > 0f) {
        val w = size.width * (0.35f + 0.65f * ignite)
        val x = (size.width - w) / 2f
        for ((thickness, alpha) in edgeRuleBands) {
            val tPx = thickness.toPx()
            for (y in listOf(cy - half, cy + half)) {
                drawRect(
                    color = look.line.copy(alpha = alpha * edge),
                    topLeft = Offset(x, y - tPx / 2f),
                    size = Size(w, tPx),
                )
            }
        }
    }
}

/**
 * Existing entry point for a node's own live content (WS3): delegates to the [content]-taking
 * overload above, passing the node's `drawContent()` as the content lambda. The trailing lambda
 * below is itself an argument to a call named `drawCrtFrame`, so its own implicit `DrawScope`
 * receiver shadows this function's `this@drawCrtFrame` label — `contentScope` captures the outer
 * `ContentDrawScope` receiver as a plain local so `drawContent()` still resolves against it.
 */
internal fun ContentDrawScope.drawCrtFrame(ignite: Float, open: Float, glow: Float, look: CrtLook) {
    val contentScope = this
    drawCrtFrame(ignite, open, glow, look) { contentScope.drawContent() }
}

/**
 * Card-scale reveal for AlertDialogs and in-tree modal cards (WS3 step 3.3). Entrance only
 * (D6) — dismissal, Esc and Cancel all stay instant. Born at 0 only in Retro, so there is no
 * first-frame flash of the settled card in Default.
 *
 * [ghost], when non-null, turns on the WS6 snapshot path: the card records itself into
 * [CrtGhost.layer] on every draw, so a sibling [CrtGhostExit] can play its collapse after the
 * card has left composition. Recording is retro-only, exactly like the reveal itself — Default
 * never touches the ghost.
 */
@Composable
fun Modifier.crtCardReveal(ghost: CrtGhost? = null): Modifier {
    val retro = ThemeManager.isRetro
    val reveal = remember { Animatable(if (retro) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(180, easing = LinearEasing))
    }
    if (!retro) return this
    val look = crtLook(CrtScale.CARD, ThemeManager.isDarkTheme, KdPrimary)
    val positioned = if (ghost != null) {
        this.onGloballyPositioned {
            ghost.cardTopLeftInRoot = it.positionInRoot()
            ghost.cardSize = it.size
        }
    } else {
        this
    }
    return positioned.drawWithContent {
        val r = reveal.value
        if (ghost != null) {
            // Record on each draw — not once — so the snapshot always matches the card's
            // current content; draws only happen on invalidation (D7), so this is not a
            // per-frame cost beyond what the card would already pay.
            ghost.layer.record { this@drawWithContent.drawContent() }
            ghost.hasSnapshot = true
            drawCrtFrame(ignite = 1f, open = LinearOutSlowInEasing.transform(r), glow = r, look = look) {
                drawLayer(ghost.layer)
            }
        } else {
            drawCrtFrame(ignite = 1f, open = LinearOutSlowInEasing.transform(r), glow = r, look = look)
        }
    }
}

/**
 * Hoisted window power-on state (WS3 steps 3.1/3.2). Hoisted above
 * `MaybeProvideSessionLocals` in `App` so the animation survives the root `Box` being recreated
 * when the title session flips between null and non-null (opening the first cluster tab, or
 * closing the last one).
 *
 * Plays once at window creation in Retro; plays again whenever the window enters Retro (the
 * Style switch) or its dark/light mode flips while in Retro. Never plays on the way out of
 * Retro.
 *
 * Each (retro, dark) pair gets its own progress, created in composition, so the first frame drawn
 * in the new look already sees the dark tube. Snapping one progress to 0 from the effect came too
 * late: the effect runs after that frame, which then showed the finished Retro screen before the
 * power-on (for as long as the slow first Retro frame took).
 */
@Composable
fun rememberCrtScreenPowerOn(): Animatable<Float, AnimationVector1D> {
    val retro = ThemeManager.isRetro
    val dark = ThemeManager.isDarkTheme
    val progress = remember(retro, dark) { Animatable(if (retro) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(370 * ScreenshotHooks.crtTimeScale.value, easing = LinearEasing))
        }
    }
    return progress
}

/**
 * Non-composable: always returns a `drawWithContent` node, so Default draws `drawContent()`
 * with no extra allocation (D3). All reads happen inside the draw lambda, so animation frames
 * invalidate draw only and never recompose (D6).
 */
fun Modifier.crtScreenPowerOn(progress: Animatable<Float, AnimationVector1D>): Modifier = this.drawWithContent {
    if (!ThemeManager.isRetro || progress.value >= 1f) {
        drawContent()
    } else {
        val (ignite, open, glow) = crtFrameFor(progress.value, entering = true, igniteFraction = 120f / 370f)
        drawCrtFrame(ignite, open, glow, crtLook(CrtScale.SCREEN, ThemeManager.isDarkTheme, KdPrimary))
    }
}
