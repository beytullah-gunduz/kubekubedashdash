package com.kubekubedashdash.util

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.kubekubedashdash.model.WindowGeometry

/** The app's default window size; also the fallback when a state reports an unspecified size. */
val DEFAULT_WINDOW_SIZE = DpSize(1440.dp, 960.dp)

private val DEFAULT_GEOMETRY = WindowGeometry(
    widthDp = DEFAULT_WINDOW_SIZE.width.value.toInt(),
    heightDp = DEFAULT_WINDOW_SIZE.height.value.toInt(),
)

/**
 * Live Compose window state -> saved geometry.
 *
 * While the window is maximized or full-screen, AWT reports the MAXIMIZED
 * bounds and Compose copies them straight into [WindowState]. Saving those
 * would make the restored window's un-maximized bounds equal to the screen,
 * so the last FLOATING geometry in [previous] is carried over and only the
 * maximized flag is set. Unplaced windows have no position.
 */
fun WindowState.toGeometry(previous: WindowGeometry?): WindowGeometry {
    if (placement != WindowPlacement.Floating) {
        return (previous ?: DEFAULT_GEOMETRY).copy(maximized = true)
    }
    val absolute = position as? WindowPosition.Absolute
    val width = if (size.width.isSpecified) size.width.value.toInt() else DEFAULT_WINDOW_SIZE.width.value.toInt()
    val height = if (size.height.isSpecified) size.height.value.toInt() else DEFAULT_WINDOW_SIZE.height.value.toInt()
    return WindowGeometry(
        x = absolute?.x?.value?.toInt(),
        y = absolute?.y?.value?.toInt(),
        widthDp = width,
        heightDp = height,
        maximized = false,
    )
}

fun WindowGeometry.toPosition(): WindowPosition? {
    val px = x ?: return null
    val py = y ?: return null
    return WindowPosition.Absolute(px.dp, py.dp)
}

fun WindowGeometry.toSize(): DpSize = DpSize(widthDp.dp, heightDp.dp)
