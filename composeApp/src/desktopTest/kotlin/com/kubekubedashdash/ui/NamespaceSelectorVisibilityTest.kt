package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which screens show the header's namespace selector. The selection applies
 * to every screen of the session whether or not the selector is on screen, so
 * hiding it where the data follows it leaves a filter the user cannot see or
 * change — shipped twice, on Topology (047a30b) and on the Overview.
 */
class NamespaceSelectorVisibilityTest {

    @Test
    fun `screens that read namespace-scoped data show the selector`() {
        assertTrue(Screen.Main.ClusterOverview.showsNamespaceSelector())
        assertTrue(Screen.Main.ClusterTopology.showsNamespaceSelector())
        assertTrue(Screen.Main.Pods().showsNamespaceSelector())
    }

    @Test
    fun `cluster-scoped screens hide it`() {
        assertFalse(Screen.Main.Nodes().showsNamespaceSelector())
        assertFalse(Screen.Main.Namespaces.showsNamespaceSelector())
        assertFalse(Screen.Main.PersistentVolumes.showsNamespaceSelector())
    }

    @Test
    fun `custom resources follow their own scope`() {
        fun crd(namespaced: Boolean) = Screen.Main.CustomResource("example.com", "v1", "Widget", "widgets", namespaced)
        assertTrue(crd(namespaced = true).showsNamespaceSelector())
        assertFalse(crd(namespaced = false).showsNamespaceSelector())
    }
}
