package com.kubekubedashdash.ui.components

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.NamespaceScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a cluster-wide 403 offers "Choose a namespace": a list that follows the selector and watches every
 * namespace. Two or more selected names are watched cluster-wide as well, so they get the hint too.
 */
class NamespaceAccessHintTest {

    @Test
    fun `All Namespaces on a namespaced list gets the hint`() {
        assertTrue(namespaceAccessHintApplies(Screen.Main.Pods(), NamespaceScope.All))
        assertTrue(namespaceAccessHintApplies(Screen.Main.ClusterOverview, NamespaceScope.All))
    }

    @Test
    fun `a single namespace does not`() {
        assertFalse(namespaceAccessHintApplies(Screen.Main.Pods(), NamespaceScope.single("team-a")))
    }

    @Test
    fun `a selection of two or more namespaces is listed cluster-wide and gets the hint`() {
        assertTrue(namespaceAccessHintApplies(Screen.Main.Pods(), NamespaceScope.of(listOf("team-a", "team-b"))))
    }

    @Test
    fun `a screen that does not follow the selector never gets the hint`() {
        assertFalse(namespaceAccessHintApplies(Screen.Main.Nodes(), NamespaceScope.All))
        assertFalse(namespaceAccessHintApplies(Screen.Main.Namespaces, NamespaceScope.of(listOf("team-a", "team-b"))))
    }
}
