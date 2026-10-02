package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The narrowest a summary card gets before the cards wrap onto another row: the icon chrome
 * (90 dp) plus room for "Deployments" or a "38 failed" badge in either style.
 */
internal val SUMMARY_CARD_MIN_WIDTH = 180.dp
internal val SUMMARY_CARD_GAP = 16.dp

/**
 * How many of [count] summary cards share a row in [available] width: as many as fit at
 * [SUMMARY_CARD_MIN_WIDTH], then evened out over the rows that takes, so five cards that fit four
 * to a row go 3 + 2 rather than 4 + 1.
 */
internal fun summaryCardsPerRow(available: Dp, count: Int): Int {
    if (count <= 0) return 1
    val fit = ((available + SUMMARY_CARD_GAP) / (SUMMARY_CARD_MIN_WIDTH + SUMMARY_CARD_GAP)).toInt().coerceIn(1, count)
    val rows = (count + fit - 1) / fit
    return (count + rows - 1) / rows
}

/** Narrower than this, Pod Status and Top nodes stack instead of sharing a row. */
internal val USAGE_SPLIT_MIN_WIDTH = 600.dp

internal fun usageSectionsSideBySide(available: Dp): Boolean = available >= USAGE_SPLIT_MIN_WIDTH

internal val RECENT_THREE_COLUMN_THRESHOLD = 1100.dp
internal val RECENT_CARD_GAP = 16.dp

/**
 * A recent pods or events card narrower than this drops its 120 dp namespace column. With it, three
 * cards in a 1440 dp window left a pod name about five characters ("sched…").
 */
internal val RECENT_NAMESPACE_MIN_CARD_WIDTH = 460.dp

/** The width of one recent-activity card when [available] holds them in three columns or one. */
internal fun recentCardWidth(available: Dp): Dp = if (available >= RECENT_THREE_COLUMN_THRESHOLD) (available - RECENT_CARD_GAP * 2) / 3 else available

internal fun recentCardShowsNamespace(cardWidth: Dp): Boolean = cardWidth >= RECENT_NAMESPACE_MIN_CARD_WIDTH
