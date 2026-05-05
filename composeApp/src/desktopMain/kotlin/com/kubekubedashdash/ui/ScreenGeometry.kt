package com.kubekubedashdash.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density

/**
 * Convert this layout's bounds to a screen-space rectangle that
 * [com.kubekubedashdash.services.WorkspaceManager.handleChipRelease] can hit-
 * test against AWT's `MouseInfo` cursor location at chip-drag end.
 *
 * Compose's `positionOnScreen` returns *physical* pixels on JBR with Retina
 * (a 2x scaling), but `MouseInfo.getPointerInfo().location` returns *logical*
 * pixels — feeding the two into the same hit-test silently shifted every
 * drop zone south-east by 2x on a 2x display. We compute the screen rect
 * via AWT (`Window.locationOnScreen`, logical) plus the layout's
 * window-local position converted from physical pixels through [density],
 * which keeps both sides of the comparison in AWT's coordinate space.
 *
 * Returns null if the layout is detached or the AWT window is not yet
 * showing on screen.
 */
internal fun LayoutCoordinates.toScreenRect(
    awtWindow: java.awt.Window,
    density: Density,
): Rect? {
    if (!isAttached) return null
    val winOrigin = runCatching { awtWindow.locationOnScreen }.getOrNull() ?: return null
    val localPx = positionInWindow()
    val sizePx = size
    val localDpX: Float
    val localDpY: Float
    val sizeDpX: Float
    val sizeDpY: Float
    with(density) {
        localDpX = localPx.x.toDp().value
        localDpY = localPx.y.toDp().value
        sizeDpX = sizePx.width.toDp().value
        sizeDpY = sizePx.height.toDp().value
    }
    val left = winOrigin.x + localDpX
    val top = winOrigin.y + localDpY
    return Rect(
        left = left,
        top = top,
        right = left + sizeDpX,
        bottom = top + sizeDpY,
    )
}
