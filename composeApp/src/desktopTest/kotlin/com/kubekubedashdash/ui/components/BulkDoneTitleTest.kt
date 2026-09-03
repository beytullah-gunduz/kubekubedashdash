package com.kubekubedashdash.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the completion-toast title for clean bulk runs. */
class BulkDoneTitleTest {

    @Test
    fun `plural kinds keep the screen's casing after the count`() {
        assertEquals("Evicted 3 Pods", bulkDoneTitle(BulkVerbs.Evict, 3, "Pod", "Pods"))
    }

    @Test
    fun `a single item uses the singular kind`() {
        assertEquals("Deleted 1 Pod", bulkDoneTitle(BulkVerbs.Delete, 1, "Pod", "Pods"))
    }

    @Test
    fun `generic kinds keep their plural form`() {
        assertEquals("Deleted 2 ConfigMaps", bulkDoneTitle(BulkVerbs.Delete, 2, "ConfigMap", "ConfigMaps"))
        assertEquals("Cordoned 2 Nodes", bulkDoneTitle(BulkVerbs.Cordon, 2, "Node", "Nodes"))
        assertEquals("Rollout restart started for 3 Deployments", bulkDoneTitle(BulkVerbs.Restart, 3, "Deployment", "Deployments"))
    }
}
