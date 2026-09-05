package com.kubekubedashdash.ui.screens.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class InitialNamespaceTest {

    @Test
    fun `restore wins over default`() {
        assertEquals("restored-ns", initialNamespace("restored-ns", "default-ns"))
    }

    @Test
    fun `default wins when there is no restore`() {
        assertEquals("default-ns", initialNamespace(null, "default-ns"))
    }

    @Test
    fun `blank default counts as absent`() {
        assertEquals("All Namespaces", initialNamespace(null, ""))
        assertEquals("All Namespaces", initialNamespace(null, "   "))
    }

    @Test
    fun `both absent falls back to All Namespaces`() {
        assertEquals("All Namespaces", initialNamespace(null, null))
    }

    @Test
    fun `a restored All Namespaces still wins over a default`() {
        assertEquals("All Namespaces", initialNamespace("All Namespaces", "default-ns"))
    }
}
