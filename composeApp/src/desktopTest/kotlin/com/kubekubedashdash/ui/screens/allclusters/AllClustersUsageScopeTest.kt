package com.kubekubedashdash.ui.screens.allclusters

import com.kubekubedashdash.models.NamespaceScope
import com.kubekubedashdash.ui.screens.cluster.UsageScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the All Clusters usage header says about the namespaces behind its figures. */
class AllClustersUsageScopeTest {

    @Test
    fun `no scope note while every tab covers all namespaces`() {
        assertNull(allClustersUsageScope(emptyList()))
        assertNull(allClustersUsageScope(listOf(NamespaceScope.All, NamespaceScope.All)))
    }

    @Test
    fun `a lone scoped tab reads like the cluster overview`() {
        assertEquals(
            UsageScope.of(NamespaceScope.Only(setOf("ns-a"))),
            allClustersUsageScope(listOf(NamespaceScope.single("ns-a"))),
        )
    }

    @Test
    fun `a lone tab with several namespaces counts them`() {
        val scope = allClustersUsageScope(listOf(NamespaceScope.of(listOf("ns-a", "ns-b"))))
        assertEquals("Usage Statistics · 2 namespaces", scope?.title)
        assertEquals("These namespaces' usage and pods, against whole-cluster capacity", scope?.note)
    }

    @Test
    fun `mixed tabs say how many clusters are scoped`() {
        val one = allClustersUsageScope(listOf(NamespaceScope.single("ns-a"), NamespaceScope.All))
        assertEquals("Usage Statistics · namespace-scoped", one?.title)
        assertEquals("Pods and usage for 1 of 2 clusters follow a namespace selection (see its panel); capacity is whole-cluster", one?.note)

        assertEquals(
            "Pods and usage for 2 of 3 clusters follow a namespace selection (see their panels); capacity is whole-cluster",
            allClustersUsageScope(listOf(NamespaceScope.single("ns-a"), NamespaceScope.All, NamespaceScope.single("ns-b")))?.note,
        )
        assertEquals(
            "Pods and usage for all 2 clusters follow a namespace selection (see their panels); capacity is whole-cluster",
            allClustersUsageScope(listOf(NamespaceScope.single("ns-a"), NamespaceScope.single("ns-b")))?.note,
        )
    }

    @Test
    fun `a cluster panel's chip names one namespace or counts several`() {
        assertEquals("Namespace: ns-a", fleetNamespaceChipText(NamespaceScope.Only(setOf("ns-a"))))
        assertEquals("2 namespaces", fleetNamespaceChipText(NamespaceScope.Only(setOf("ns-a", "ns-b"))))
    }
}
