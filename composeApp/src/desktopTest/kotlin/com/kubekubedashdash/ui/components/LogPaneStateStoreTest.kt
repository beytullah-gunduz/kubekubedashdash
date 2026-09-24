package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogPaneStateStoreTest {

    @Test
    fun `same key returns the same state`() {
        val store = LogPaneStateStore()
        assertSame(store.stateFor("a"), store.stateFor("a"))
    }

    @Test
    fun `different keys get independent state`() {
        val store = LogPaneStateStore()
        store.stateFor("a").filterText = "x"
        assertEquals("", store.stateFor("b").filterText)
    }

    @Test
    fun `a new state follows and has nothing set`() {
        val state = LogPaneStateStore().stateFor("a")
        assertTrue(state.follow)
        assertEquals("", state.filterText)
        assertFalse(state.useRegex)
        assertFalse(state.caseSensitive)
        assertFalse(state.wrap)
        assertEquals(emptySet(), state.mutedPods)
        assertEquals(0, state.scrollIndex)
        assertEquals(0, state.scrollOffset)
    }

    @Test
    fun `retainOnly drops other keys and a dropped key starts fresh`() {
        val store = LogPaneStateStore()
        store.stateFor("a").filterText = "x"
        store.stateFor("b").filterText = "y"
        store.retainOnly(setOf("a"))
        assertEquals(1, store.size)
        assertEquals("x", store.stateFor("a").filterText)
        assertEquals("", store.stateFor("b").filterText)
    }
}
