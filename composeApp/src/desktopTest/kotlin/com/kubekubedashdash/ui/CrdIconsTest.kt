package com.kubekubedashdash.ui

import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.cloud_circle_filled
import com.kubekubedashdash.resources.database_filled
import com.kubekubedashdash.resources.extension_filled
import com.kubekubedashdash.resources.flowchart_filled
import com.kubekubedashdash.resources.gavel_filled
import com.kubekubedashdash.resources.lan_filled
import com.kubekubedashdash.resources.license_filled
import com.kubekubedashdash.resources.swap_horiz_filled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pins crdGroupIcon's matching rules and the icon table's invariants. */
class CrdIconsTest {

    @Test
    fun `an exact group gets its rule's icon`() {
        assertEquals(Res.drawable.flowchart_filled, crdGroupIcon("argoproj.io"))
        assertEquals(Res.drawable.gavel_filled, crdGroupIcon("kyverno.io"))
    }

    @Test
    fun `a subdomain of a rule's group gets that rule's icon`() {
        assertEquals(Res.drawable.license_filled, crdGroupIcon("acme.cert-manager.io"))
        assertEquals(Res.drawable.lan_filled, crdGroupIcon("extensions.istio.io"))
        assertEquals(Res.drawable.database_filled, crdGroupIcon("postgresql.cnpg.io"))
    }

    @Test
    fun `the longest matching rule wins`() {
        assertEquals(Res.drawable.lan_filled, crdGroupIcon("networking.gke.io"))
        assertEquals(Res.drawable.swap_horiz_filled, crdGroupIcon("autoscaling.gke.io"))
        assertEquals(Res.drawable.cloud_circle_filled, crdGroupIcon("auto.gke.io"))
        assertEquals(Res.drawable.swap_horiz_filled, crdGroupIcon("karpenter.k8s.aws"))
        assertEquals(Res.drawable.cloud_circle_filled, crdGroupIcon("elbv2.k8s.aws"))
    }

    @Test
    fun `every group nested under another rule's group resolves to the longer rule`() {
        val entries = CrdIconRules.flatMap { rule -> rule.groups.map { it to rule } }
        val nested = entries.flatMap { (longer, longerRule) ->
            entries.filter { (shorter, _) -> longer.endsWith(".$shorter") }
                .map { (shorter, _) -> Triple(longer, shorter, longerRule) }
        }
        assertTrue(nested.isNotEmpty(), "the table has no nested groups left, so this test proves nothing")
        nested.forEach { (longer, shorter, longerRule) ->
            assertEquals(longerRule.icon, crdGroupIcon(longer), "$longer is nested under $shorter and must keep its own rule's icon")
        }
    }

    @Test
    fun `a rule only matches on a dot boundary`() {
        assertEquals(Res.drawable.extension_filled, crdGroupIcon("notargoproj.io"))
        assertEquals(Res.drawable.extension_filled, crdGroupIcon("fakecilium.io"))
        assertEquals(Res.drawable.extension_filled, crdGroupIcon("xk8s.aws"))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(Res.drawable.flowchart_filled, crdGroupIcon("ArgoProj.IO"))
    }

    @Test
    fun `an unknown group keeps the puzzle piece`() {
        assertEquals(Res.drawable.extension_filled, crdGroupIcon("example.com"))
        assertEquals(Res.drawable.extension_filled, crdGroupIcon("widgets.example.io"))
        assertEquals(Res.drawable.extension_filled, crdGroupIcon(""))
    }

    @Test
    fun `no group is listed twice`() {
        val groups = CrdIconRules.flatMap { it.groups }
        val duplicates = groups.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "groups listed more than once: $duplicates")
    }

    @Test
    fun `every group is a lowercase dotted domain`() {
        CrdIconRules.flatMap { it.groups }.forEach { group ->
            assertEquals(group.lowercase(), group, "$group is not lowercase")
            assertTrue(!group.startsWith(".") && !group.endsWith("."), "$group starts or ends with a dot")
            assertTrue('.' in group, "$group has no dot")
        }
    }

    @Test
    fun `no rule hands out the fallback icon`() {
        CrdIconRules.forEach { rule ->
            assertNotEquals(Res.drawable.extension_filled, rule.icon, "${rule.groups.first()} would be indistinguishable from an unknown group")
        }
    }

    @Test
    fun `only autoscaling shares an icon with a built-in kind`() {
        val builtIn = NavKinds.map { it.icon }.toSet()
        val shared = CrdIconRules.map { it.icon }.filter { it in builtIn }.toSet()
        assertEquals(setOf(Res.drawable.swap_horiz_filled), shared)
    }
}
