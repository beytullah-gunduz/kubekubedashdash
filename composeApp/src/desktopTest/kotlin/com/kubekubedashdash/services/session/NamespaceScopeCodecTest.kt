package com.kubekubedashdash.services.session

import com.kubekubedashdash.models.NamespaceScope
import kotlin.test.Test
import kotlin.test.assertEquals

class NamespaceScopeCodecTest {

    @Test
    fun `All encodes as the All Namespaces marker with no list`() {
        assertEquals("All Namespaces" to null, NamespaceScopeCodec.encode(NamespaceScope.All))
    }

    @Test
    fun `one namespace encodes as its name with no list`() {
        assertEquals("ns-a" to null, NamespaceScopeCodec.encode(NamespaceScope.single("ns-a")))
    }

    @Test
    fun `several namespaces encode as All Namespaces plus the sorted list`() {
        assertEquals(
            "All Namespaces" to listOf("ns-a", "ns-b"),
            NamespaceScopeCodec.encode(NamespaceScope.of(listOf("ns-b", "ns-a"))),
        )
    }

    @Test
    fun `the All marker and a blank name decode as All`() {
        assertEquals(NamespaceScope.All, NamespaceScopeCodec.decode("All Namespaces", null))
        assertEquals(NamespaceScope.All, NamespaceScopeCodec.decode("", null))
    }

    @Test
    fun `a name decodes as that one namespace`() {
        assertEquals(NamespaceScope.single("ns-a"), NamespaceScopeCodec.decode("ns-a", null))
    }

    @Test
    fun `a list wins over the marker`() {
        assertEquals(
            NamespaceScope.of(listOf("ns-a", "ns-b")),
            NamespaceScopeCodec.decode("All Namespaces", listOf("ns-a", "ns-b")),
        )
    }

    @Test
    fun `an empty or blank list falls back to the name`() {
        assertEquals(NamespaceScope.single("ns-a"), NamespaceScopeCodec.decode("ns-a", emptyList()))
        assertEquals(NamespaceScope.All, NamespaceScopeCodec.decode("All Namespaces", listOf(" ")))
    }

    @Test
    fun `every scope survives a round trip`() {
        listOf(
            NamespaceScope.All,
            NamespaceScope.single("ns-a"),
            NamespaceScope.of(listOf("ns-a", "ns-b")),
        ).forEach { scope ->
            val (namespace, namespaces) = NamespaceScopeCodec.encode(scope)
            assertEquals(scope, NamespaceScopeCodec.decode(namespace, namespaces))
        }
    }
}
