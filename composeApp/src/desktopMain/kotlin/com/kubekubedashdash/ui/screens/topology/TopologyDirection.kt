package com.kubekubedashdash.ui.screens.topology

// Rotation cycle. Right-rotate steps through the list in order; left-rotate steps
// backward. The order matches what the user sees on screen as they rotate
// clockwise: horizontal flow → vertical flow → horizontal mirror → vertical mirror.
internal enum class TopologyDirection {
    LEFT_TO_RIGHT,
    TOP_TO_BOTTOM,
    RIGHT_TO_LEFT,
    BOTTOM_TO_TOP,
    ;

    val isHorizontal: Boolean get() = this == LEFT_TO_RIGHT || this == RIGHT_TO_LEFT
    val isReversed: Boolean get() = this == RIGHT_TO_LEFT || this == BOTTOM_TO_TOP

    fun rotateRight(): TopologyDirection = entries[(ordinal + 1) % entries.size]
    fun rotateLeft(): TopologyDirection = entries[(ordinal + entries.size - 1) % entries.size]
}
