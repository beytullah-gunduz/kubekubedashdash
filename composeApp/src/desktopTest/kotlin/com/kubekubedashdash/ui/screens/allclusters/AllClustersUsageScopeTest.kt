package com.kubekubedashdash.ui.screens.allclusters

import com.kubekubedashdash.ui.screens.cluster.UsageScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the All Clusters usage header says about the namespaces behind its figures. */
class AllClustersUsageScopeTest {

    @Test
    fun `no scope note while every tab covers all namespaces`() {
        assertNull(allClustersUsageScope(emptyList()))
        assertNull(allClustersUsageScope(listOf(null, null)))
    }

    @Test
    fun `a lone scoped tab reads like the cluster overview`() {
        assertEquals(UsageScope.namespace("ns-a"), allClustersUsageScope(listOf("ns-a")))
    }

    @Test
    fun `mixed tabs say how many clusters are scoped`() {
        val one = allClustersUsageScope(listOf("ns-a", null))
        assertEquals("Usage Statistics · namespace-scoped", one?.title)
        assertEquals("Pods and usage for 1 of 2 clusters cover one namespace (see its card); capacity is whole-cluster", one?.note)

        assertEquals(
            "Pods and usage for 2 of 3 clusters cover one namespace each (see their cards); capacity is whole-cluster",
            allClustersUsageScope(listOf("ns-a", null, "ns-b"))?.note,
        )
        assertEquals(
            "Pods and usage for all 2 clusters cover one namespace each (see their cards); capacity is whole-cluster",
            allClustersUsageScope(listOf("ns-a", "ns-b"))?.note,
        )
    }
}
