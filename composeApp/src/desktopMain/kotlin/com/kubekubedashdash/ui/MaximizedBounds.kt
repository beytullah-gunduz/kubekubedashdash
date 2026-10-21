package com.kubekubedashdash.ui

import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit

private val isMacOS: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * Pins this frame's maximized bounds to the usable area (screen minus menu bar and Dock) of the
 * screen holding the center of [target], or of the frame's own screen when [target] is null or
 * on no screen.
 *
 * Works around an OpenJDK deadlock on macOS. Maximizing an undecorated frame whose maximized
 * bounds are unset makes `LWWindowPeer.getMaximizedBounds()` ask the AppKit main thread for the
 * screen insets while it holds the peer's state lock. A mouse event the main thread is delivering
 * to that window needs the same lock, so neither thread moves and the app freezes for good. With
 * the bounds pinned, the peer never asks. JBR caches the insets, so dev runs never hang; the
 * installers bundle Temurin, which queries them on every call.
 *
 * Call on the EDT, holding no AWT lock (the insets query here waits for the main thread too),
 * before each maximize: the pin does not follow the window to another screen or a Dock resize.
 * No-op off macOS, where AWT's own default stays in charge.
 */
fun Frame.pinMaximizedBounds(target: Rectangle? = null) {
    if (!isMacOS) return
    runCatching {
        val config = target?.let { screenContaining(Point(it.centerX.toInt(), it.centerY.toInt())) }
            ?: graphicsConfiguration
        val screen = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        maximizedBounds = Rectangle(
            screen.x + insets.left,
            screen.y + insets.top,
            screen.width - insets.left - insets.right,
            screen.height - insets.top - insets.bottom,
        )
    }
}

private fun screenContaining(point: Point): GraphicsConfiguration? = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    .map { it.defaultConfiguration }
    .firstOrNull { it.bounds.contains(point) }
