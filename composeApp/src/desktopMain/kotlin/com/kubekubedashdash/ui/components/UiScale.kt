package com.kubekubedashdash.ui.components

import kotlin.math.abs

/**
 * The zoom levels the keyboard and the Settings control both offer.
 *
 * Capped at 150 deliberately. The list/detail scaffold picks its pane count
 * from the window size class, which `currentWindowAdaptiveInfo` derives in dp
 * through the *current* density — so zoom shrinks the window as far as that
 * calculation is concerned. On a 1440 dp window, 200 % reports 720 dp, which
 * falls below the two-partition threshold and unmounts the sidebar with no
 * way back except undoing the zoom. 150 % reports 960 dp and stays safe.
 * Going higher needs the directive computed from [LocalSystemDensity]
 * instead, which is a change to the scaffold, not to this list.
 */
val UiScaleSteps = listOf(80, 100, 125, 150)

/**
 * [percent] snapped to the nearest allowed step. A non-positive [percent]
 * cannot have come from [UiScaleSteps] itself — it is treated as an unknown /
 * corrupted value, e.g. from a hand-edited preferences store — and maps to
 * 100 rather than to whichever step happens to be numerically closest.
 */
fun clampUiScale(percent: Int): Int {
    if (percent <= 0) return 100
    return UiScaleSteps.minBy { abs(it - percent) }
}

/** The next step above/below [percent]; unchanged at the ends of the range. */
fun stepUiScale(percent: Int, up: Boolean): Int {
    val current = clampUiScale(percent)
    val index = UiScaleSteps.indexOf(current)
    val nextIndex = if (up) index + 1 else index - 1
    return UiScaleSteps.getOrNull(nextIndex) ?: current
}
