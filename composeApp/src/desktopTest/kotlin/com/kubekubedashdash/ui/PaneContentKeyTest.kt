package com.kubekubedashdash.ui

import com.kubekubedashdash.Screen
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pure coverage of [paneContentKey] (D25): the resource-identity key that gates the row-to-row
 * cut, distinct from the object itself so a live update or a Back/Forward snapshot of the same
 * resource never re-triggers it.
 */
class PaneContentKeyTest {

    // No shared builder exists in TST, so every required field is filled here with obviously
    // fake values (PodWarningsTest's pattern).
    private fun pod(uid: String, name: String = "web-0", namespace: String = "default") = PodInfo(
        uid = uid,
        name = name,
        namespace = namespace,
        status = "Running",
        ready = "1/1",
        restarts = 0,
        age = "1h",
        node = "node-1",
        ip = "10.0.0.1",
        labels = emptyMap(),
        annotations = emptyMap(),
        containers = emptyList(),
    )

    private fun node(uid: String, name: String = "node-1") = NodeInfo(
        uid = uid,
        name = name,
        status = "Ready",
        roles = "worker",
        version = "v1.31.0",
        os = "linux",
        arch = "amd64",
        containerRuntime = "containerd://1.7.0",
        cpu = "4",
        memory = "16Gi",
        pods = "10",
        age = "1h",
        labels = emptyMap(),
        annotations = emptyMap(),
    )

    @Test
    fun `two PodDetail snapshots of the same uid share a key`() {
        val a: Screen = Screen.Detail.PodDetail(pod(uid = "uid-1", name = "web-0"))
        val b: Screen = Screen.Detail.PodDetail(pod(uid = "uid-1", name = "web-0", namespace = "other-ns"))

        assertEquals(a.paneContentKey(), b.paneContentKey())
    }

    @Test
    fun `PodDetails with different uids give different keys`() {
        val a: Screen = Screen.Detail.PodDetail(pod(uid = "uid-1"))
        val b: Screen = Screen.Detail.PodDetail(pod(uid = "uid-2"))

        assertNotEquals(a.paneContentKey(), b.paneContentKey())
    }

    @Test
    fun `a blank pod uid falls back to namespace slash name`() {
        val screen: Screen = Screen.Detail.PodDetail(pod(uid = "", name = "web-0", namespace = "ns-1"))

        assertEquals("Pod/ns-1/web-0", screen.paneContentKey())
    }

    @Test
    fun `a blank node uid falls back to the node name`() {
        val screen: Screen = Screen.Detail.NodeDetail(node(uid = "", name = "node-1"))

        assertEquals("Node/node-1", screen.paneContentKey())
    }

    @Test
    fun `a generic ResourceDetail keys on kind, namespace and name`() {
        val namespaced: Screen = Screen.Detail.ResourceDetail(kind = "ReplicaSet", name = "rs-1", namespace = "ns-1")
        val clusterScoped: Screen = Screen.Detail.ResourceDetail(kind = "ClusterRole", name = "cr", namespace = null)

        assertEquals("ReplicaSet/ns-1/rs-1", namespaced.paneContentKey())
        assertEquals("ClusterRole//cr", clusterScoped.paneContentKey())
    }

    @Test
    fun `a non-detail screen and null both give no key`() {
        val overview: Screen = Screen.Main.ClusterOverview
        val nullScreen: Screen? = null

        assertNull(overview.paneContentKey())
        assertNull(nullScreen.paneContentKey())
    }
}
