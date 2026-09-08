package com.kubekubedashdash.ui

import androidx.compose.ui.graphics.Color
import com.kubekubedashdash.util.DemoContext
import kotlin.math.absoluteValue

/**
 * Deterministic per-cluster color + initial letter, derived from the kubeconfig
 * context name. Demo labels fold to the demo row's key (see
 * [DemoContext.preferenceKey]); any other string is its own key. Same context
 * name → same look across tabs, app restarts, and windows, so each cluster has
 * a stable visual identity (Decision 4 in
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
    /** User-chosen override color; when set, overrides HSL-derived values. */
    val override: Color? = null,
) {
    val composeColor: Color get() = override ?: Color.hsl(hue, saturation, lightness)

    fun shifted(deltaLightness: Float): Color = override
        ?: Color.hsl(hue, saturation, (lightness + deltaLightness).coerceIn(0f, 1f))

    companion object {
        // Every minted demo label ("demo-cluster (mock) #N") folds to the one
        // demo row the picker lists, so the demo cluster keeps one hue and one
        // override across re-mints (F11). A real context is its own key.
        fun fromContext(context: String): ClusterColor {
            val hash = DemoContext.preferenceKey(context).hashCode().absoluteValue
            return ClusterColor(hue = (hash % 360).toFloat())
        }

        fun effectiveColor(context: String, overrides: Map<String, String>): ClusterColor {
            val hex = overrides[DemoContext.preferenceKey(context)] ?: return fromContext(context)
            return fromContext(context).copy(override = parseHex(hex))
        }

        fun parseHex(hex: String): Color? = try {
            val clean = hex.trimStart('#').padStart(6, '0')
            Color(
                red = clean.substring(0, 2).toInt(16) / 255f,
                green = clean.substring(2, 4).toInt(16) / 255f,
                blue = clean.substring(4, 6).toInt(16) / 255f,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Single-character glyph for a cluster chip. Uses the first letter/digit of the
 * context name, uppercased. Falls back to `?` for empty/non-alphanumeric names.
 */
fun clusterInitial(context: String): String = context.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
