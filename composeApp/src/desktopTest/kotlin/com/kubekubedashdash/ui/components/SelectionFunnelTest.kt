package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionFunnelTest {

    @Test
    fun `set intersects with the visible set`() {
        val funnel = SelectionFunnel()
        funnel.setVisible(setOf("a", "b"))
        funnel.set(setOf("a", "b", "c"))
        assertEquals(setOf("a", "b"), funnel.selected.value)
    }

    @Test
    fun `shrinking the visible set prunes the selection`() {
        val funnel = SelectionFunnel()
        funnel.setVisible(setOf("a", "b"))
        funnel.set(setOf("a", "b"))
        funnel.setVisible(setOf("a"))
        assertEquals(setOf("a"), funnel.selected.value)
    }

    @Test
    fun `set cannot resurrect a hidden id`() {
        val funnel = SelectionFunnel()
        funnel.setVisible(setOf("a"))
        funnel.set(setOf("a", "b"))
        assertEquals(setOf("a"), funnel.selected.value)
    }

    @Test
    fun `reset clears both the selection and the visible set`() {
        val funnel = SelectionFunnel()
        funnel.setVisible(setOf("a", "b"))
        funnel.set(setOf("a", "b"))
        assertTrue(funnel.selected.value.isNotEmpty())

        funnel.reset()
        funnel.set(setOf("a"))
        assertTrue(funnel.selected.value.isEmpty())
    }
}
