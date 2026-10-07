package com.kubekubedashdash.ui.screens.cluster

import com.kubekubedashdash.models.NamespaceScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Overview's scope chip and note for a namespace selection. */
class NamespaceScopeNoteTextTest {

    @Test
    fun `one namespace names it and says the figures cover that namespace`() {
        assertEquals(
            "Namespace: ns-a" to
                "Pods, deployments, services, usage and events cover this namespace; nodes and capacity cover the whole cluster.",
            namespaceScopeNoteText(NamespaceScope.Only(setOf("ns-a"))),
        )
    }

    @Test
    fun `several namespaces are counted and listed in name order`() {
        assertEquals(
            "2 namespaces" to
                "Pods, deployments, services, usage and events cover these namespaces: ns-a, ns-b; nodes and capacity cover the whole cluster.",
            namespaceScopeNoteText(NamespaceScope.Only(setOf("ns-b", "ns-a"))),
        )
    }

    @Test
    fun `a long selection lists five names and counts the rest`() {
        assertEquals(
            "7 namespaces" to
                "Pods, deployments, services, usage and events cover these namespaces: ns-a, ns-b, ns-c, ns-d, ns-e and 2 more; nodes and capacity cover the whole cluster.",
            namespaceScopeNoteText(NamespaceScope.Only(setOf("ns-g", "ns-f", "ns-e", "ns-d", "ns-c", "ns-b", "ns-a"))),
        )
    }
}
