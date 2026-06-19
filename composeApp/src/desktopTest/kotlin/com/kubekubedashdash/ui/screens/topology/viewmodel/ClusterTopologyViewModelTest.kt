package com.kubekubedashdash.ui.screens.topology.viewmodel

import com.kubekubedashdash.models.ResourceGraph
import com.kubekubedashdash.models.ResourceGraphEdge
import com.kubekubedashdash.models.ResourceGraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the pure layout transform in [ClusterTopologyViewModel.groupIntoColumns]
 * and — transitively — the private [ClusterTopologyViewModel.arrangeAroundCenter]
 * spiral. The [ClusterTopologyViewModel.load] path is intentionally not tested:
 * [com.kubekubedashdash.util.ReactiveKubeClient] is a final class with no
 * fake/interface to inject, so load() would need a refactor to unit-test cleanly.
 */
class ClusterTopologyViewModelTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun node(id: String, kind: String) = ResourceGraphNode(id = id, name = id, kind = kind)
    private fun edge(src: String, tgt: String) = ResourceGraphEdge(sourceId = src, targetId = tgt)
    private fun emptyGraph() = ResourceGraph(nodes = emptyList(), edges = emptyList())

    // ── empty graph ────────────────────────────────────────────────────────────

    @Test
    fun `empty graph produces six empty columns`() {
        val result = ClusterTopologyViewModel.groupIntoColumns(emptyGraph())
        assertEquals(6, result.size)
        result.forEach { col -> assertTrue(col.isEmpty(), "expected empty column, got $col") }
    }

    // ── kindColumnOrder routing ────────────────────────────────────────────────

    @Test
    fun `Service node lands in column 2`() {
        val graph = ResourceGraph(
            nodes = listOf(node("svc1", "Service")),
            edges = emptyList(),
        )
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(listOf("svc1"), result[2].map { it.id })
        (result.indices - 2).forEach { idx ->
            assertTrue(result[idx].isEmpty(), "col $idx should be empty")
        }
    }

    @Test
    fun `Ingress node lands in column 1`() {
        val graph = ResourceGraph(
            nodes = listOf(node("ing1", "Ingress")),
            edges = emptyList(),
        )
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(listOf("ing1"), result[1].map { it.id })
    }

    @Test
    fun `unknown kind falls back to column 2 (Service column)`() {
        // kindColumnOrder has no entry for "Widget"; the ?: 2 default applies.
        val graph = ResourceGraph(
            nodes = listOf(node("w1", "Widget")),
            edges = emptyList(),
        )
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(listOf("w1"), result[2].map { it.id })
    }

    @Test
    fun `ConfigMap Secret PVC and ServiceAccount all land in column 5 (mounts column)`() {
        val mounts = listOf(
            node("cm1", "ConfigMap"),
            node("sec1", "Secret"),
            node("pvc1", "PersistentVolumeClaim"),
            node("sa1", "ServiceAccount"),
        )
        val graph = ResourceGraph(nodes = mounts, edges = emptyList())
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        val col5Ids = result[5].map { it.id }.toSet()
        assertEquals(setOf("cm1", "sec1", "pvc1", "sa1"), col5Ids)
        (0 until 5).forEach { idx ->
            assertTrue(result[idx].isEmpty(), "col $idx should be empty")
        }
    }

    // ── single-node column stays untouched ────────────────────────────────────

    @Test
    fun `single-node column is not rearranged`() {
        // arrangeAroundCenter returns early for n <= 1; the column should be
        // returned as-is with its one element.
        val graph = ResourceGraph(
            nodes = listOf(node("svc1", "Service")),
            edges = emptyList(),
        )
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(1, result[2].size)
        assertEquals("svc1", result[2][0].id)
    }

    // ── equal-degree column preserves insertion order ─────────────────────────

    @Test
    fun `equal-degree column preserves insertion order`() {
        // 3 Services, no edges → all have upstreamDegree=0 → min==max → col.toList().
        // Insertion order comes from graph.nodes order.
        val graph = ResourceGraph(
            nodes = listOf(
                node("svc-a", "Service"),
                node("svc-b", "Service"),
                node("svc-c", "Service"),
            ),
            edges = emptyList(),
        )
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(listOf("svc-a", "svc-b", "svc-c"), result[2].map { it.id })
    }

    // ── pyramid: n=5 ──────────────────────────────────────────────────────────

    @Test
    fun `pyramid n=5 - most-connected workload lands dead center`() {
        // 5 WorkloadGroup nodes in col 3, each with a distinct upstream Service edge.
        // Degrees: w0←4 edges, w1←3, w2←2, w3←1, w4←0.
        // After sortDesc: [w0, w1, w2, w3, w4].
        // arrangeAroundCenter(n=5, center=2):
        //   i=0 → pos 2 → w0
        //   i=1 → pos 3 → w1
        //   i=2 → pos 1 → w2
        //   i=3 → pos 4 → w3
        //   i=4 → pos 0 → w4
        // Expected: [w4, w2, w0, w1, w3]
        val services = (0 until 5).map { i -> node("svc$i", "Service") }
        val workloads = (0 until 5).map { i -> node("wg$i", "WorkloadGroup") }
        // wg0 gets 4 service upstream edges, wg1 gets 3, …, wg4 gets 0.
        val edges = buildList {
            for (wIdx in 0 until 5) {
                val serviceCount = 4 - wIdx
                for (sIdx in 0 until serviceCount) {
                    add(edge("svc$sIdx", "wg$wIdx"))
                }
            }
        }
        val graph = ResourceGraph(nodes = services + workloads, edges = edges)
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(
            listOf("wg4", "wg2", "wg0", "wg1", "wg3"),
            result[3].map { it.id },
            "pyramid n=5 ordering",
        )
    }

    // ── pyramid: n=4 ──────────────────────────────────────────────────────────

    @Test
    fun `pyramid n=4 - even-size column biases upward`() {
        // 4 Services, degrees 3/2/1/0 (driven by upstream External edges).
        // sortDesc: [a, b, c, d]
        // arrangeAroundCenter(n=4, center=(4-1)/2=1):
        //   i=0 → pos 1 → a
        //   i=1 → pos 2 → b
        //   i=2 → pos 0 → c
        //   i=3 → pos 3 → d
        // Expected: [c, a, b, d]
        val externals = (0 until 4).map { i -> node("ext$i", "External") }
        val svcs = listOf(
            node("svc-a", "Service"),
            node("svc-b", "Service"),
            node("svc-c", "Service"),
            node("svc-d", "Service"),
        )
        // svc-a gets 3 upstream External edges, svc-b gets 2, svc-c gets 1, svc-d gets 0.
        val edges = buildList {
            add(edge("ext0", "svc-a"))
            add(edge("ext1", "svc-a"))
            add(edge("ext2", "svc-a"))
            add(edge("ext0", "svc-b"))
            add(edge("ext1", "svc-b"))
            add(edge("ext0", "svc-c"))
        }
        val graph = ResourceGraph(nodes = externals + svcs, edges = edges)
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(
            listOf("svc-c", "svc-a", "svc-b", "svc-d"),
            result[2].map { it.id },
            "pyramid n=4 ordering",
        )
    }

    // ── pod ordering follows parent workload position ─────────────────────────

    @Test
    fun `pod column ordered by parent workload position with orphan pods last`() {
        // Two WorkloadGroups (wgA, wgB). After pyramid wgA ends up at index 0,
        // wgB at index 1.  Four pods: p1/p2 owned by wgB, p3 owned by wgA,
        // p4 is orphan (no parent edge).
        // Expected pod column: [p3 (wgA=0), p1 (wgB=1), p2 (wgB=1), p4 (orphan)].
        //
        // To get a deterministic workload order without pyramid rearrangement we
        // need equal degrees (min==max). Give both workloads the same upstream
        // count (0) so the col[3] order equals insertion order: [wgA, wgB].
        val wgA = node("wgA", "WorkloadGroup")
        val wgB = node("wgB", "WorkloadGroup")
        val p1 = node("p1", "Pod")
        val p2 = node("p2", "Pod")
        val p3 = node("p3", "Pod")
        val p4 = node("p4", "Pod") // orphan — no parent workload edge
        val graph = ResourceGraph(
            nodes = listOf(wgA, wgB, p1, p2, p3, p4),
            edges = listOf(
                edge("wgB", "p1"),
                edge("wgB", "p2"),
                edge("wgA", "p3"),
                // p4 intentionally has no workload edge
            ),
        )
        val result = ClusterTopologyViewModel.groupIntoColumns(graph)
        assertEquals(
            listOf("p3", "p1", "p2", "p4"),
            result[4].map { it.id },
            "pod ordering by workload position, orphan last",
        )
    }
}
