package com.kubekubedashdash.ui.screens.viewmodel

import com.kubekubedashdash.models.NamespaceScope
import kotlin.test.Test
import kotlin.test.assertEquals

class InitialNamespaceTest {

    @Test
    fun `restore wins over default`() {
        assertEquals(NamespaceScope.single("ns-b"), initialNamespaceScope(NamespaceScope.single("ns-b"), "ns-a"))
    }

    @Test
    fun `a restored multi-selection wins over default`() {
        val restored = NamespaceScope.of(listOf("ns-b", "ns-c"))
        assertEquals(restored, initialNamespaceScope(restored, "ns-a"))
    }

    @Test
    fun `default wins when there is no restore`() {
        assertEquals(NamespaceScope.single("ns-a"), initialNamespaceScope(null, "ns-a"))
    }

    @Test
    fun `blank default counts as absent`() {
        assertEquals(NamespaceScope.All, initialNamespaceScope(null, ""))
        assertEquals(NamespaceScope.All, initialNamespaceScope(null, "   "))
    }

    @Test
    fun `both absent falls back to All Namespaces`() {
        assertEquals(NamespaceScope.All, initialNamespaceScope(null, null))
    }

    @Test
    fun `a restored All Namespaces still wins over a default`() {
        assertEquals(NamespaceScope.All, initialNamespaceScope(NamespaceScope.All, "ns-a"))
    }
}
