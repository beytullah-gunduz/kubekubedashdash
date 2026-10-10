package com.kubekubedashdash.data.repository

import com.kubekubedashdash.util.DemoContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** The picker's favourite toggle is pure: the stored order is the order the clusters were starred. */
class ClusterFavouritesTest {

    @Test
    fun `starring an absent cluster appends it and keeps the order`() {
        assertEquals(
            listOf("example-dev", "example-prod"),
            toggledFavouriteClusters(listOf("example-dev"), "example-prod"),
        )
    }

    @Test
    fun `starring a present cluster removes it`() {
        assertEquals(
            listOf("example-prod"),
            toggledFavouriteClusters(listOf("example-dev", "example-prod"), "example-dev"),
        )
    }

    @Test
    fun `a minted demo label stars and unstars the one demo row`() {
        val minted = "demo-cluster (mock) #3"
        assertEquals(
            listOf(DemoContext.MOCK_CONTEXT_NAME),
            toggledFavouriteClusters(emptyList(), minted),
        )
        assertEquals(
            emptyList(),
            toggledFavouriteClusters(listOf(DemoContext.MOCK_CONTEXT_NAME), minted),
        )
    }

    @Test
    fun `duplicates in a hand-edited store are dropped`() {
        assertEquals(
            listOf("example-dev", "example-prod"),
            toggledFavouriteClusters(listOf("example-dev", "example-dev"), "example-prod"),
        )
    }
}
