package com.kubekubedashdash.util

import com.kubekubedashdash.model.ScreenBounds
import java.awt.GraphicsEnvironment

/**
 * Attached monitors as [ScreenBounds]. AWT reports points on macOS (same
 * space as Compose dp); on other platforms with display scaling the two can
 * differ, which only makes the off-screen check more lenient. Headless or
 * failing environments yield an empty list, which callers treat as "unknown".
 */
object ScreenBoundsProvider {
    fun current(): List<ScreenBounds> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
            val b = device.defaultConfiguration.bounds
            ScreenBounds(b.x, b.y, b.width, b.height)
        }
    }.getOrDefault(emptyList())
}
