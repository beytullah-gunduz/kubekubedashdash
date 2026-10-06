package com.kubekubedashdash.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.ThemeMode
import com.kubekubedashdash.ThemeStyle
import com.kubekubedashdash.drawKdDot
import com.kubekubedashdash.kdCornerRadius
import com.kubekubedashdash.kdCorners
import com.kubekubedashdash.util.SystemDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [kdCorners], [kdCornerRadius] and [drawKdDot]: the non-uniform, `drawRoundRect` and filled-dot
 * Retro tokens. Pure plus one offscreen draw. Every switch goes through `sync*`, never a `set*`, so
 * nothing here ever persists; the manager's prior mode and style are restored after each case and the
 * test runs only against the Gradle test-data store (harness copied from [RetroPanelChromeTest]).
 */
class RetroShapeTokensTest {

    private lateinit var originalStyle: ThemeStyle
    private lateinit var originalMode: ThemeMode
    private var originalDark = true

    @BeforeTest
    fun setUp() {
        assertTrue(
            SystemDirectories.dataDirectory.contains("test-data"),
            "refusing to run against a data directory that is not the Gradle test-data directory",
        )
        originalStyle = ThemeManager.style
        originalMode = ThemeManager.mode
        originalDark = ThemeManager.isDarkTheme
    }

    @AfterTest
    fun restore() {
        ThemeManager.syncStyleFromPreferences(originalStyle)
        // syncFromPreferences(SYSTEM) leaves the dark flag where the last explicit
        // mode put it, so restore the flag first through the matching explicit mode.
        ThemeManager.syncFromPreferences(if (originalDark) ThemeMode.DARK else ThemeMode.LIGHT)
        ThemeManager.syncFromPreferences(originalMode)
    }

    @Test
    fun `kdCorners keeps the per-corner radii under Default and squares them under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals(RoundedCornerShape(8.dp, 8.dp, 0.dp, 0.dp), kdCorners(topStart = 8.dp, topEnd = 8.dp))

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(RoundedCornerShape(0.dp), kdCorners(topStart = 8.dp, topEnd = 8.dp))
    }

    @Test
    fun `kdCornerRadius is the radius in pixels under Default and zero under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        assertEquals(CornerRadius(2f), Density(2f).kdCornerRadius(1.dp))

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        assertEquals(CornerRadius.Zero, Density(2f).kdCornerRadius(1.dp))
    }

    @Test
    fun `drawKdDot draws a circle under Default and a square under Retro`() {
        ThemeManager.syncStyleFromPreferences(ThemeStyle.DEFAULT)
        val circle = drawDot()
        // The corner of the 10 x 10 box lies outside the radius-5 circle; the centre is inside both.
        assertTrue(circle[5, 5].alpha < 0.1f, "Default corner pixel should be empty, got ${circle[5, 5]}")
        assertEquals(Color.Red, circle[10, 10])

        ThemeManager.syncStyleFromPreferences(ThemeStyle.RETRO)
        val square = drawDot()
        assertEquals(Color.Red, square[5, 5])
        assertEquals(Color.Red, square[10, 10])
    }

    private fun drawDot() = ImageBitmap(20, 20).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(20f, 20f)) {
            drawKdDot(Color.Red, 5f, Offset(10f, 10f))
        }
    }.toPixelMap()
}
