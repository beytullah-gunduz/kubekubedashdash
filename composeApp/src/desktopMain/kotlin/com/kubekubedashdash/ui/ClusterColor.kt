package com.kubekubedashdash.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

/**
 * Deterministic per-cluster color + initial letter, derived from the kubeconfig
 * context name. Same context name → same look across tabs, app restarts, and
 * windows, so each cluster has a stable visual identity (Decision 4 in
 * .docs/multi-cluster-plan.md).
 *
 * The hue is taken from the context-name hash; saturation/lightness are fixed
 * to stay legible against the app's Material surface in both light and dark
 * themes.
 */
data class ClusterColor(
    val hue: Float,
    val saturation: Float = 0.55f,
    val lightness: Float = 0.55f,
) {
    val composeColor: Color get() = Color.hsl(hue, saturation, lightness)

    fun shifted(deltaLightness: Float): Color = Color.hsl(hue, saturation, (lightness + deltaLightness).coerceIn(0f, 1f))

    companion object {
        fun fromContext(context: String): ClusterColor {
            val hash = context.hashCode().absoluteValue
            return ClusterColor(hue = (hash % 360).toFloat())
        }
    }
}

/**
 * Single-character glyph for a cluster chip. Uses the first letter/digit of the
 * context name, uppercased. Falls back to `?` for empty/non-alphanumeric names.
 */
fun clusterInitial(context: String): String = context.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
