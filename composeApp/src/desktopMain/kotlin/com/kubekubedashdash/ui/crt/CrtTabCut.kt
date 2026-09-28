package com.kubekubedashdash.ui.crt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.ThemeManager

/** Retro tab switch (D23): counts tab cuts; the pager's [crtTabCut] aperture restarts on each. */
internal class CrtTabCut {
    internal var cuts by mutableIntStateOf(0)
}

@Composable
internal fun rememberCrtTabCut(): CrtTabCut = remember { CrtTabCut() }

/**
 * Moves to [page] for a tab click or an in-panel tab link. In Retro: an instant jump, and a new
 * [crtTabCut] aperture only when the page actually changes. In Default: the existing slide.
 */
internal suspend fun PagerState.goToTab(page: Int, cut: CrtTabCut) {
    if (ThemeManager.isRetro) {
        // Count the cut only once the jump has happened: scrollToPage can suspend, or throw
        // when a user drag holds the scroll mutex (review MINOR-2).
        val changed = currentPage != page
        scrollToPage(page)
        if (changed) cut.cuts++
    } else {
        animateScrollToPage(page)
    }
}

/**
 * Enter-only [CrtPanelTiming.TAB_CUT_MS] aperture on a panel's pager (D23), restarted by
 * [goToTab]. `remember(cuts)` recreates the progress at 0 in the same composition the count
 * changes, so the first frame of the new tab is drawn closed; the count starts at 0, so the
 * first composition never plays. Default: this modifier is returned unchanged.
 */
@Composable
internal fun Modifier.crtTabCut(cut: CrtTabCut): Modifier {
    val cuts = cut.cuts
    val reveal = remember(cuts) { Animatable(if (cuts == 0 || !ThemeManager.isRetro) 1f else 0f) }
    LaunchedEffect(reveal) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(CrtPanelTiming.TAB_CUT_MS, easing = LinearEasing))
    }
    return crtAperture(reveal)
}

@Composable
private fun Modifier.crtAperture(reveal: Animatable<Float, AnimationVector1D>): Modifier {
    if (!ThemeManager.isRetro) return this
    val look = crtLook(CrtScale.CUT, ThemeManager.isDarkTheme, KdPrimary)
    return this.drawWithContent {
        val r = reveal.value
        if (r >= 1f) {
            drawContent()
        } else {
            val f = crtFrameFor(r, entering = true, igniteFraction = 0f)
            drawCrtFrame(f.ignite, f.open, f.glow, look)
        }
    }
}

/**
 * Retro row-to-row cut in the detail pane (D25): an enter-only [CrtPanelTiming.TAB_CUT_MS]
 * aperture whenever [key], the identity of the resource shown, changes. Key on identity,
 * never on the object: live updates re-emit the same resource as a new object every tick,
 * and a re-click of the open row carries the same key, so neither replays. The first
 * composition never plays (an opening pane plays D21's tube instead); a new key inside a
 * running cut restarts it closed. Default: this modifier is returned unchanged.
 */
@Composable
internal fun Modifier.crtContentCut(key: Any?): Modifier {
    val firstUse = remember { BooleanArray(1) { true } }
    val reveal = remember(key) {
        val born = if (firstUse[0] || !ThemeManager.isRetro) 1f else 0f
        firstUse[0] = false
        Animatable(born)
    }
    LaunchedEffect(reveal) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(CrtPanelTiming.TAB_CUT_MS, easing = LinearEasing))
    }
    return crtAperture(reveal)
}
