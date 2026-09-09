package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.CrdInfo
import com.kubekubedashdash.models.CrdScope
import com.kubekubedashdash.models.ResourceState
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A tab saved against a CRD without a group — possible before F13 required
 * one — restores to the missing-CRD state; its message names the kind
 * without a pair of empty parentheses (review follow-up F16, cosmetic).
 */
class CrdRouteBlankGroupTest {

    private fun target(group: String) = Screen.Main.CustomResource(group = group, version = "v1", kind = "Widget", plural = "widgets", namespaced = true)

    private val other = CrdInfo(
        group = "other.io",
        version = "v1",
        kind = "Gadget",
        plural = "gadgets",
        singular = "gadget",
        shortNames = emptyList(),
        categories = emptyList(),
        scope = CrdScope.NAMESPACED,
        columns = emptyList(),
    )

    @Test
    fun `a target saved without a group is named by its kind alone`() {
        val route = crdRoute(ResourceState.Success(listOf(other)), target(group = ""))

        assertIs<CrdRoute.Missing>(route)
        assertTrue(route.reason.startsWith("Widget is not installed"), route.reason)
    }

    @Test
    fun `a target with a group keeps it in the name`() {
        val route = crdRoute(ResourceState.Success(listOf(other)), target(group = "example.io"))

        assertIs<CrdRoute.Missing>(route)
        assertTrue(route.reason.startsWith("Widget (example.io) is not installed"), route.reason)
    }
}
