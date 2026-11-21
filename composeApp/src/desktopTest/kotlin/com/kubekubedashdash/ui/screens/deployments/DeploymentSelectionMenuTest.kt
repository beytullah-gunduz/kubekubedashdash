package com.kubekubedashdash.ui.screens.deployments

import com.kubekubedashdash.ui.components.BulkVerb
import com.kubekubedashdash.ui.components.BulkVerbs
import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentSelectionMenuTest {
    @Test
    fun headerNamesTheCount() {
        assertEquals("3 Deployments selected", deploymentSelectionHeader(3))
    }

    @Test
    fun labelsCarryTheScopeAndDestructiveIsLast() {
        val labels = deploymentSelectionMenu(3) {}.map { it.label }
        assertEquals(
            listOf("Restart 3 Deployments…", "Scale 3 Deployments…", "Delete 3 Deployments…"),
            labels,
        )
    }

    @Test
    fun itemsOpenTheMatchingVerbInOrder() {
        val opened = mutableListOf<BulkVerb>()
        deploymentSelectionMenu(3) { opened += it }.forEach { it.onSelect() }
        assertEquals(listOf(BulkVerbs.Restart, BulkVerbs.Scale, BulkVerbs.Delete), opened)
    }
}
