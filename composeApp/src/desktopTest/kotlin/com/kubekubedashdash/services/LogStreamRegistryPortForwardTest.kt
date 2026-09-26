package com.kubekubedashdash.services

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogStreamRegistryPortForwardTest {

    @AfterTest
    fun cleanup() {
        LogStreamRegistry.clearAll()
    }

    @Test
    fun `opening twice leaves exactly one tab, focused`() {
        LogStreamRegistry.openOrFocusPortForwards()
        LogStreamRegistry.openOrFocusPortForwards()

        val key = ActivePortForwards.PORT_FORWARDS_KEY
        assertEquals(1, LogStreamRegistry.tabs.value.size)
        assertEquals(setOf(key), LogStreamRegistry.tabs.value.keys)
        assertEquals(key, LogStreamRegistry.focusedKey.value)
    }
}
