package com.kubekubedashdash.ui.crt

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import com.kubekubedashdash.ThemeManager
import kotlin.math.max

// Per-mode alphas (D13, D4: lighter in light). Named constants — see CrtFrame.kt's per-mode
// look for the same "one place to tune" rationale.
private const val SCANLINE_ALPHA_DARK = 0.14f
private const val SCANLINE_ALPHA_LIGHT = 0.08f
private const val VIGNETTE_ALPHA_DARK = 0.22f
private const val VIGNETTE_ALPHA_LIGHT = 0.12f

/**
 * Builds the scanline tile (WS4 "Tile construction", exact): a 1x3 **physical-pixel**
 * [ImageBitmap] with rows 0 and 1 left transparent and row 2 painted black at [alpha]. Tiled
 * with [TileMode.Repeated] over the whole draw area, this keeps the scanline pitch at 3 physical
 * pixels regardless of UI zoom (DrawScope units are pixels). `internal` so the pure test can read
 * the rows back via `toPixelMap()`.
 */
internal fun scanlineTile(alpha: Float): ImageBitmap = ImageBitmap(1, 3).also { tile ->
    Canvas(tile).drawRect(
        Rect(0f, 2f, 1f, 3f),
        Paint().apply { color = Color.Black.copy(alpha = alpha) },
    )
}

/**
 * The optional "CRT scanlines" overlay (D13): faint scanlines plus a darkened-corner vignette,
 * static (D7) — it never animates, so a monitoring app left open all day does not redraw at
 * 60 fps. [enabled] is read on every frame, inside the draw lambda, so the Settings switch (and
 * Retro/Default) take effect without a recomposition; when it reports `false` the node draws only
 * `drawContent()`, so Default — and Retro with the switch off — stay pixel-identical to having no
 * modifier at all.
 *
 * `ThemeManager.isDarkTheme` is read inside the `drawWithCache` block (not per frame), so the
 * tile and vignette alphas follow a dark/light switch without an extra `remember` key — the cache
 * block re-runs whenever a state read inside it changes, or the layout size changes.
 *
 * Known blind spots (D13): AlertDialogs, dropdown/context menus and tooltips render in their own
 * layers and are not covered by this modifier; the JediTerm terminal's `SwingPanel` (D9) is not
 * covered either.
 */
fun Modifier.crtScanlines(enabled: () -> Boolean): Modifier = drawWithCache {
    val dark = ThemeManager.isDarkTheme
    val tileAlpha = if (dark) SCANLINE_ALPHA_DARK else SCANLINE_ALPHA_LIGHT
    val vignetteAlpha = if (dark) VIGNETTE_ALPHA_DARK else VIGNETTE_ALPHA_LIGHT

    val scanlineBrush = ShaderBrush(ImageShader(scanlineTile(tileAlpha), TileMode.Repeated, TileMode.Repeated))
    val vignetteBrush = Brush.radialGradient(
        0f to Color.Transparent,
        1f to Color.Black.copy(alpha = vignetteAlpha),
        radius = max(size.width, size.height) * 0.75f,
    )

    onDrawWithContent {
        drawContent()
        if (enabled()) {
            drawRect(scanlineBrush)
            drawRect(vignetteBrush)
        }
    }
}
