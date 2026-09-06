package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.models.ResourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A restored CustomResource tab whose CRD is gone used to park on the
 * Connecting spinner forever: the router could not tell "the CRD list has
 * not synced yet" from "the list synced and the CRD is not in it". The
 * decision is now a pure function of the CRD list state and the target.
 */
class CrdRouteTest {

    private val target = Screen.Main.CustomResource(
        group = "example.io",
        version = "v1",
        kind = "Widget",
        plural = "widgets",
        namespaced = true,
    )

    private fun crd(group: String, kind: String) = CrdInfo(
        group = group,
        version = "v1",
        kind = kind,
        plural = kind.lowercase() + "s",
        singular = kind.lowercase(),
        shortNames = emptyList(),
        categories = emptyList(),
        scope = CrdScope.NAMESPACED,
        columns = emptyList(),
    )

    @Test
    fun `an unsynced list keeps loading`() {
        assertEquals(CrdRoute.Loading, crdRoute(ResourceState.Loading, target))
    }

    @Test
    fun `a listed CRD is found by group and kind`() {
        val widget = crd("example.io", "Widget")
        val state = ResourceState.Success(listOf(crd("other.io", "Widget"), widget))
        assertEquals(CrdRoute.Found(widget), crdRoute(state, target))
    }

    @Test
    fun `a CRD absent from the synced list is missing, not loading`() {
        val route = crdRoute(ResourceState.Success(listOf(crd("other.io", "Gadget"))), target)
        assertIs<CrdRoute.Missing>(route)
        assertTrue("Widget" in route.reason && "example.io" in route.reason, route.reason)
    }

    @Test
    fun `a listing error is missing with the error in the reason`() {
        val route = crdRoute(ResourceState.Error("forbidden"), target)
        assertIs<CrdRoute.Missing>(route)
        assertTrue("forbidden" in route.reason, route.reason)
    }
}
