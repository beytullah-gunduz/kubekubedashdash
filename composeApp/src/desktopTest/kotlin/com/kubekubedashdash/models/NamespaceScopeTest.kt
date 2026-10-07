package com.kubekubedashdash.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamespaceScopeTest {

    @Test
    fun `of normalises blank and duplicate names`() {
        assertEquals(NamespaceScope.All, NamespaceScope.of(emptyList()))
        assertEquals(NamespaceScope.All, NamespaceScope.of(listOf("", " ")))
        assertEquals(NamespaceScope.Only(setOf("ns-a")), NamespaceScope.of(listOf("ns-a", "ns-a")))
        assertEquals(NamespaceScope.All, NamespaceScope.single(""))
    }

    @Test
    fun `serverNamespace is set only for exactly one namespace`() {
        assertNull(NamespaceScope.All.serverNamespace)
        assertEquals("ns-a", NamespaceScope.single("ns-a").serverNamespace)
        assertNull(NamespaceScope.of(listOf("ns-a", "ns-b")).serverNamespace)
    }

    @Test
    fun `contains matches the selected namespaces and treats null as All only`() {
        assertTrue(NamespaceScope.All.contains(null))
        assertFalse(NamespaceScope.single("ns-a").contains(null))
        assertFalse(NamespaceScope.single("ns-a").contains("ns-b"))
        assertTrue(NamespaceScope.of(listOf("ns-a", "ns-b")).contains("ns-b"))
    }

    @Test
    fun `toggled adds, removes and collapses to All`() {
        assertEquals(NamespaceScope.single("ns-a"), NamespaceScope.All.toggled("ns-a"))
        assertEquals(NamespaceScope.of(listOf("ns-a", "ns-b")), NamespaceScope.single("ns-a").toggled("ns-b"))
        assertEquals(NamespaceScope.single("ns-a"), NamespaceScope.of(listOf("ns-a", "ns-b")).toggled("ns-b"))
        assertEquals(NamespaceScope.All, NamespaceScope.single("ns-a").toggled("ns-a"))
    }

    @Test
    fun `an empty Only is rejected`() {
        assertFailsWith<IllegalArgumentException> { NamespaceScope.Only(emptySet()) }
    }

    @Test
    fun `sortedNames lists the names in display order`() {
        val scope = NamespaceScope.of(listOf("ns-b", "ns-a")) as NamespaceScope.Only

        assertEquals(listOf("ns-a", "ns-b"), scope.sortedNames)
    }
}
