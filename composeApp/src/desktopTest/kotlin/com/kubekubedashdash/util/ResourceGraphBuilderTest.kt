package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for the resource-graph assembly extracted from
 * `ReactiveKubeClient` (audit A1) — in particular the `findRoot`
 * ownerReference walk and the scale guard, which were the most
 * owner-reference-heavy logic in the old god-class and had zero tests
 * (the one bug the audit found in that file lived in this area).
 */
class ResourceGraphBuilderTest {

    private fun pod(
        name: String,
        uid: String,
        ns: String = "default",
        phase: String = "Running",
        ownerKind: String? = null,
        ownerUid: String? = null,
        ownerName: String? = null,
        labels: Map<String, String> = emptyMap(),
    ): Pod {
        val meta = PodBuilder().withNewMetadata()
            .withName(name).withUid(uid).withNamespace(ns)
        labels.forEach { (k, v) -> meta.addToLabels(k, v) }
        if (ownerKind != null) {
            meta.addNewOwnerReference()
                .withKind(ownerKind).withUid(ownerUid).withName(ownerName ?: "")
                .endOwnerReference()
        }
        return meta.endMetadata()
            .withNewStatus().withPhase(phase).endStatus()
            .build()
    }

    // ── buildDeploymentGraph ────────────────────────────────────────────────

    @Test
    fun `buildDeploymentGraph returns an empty graph when the deployment is null`() {
        val graph = ResourceGraphBuilder.buildDeploymentGraph(
            name = "missing",
            deployment = null,
            allReplicaSets = emptyList(),
            allPods = emptyList(),
            allServices = emptyList(),
            allIngresses = emptyList(),
            allHpas = emptyList(),
        )
        assertEquals(emptyList(), graph.nodes)
        assertEquals(emptyList(), graph.edges)
    }

    @Test
    fun `buildDeploymentGraph wires Deployment to its ReplicaSet to its Pods`() {
        val dep = DeploymentBuilder()
            .withNewMetadata().withName("web").withUid("dep-1").withNamespace("default").endMetadata()
            .withNewSpec().withReplicas(1).withNewSelector().addToMatchLabels("app", "web").endSelector().endSpec()
            .withNewStatus().withReadyReplicas(1).endStatus()
            .build()
        val rs = ReplicaSetBuilder()
            .withNewMetadata()
            .withName("web-rs").withUid("rs-1").withNamespace("default")
            .addNewOwnerReference().withKind("Deployment").withUid("dep-1").withName("web").endOwnerReference()
            .endMetadata()
            .withNewSpec().withReplicas(1).endSpec()
            .withNewStatus().withReadyReplicas(1).endStatus()
            .build()
        val p = pod("web-xyz", "pod-1", ownerKind = "ReplicaSet", ownerUid = "rs-1", ownerName = "web-rs")

        val graph = ResourceGraphBuilder.buildDeploymentGraph(
            name = "web",
            deployment = dep,
            allReplicaSets = listOf(rs),
            allPods = listOf(p),
            allServices = emptyList(),
            allIngresses = emptyList(),
            allHpas = emptyList(),
        )

        val depNode = graph.nodes.single { it.id == "Deployment:dep-1" }
        assertEquals("Available", depNode.status)
        assertNotNull(graph.nodes.singleOrNull { it.id == "ReplicaSet:rs-1" && it.status == "1/1" })
        assertNotNull(graph.nodes.singleOrNull { it.id == "Pod:pod-1" })
        assertTrue(graph.edges.any { it.sourceId == "Deployment:dep-1" && it.targetId == "ReplicaSet:rs-1" })
        assertTrue(graph.edges.any { it.sourceId == "ReplicaSet:rs-1" && it.targetId == "Pod:pod-1" })
    }

    // ── buildClusterTopology: findRoot owner-ref walk ───────────────────────

    @Test
    fun `topology climbs Pod to ReplicaSet to Deployment into one WorkloadGroup`() {
        val dep = DeploymentBuilder()
            .withNewMetadata().withName("web").withUid("dep-1").withNamespace("default").endMetadata()
            .build()
        val rs = ReplicaSetBuilder()
            .withNewMetadata()
            .withName("web-rs").withUid("rs-1").withNamespace("default")
            .addNewOwnerReference().withKind("Deployment").withUid("dep-1").withName("web").endOwnerReference()
            .endMetadata()
            .build()
        val p1 = pod("web-a", "pod-1", ownerKind = "ReplicaSet", ownerUid = "rs-1", ownerName = "web-rs")
        val p2 = pod("web-b", "pod-2", ownerKind = "ReplicaSet", ownerUid = "rs-1", ownerName = "web-rs")

        val graph = ResourceGraphBuilder.buildClusterTopology(
            ingresses = emptyList(),
            services = emptyList(),
            allPods = listOf(p1, p2),
            allRS = listOf(rs),
            allJobs = emptyList(),
            allDeployments = listOf(dep),
            allStatefulSets = emptyList(),
            allDaemonSets = emptyList(),
            allCronJobs = emptyList(),
            crdKinds = emptySet(),
        )

        val group = graph.nodes.single { it.kind == "WorkloadGroup" }
        assertEquals("WorkloadGroup:Deployment:dep-1", group.id)
        assertEquals("web", group.name)
        assertEquals("Deployment", group.subKind)
        // Both pods are children of the single group.
        assertEquals(2, graph.edges.count { it.sourceId == group.id && it.targetId.startsWith("Pod:") })
    }

    @Test
    fun `topology groups an unowned Pod as a standalone WorkloadGroup`() {
        val p = pod("loose", "pod-9", ns = "default")
        val graph = ResourceGraphBuilder.buildClusterTopology(
            ingresses = emptyList(),
            services = emptyList(),
            allPods = listOf(p),
            allRS = emptyList(),
            allJobs = emptyList(),
            allDeployments = emptyList(),
            allStatefulSets = emptyList(),
            allDaemonSets = emptyList(),
            allCronJobs = emptyList(),
            crdKinds = emptySet(),
        )
        val group = graph.nodes.single { it.kind == "WorkloadGroup" }
        assertEquals("WorkloadGroup:Pod:default", group.id)
        assertEquals("standalone", group.name)
    }

    @Test
    fun `topology connects a Service to the WorkloadGroup of its selected Pods`() {
        val dep = DeploymentBuilder()
            .withNewMetadata().withName("web").withUid("dep-1").withNamespace("default").endMetadata()
            .build()
        val rs = ReplicaSetBuilder()
            .withNewMetadata()
            .withName("web-rs").withUid("rs-1").withNamespace("default")
            .addNewOwnerReference().withKind("Deployment").withUid("dep-1").withName("web").endOwnerReference()
            .endMetadata()
            .build()
        val p = pod(
            "web-a",
            "pod-1",
            ownerKind = "ReplicaSet",
            ownerUid = "rs-1",
            ownerName = "web-rs",
            labels = mapOf("app" to "web"),
        )
        val svc = ServiceBuilder()
            .withNewMetadata().withName("web-svc").withUid("svc-1").withNamespace("default").endMetadata()
            .withNewSpec().withType("ClusterIP").addToSelector("app", "web").endSpec()
            .build()

        val graph = ResourceGraphBuilder.buildClusterTopology(
            ingresses = emptyList(),
            services = listOf(svc),
            allPods = listOf(p),
            allRS = listOf(rs),
            allJobs = emptyList(),
            allDeployments = listOf(dep),
            allStatefulSets = emptyList(),
            allDaemonSets = emptyList(),
            allCronJobs = emptyList(),
            crdKinds = emptySet(),
        )

        assertTrue(
            graph.edges.any { it.sourceId == "Service:svc-1" && it.targetId == "WorkloadGroup:Deployment:dep-1" },
            "expected a Service -> WorkloadGroup edge, got ${graph.edges}",
        )
    }

    @Test
    fun `topology scale guard truncates at 200 visible nodes and appends a warning`() {
        // 201 standalone pods, each in its own namespace => 201 distinct
        // WorkloadGroup (visible) nodes, one over the cap.
        val pods = (0..200).map { pod("p$it", "uid-$it", ns = "ns-$it") }

        val graph = ResourceGraphBuilder.buildClusterTopology(
            ingresses = emptyList(),
            services = emptyList(),
            allPods = pods,
            allRS = emptyList(),
            allJobs = emptyList(),
            allDeployments = emptyList(),
            allStatefulSets = emptyList(),
            allDaemonSets = emptyList(),
            allCronJobs = emptyList(),
            crdKinds = emptySet(),
        )

        assertTrue(
            graph.nodes.any { it.id == "__truncated__" && it.kind == "Warning" },
            "expected a __truncated__ warning node",
        )
        val visibleNonWarning = graph.nodes.count { it.kind != "Pod" && it.kind != "Warning" }
        assertEquals(200, visibleNonWarning, "visible nodes must be capped at 200")
    }
}
