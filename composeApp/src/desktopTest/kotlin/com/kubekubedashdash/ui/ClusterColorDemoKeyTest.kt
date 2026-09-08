package com.kubekubedashdash.ui

import com.kubekubedashdash.util.DemoContext
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The colour picker stores the demo cluster's override under the one row it
 * lists, "demo-cluster (mock)", while every open demo tab reports the label
 * the provider minted, "demo-cluster (mock) #N" (review follow-up F11). The
 * resolver folds a minted label to the demo key for both the hue and the
 * override lookup, so the demo cluster keeps one look across re-mints; a
 * real context is its own key, as before.
 */
class ClusterColorDemoKeyTest {

    private val bare = DemoContext.MOCK_CONTEXT_NAME
    private val minted = "demo-cluster (mock) #3"
    private val reminted = "demo-cluster (mock) #7"

    @Test
    fun `every minted demo label gets the demo row's hue`() {
        val demo = ClusterColor.fromContext(bare).hue
        assertEquals(demo, ClusterColor.fromContext(minted).hue, "a minted label must not hash to its own hue")
        assertEquals(demo, ClusterColor.fromContext(reminted).hue)
    }

    @Test
    fun `an override stored under the demo key applies to a minted label`() {
        val overrides = mapOf(bare to "#E53935")

        assertEquals(ClusterColor.parseHex("#E53935"), ClusterColor.effectiveColor(minted, overrides).override)
        assertEquals(ClusterColor.parseHex("#E53935"), ClusterColor.effectiveColor(reminted, overrides).override)
    }

    @Test
    fun `a real context keeps its own hue and reads only its own override`() {
        val ctx = "example-context"

        assertEquals((ctx.hashCode().absoluteValue % 360).toFloat(), ClusterColor.fromContext(ctx).hue)
        assertEquals(ClusterColor.parseHex("#1E88E5"), ClusterColor.effectiveColor(ctx, mapOf(ctx to "#1E88E5")).override)
        assertNull(ClusterColor.effectiveColor(ctx, mapOf(bare to "#E53935")).override, "the demo row's colour must not leak onto a real context")
        assertNotEquals(ClusterColor.fromContext(ctx).hue, ClusterColor.fromContext("other-context").hue)
    }
}
