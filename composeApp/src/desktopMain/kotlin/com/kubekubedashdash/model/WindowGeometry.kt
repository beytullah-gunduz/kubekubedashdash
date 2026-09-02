package com.kubekubedashdash.model

import kotlinx.serialization.Serializable

/**
 * Saved OS-window geometry in dp. [x] and [y] are null when the platform
 * placed the window (nothing to restore). [widthDp]/[heightDp] are always
 * the FLOATING size; [maximized] says whether to open maximized on top.
 */
@Serializable
data class WindowGeometry(
    val x: Int? = null,
    val y: Int? = null,
    val widthDp: Int,
    val heightDp: Int,
    val maximized: Boolean = false,
) {
    /**
     * True when part of the window's title strip (its top [TITLE_STRIP_DP]
     * dp) lies inside one of [screens]. A window saved on a monitor that is
     * no longer attached must fall back to the platform placement, or the
     * user can never reach it. Positionless geometry is always visible.
     */
    fun isVisibleOn(screens: List<ScreenBounds>): Boolean {
        val wx = x ?: return true
        val wy = y ?: return true
        return screens.any { s ->
            wx + widthDp > s.x && wx < s.x + s.width &&
                wy + TITLE_STRIP_DP > s.y && wy < s.y + s.height
        }
    }

    companion object {
        const val TITLE_STRIP_DP = 40
    }
}

/** One monitor's bounds, in the same dp space as [WindowGeometry]. */
data class ScreenBounds(val x: Int, val y: Int, val width: Int, val height: Int)
