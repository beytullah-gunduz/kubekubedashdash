package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The width rules that keep the cluster overview readable in a narrow window. */
class OverviewLayoutTest {

    @Test
    fun `summaryCardsPerRow keeps five cards on one row only when all fit at the minimum width`() {
        // 5 x 180 + 4 x 16 = 964
        assertEquals(5, summaryCardsPerRow(964.dp, 5))
        assertEquals(3, summaryCardsPerRow(963.dp, 5))
    }

    @Test
    fun `summaryCardsPerRow evens out the rows instead of leaving a lone card`() {
        // 4 x 180 + 3 x 16 = 768 fits four, which would be 4 + 1
        assertEquals(3, summaryCardsPerRow(800.dp, 5))
        // 3 x 180 + 2 x 16 = 572 fits three: 3 + 2
        assertEquals(3, summaryCardsPerRow(572.dp, 5))
        // 2 x 180 + 16 = 376 fits two: 2 + 2 + 1
        assertEquals(2, summaryCardsPerRow(571.dp, 5))
        assertEquals(2, summaryCardsPerRow(376.dp, 5))
        assertEquals(1, summaryCardsPerRow(375.dp, 5))
    }

    @Test
    fun `summaryCardsPerRow never returns zero`() {
        assertEquals(1, summaryCardsPerRow(0.dp, 5))
        assertEquals(1, summaryCardsPerRow(1000.dp, 0))
    }

    @Test
    fun `usage sections share a row from the split width`() {
        assertTrue(usageSectionsSideBySide(600.dp))
        assertFalse(usageSectionsSideBySide(599.dp))
    }

    @Test
    fun `recent cards drop the namespace column when three columns leave them too narrow`() {
        // The default 1440 dp window with the sidebar open leaves about 1100 dp: three 356 dp cards.
        assertFalse(recentCardShowsNamespace(recentCardWidth(1100.dp)))
        // (1412 - 32) / 3 = 460
        assertTrue(recentCardShowsNamespace(recentCardWidth(1412.dp)))
        assertFalse(recentCardShowsNamespace(recentCardWidth(1411.dp)))
    }

    @Test
    fun `a single-column recent card is as wide as the space`() {
        assertEquals(1099.dp, recentCardWidth(1099.dp))
        assertTrue(recentCardShowsNamespace(recentCardWidth(1099.dp)))
        assertFalse(recentCardShowsNamespace(recentCardWidth(459.dp)))
    }
}
