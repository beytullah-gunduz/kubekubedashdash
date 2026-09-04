package com.kubekubedashdash.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import kotlinx.coroutines.launch

fun Modifier.kdFocusRing(): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = it.isFocused }
        .then(if (isFocused) border(2.dp, KdPrimary, RoundedCornerShape(6.dp)) else Modifier)
}

/**
 * The app-wide indication for bare `Modifier.clickable`: a 2 dp KdPrimary
 * rounded border while focused, nothing otherwise. Material 3 components do
 * NOT read LocalIndication — Surface and IconButton pass `indication =
 * ripple()` explicitly — so they are covered by the theme configuration
 * (`LocalRippleThemeConfiguration`) installed alongside this in `Theme.kt`.
 *
 * This replaces the framework default (`DefaultDebugIndication`), which
 * draws a 0.3f-alpha overlay on press and 0.1f on hover/focus. Because this
 * indication is focus-only, bare clickables that implement no press feedback
 * of their own lose it on mouse click. Hover is already handled per-site
 * across this codebase (`hovered` flags in `ResourceTable`, `ClusterChip`,
 * …), so only press feedback is genuinely given up — accepted as the cost of
 * a keyboard focus ring that is actually visible.
 */
object KdFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusIndicationNode(interactionSource)

    override fun hashCode(): Int = -1

    override fun equals(other: Any?): Boolean = other === this
}

private class FocusIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(),
    DrawModifierNode {
    private var isFocused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> {
                        isFocused = true
                        invalidateDraw()
                    }

                    is FocusInteraction.Unfocus -> {
                        isFocused = false
                        invalidateDraw()
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (isFocused) {
            val strokeWidth = 2.dp.toPx()
            drawRoundRect(
                color = KdPrimary,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
