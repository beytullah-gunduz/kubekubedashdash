package com.kubekubedashdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.extension_filled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every icon in the CRD table, and the fallback, parses and draws ink in a 24 px box at 1x density. The
 * Material Symbols paths use the compact syntax (no commas, implicit separators) that no other drawable in
 * the app uses, so a parse failure shows up here as an empty cell.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalTestApi::class)
class CrdIconRenderTest {
    private val icons: List<DrawableResource> = CrdIconRules.map { it.icon }.distinct() + Res.drawable.extension_filled

    @Test
    fun `every CRD icon draws ink in a 24 px box`() = runSkikoComposeUiTest(
        size = Size(24f * icons.size, 24f),
        density = Density(1f),
    ) {
        setContent {
            Row(Modifier.background(Color.White)) {
                icons.forEach { icon ->
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.Red)
                }
            }
        }
        waitForIdle()
        val pixels = captureToImage().toPixelMap()
        icons.forEachIndexed { i, icon ->
            var ink = 0
            for (x in i * 24 until (i + 1) * 24) {
                for (y in 0 until 24) {
                    val c = pixels[x, y]
                    if (c.red > 0.5f && c.green < 0.5f && c.blue < 0.5f) ink++
                }
            }
            val name = CrdIconRules.firstOrNull { it.icon == icon }?.groups?.first() ?: "fallback"
            assertTrue(ink in 40..500, "icon #$i ($name) drew $ink red pixels of 576; expected 40..500")
        }
    }
}
