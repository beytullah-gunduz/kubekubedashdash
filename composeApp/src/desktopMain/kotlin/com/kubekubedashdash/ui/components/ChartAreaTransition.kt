package com.kubekubedashdash.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager
import com.kubekubedashdash.screenshots.ScreenshotHooks
import com.kubekubedashdash.ui.crt.CrtScale
import com.kubekubedashdash.ui.crt.crtFrameFor
import com.kubekubedashdash.ui.crt.crtLook
import com.kubekubedashdash.ui.crt.drawCrtFrame

private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 220
private const val POWER_OFF_MS = 200
private const val POWER_ON_MS = 370

/**
 * True for content that a Retro [ChartAreaTransition] has just powered on (as opposed to content
 * composed with the area). A [TimeSeriesChart] born inside such content skips its own CRT power-on
 * the first time its data lands and only traces the beam: the area has already lit the screen.
 */
internal val LocalChartAreaPoweredOn = staticCompositionLocalOf { false }

/**
 * Swaps a chart area for its fallback (and back) when [contentKey] of [targetState] changes:
 * Default fades through with the height animated; Retro powers the old content off like a CRT,
 * then powers the new one on. A new [targetState] with the same key updates the content in place,
 * with no animation, so a periodic refresh never animates and the charts keep their state.
 * Everything [content] renders must come from the state it is handed, never from outer
 * variables: the outgoing content is still drawn after the outer state has moved on.
 */
@Composable
internal fun <T> ChartAreaTransition(
    targetState: T,
    contentKey: (T) -> Any?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    if (!ThemeManager.isRetro) {
        AnimatedContent(
            targetState = targetState,
            // Full width in both branches: a one-line note would otherwise shrink the area, and a swap would wipe sideways.
            modifier = modifier.fillMaxWidth(),
            transitionSpec = {
                (fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS)) togetherWith fadeOut(tween(FADE_OUT_MS)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(FADE_OUT_MS + FADE_IN_MS) })
            },
            contentAlignment = Alignment.TopStart,
            contentKey = contentKey,
            label = "chartArea",
        ) { state -> content(state) }
        return
    }
    val latest = rememberUpdatedState(targetState)
    // The content on screen while a swap plays. Written only from the effect below.
    var shown by remember { mutableStateOf(targetState) }
    // The key of the content the last swap powered on; nothing yet for the content composed with the area.
    var poweredOnKey by remember { mutableStateOf<Any?>(null) }
    var entering by remember { mutableStateOf(true) }
    val progress = remember { Animatable(1f) } // 1 = fully on
    val targetKey = contentKey(targetState)
    // Same key: the new state renders in place. Other key: the old content stays until its power-off is done.
    val displayed = if (targetKey == contentKey(shown)) targetState else shown
    LaunchedEffect(targetKey) {
        val scale = ScreenshotHooks.crtTimeScale.value
        if (targetKey == contentKey(shown)) {
            // First composition, or a swap interrupted back to the shown content: finish powering it on.
            if (!entering || progress.value < 1f) {
                entering = true
                progress.animateTo(1f, tween(POWER_ON_MS * scale, easing = LinearEasing))
            }
            return@LaunchedEffect
        }
        entering = false
        progress.animateTo(0f, tween(POWER_OFF_MS * scale, easing = LinearEasing))
        shown = latest.value // not the captured parameter: a same-key refresh may have landed meanwhile
        poweredOnKey = contentKey(shown)
        entering = true
        progress.animateTo(1f, tween(POWER_ON_MS * scale, easing = LinearEasing))
        // Only content born during the power-on is "powered on": a chart born later in it plays its own reveal.
        poweredOnKey = null
    }
    Box(
        modifier.fillMaxWidth().drawWithContent {
            val p = progress.value
            if (entering && p >= 1f) {
                drawContent()
            } else {
                val (ignite, open, glow) = if (entering) {
                    crtFrameFor(p, entering = true, igniteFraction = 120f / 370f)
                } else {
                    crtFrameFor(p, entering = false, igniteFraction = 0f)
                }
                drawCrtFrame(ignite, open, glow, crtLook(CrtScale.CARD, ThemeManager.isDarkTheme, KdPrimary))
            }
        },
    ) {
        val key = contentKey(displayed)
        key(key) {
            CompositionLocalProvider(LocalChartAreaPoweredOn provides (poweredOnKey == key)) { content(displayed) }
        }
    }
}
