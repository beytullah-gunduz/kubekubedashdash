package com.kubekubedashdash.util

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.kubekubedashdash.model.WindowGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowGeometryConversionsTest {
    @Test
    fun toPositionReturnsAbsolutePosition() {
        val geometry = WindowGeometry(x = 10, y = 20, widthDp = 800, heightDp = 600)
        assertEquals(WindowPosition.Absolute(10.dp, 20.dp), geometry.toPosition())
    }

    @Test
    fun toPositionIsNullWhenPositionless() {
        val geometry = WindowGeometry(widthDp = 800, heightDp = 600)
        assertNull(geometry.toPosition())
    }

    @Test
    fun toSizeReturnsDpSize() {
        val geometry = WindowGeometry(widthDp = 800, heightDp = 600)
        assertEquals(DpSize(800.dp, 600.dp), geometry.toSize())
    }

    @Test
    fun toGeometryKeepsPreviousFloatingBoundsWhileMaximized() {
        val state = WindowState(placement = WindowPlacement.Maximized, size = DpSize(1920.dp, 1080.dp))
        val previous = WindowGeometry(x = 1, y = 2, widthDp = 800, heightDp = 600)
        assertEquals(previous.copy(maximized = true), state.toGeometry(previous))
    }

    @Test
    fun toGeometryReadsFloatingPositionAndSize() {
        val state = WindowState(
            placement = WindowPlacement.Floating,
            position = WindowPosition.Absolute(5.dp, 6.dp),
            size = DpSize(800.dp, 600.dp),
        )
        assertEquals(
            WindowGeometry(x = 5, y = 6, widthDp = 800, heightDp = 600, maximized = false),
            state.toGeometry(null),
        )
    }
}
