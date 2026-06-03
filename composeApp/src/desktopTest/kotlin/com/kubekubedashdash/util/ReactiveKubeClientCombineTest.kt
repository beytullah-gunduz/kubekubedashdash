package com.kubekubedashdash.util

import com.kubekubedashdash.models.DeploymentInfo
import com.kubekubedashdash.models.NodeInfo
import com.kubekubedashdash.models.PodInfo
import com.kubekubedashdash.models.ResourceState
import com.kubekubedashdash.models.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [combineClusterInfo] — the RBAC-tolerant aggregation behind the
 * cluster overview and the connection-health signal (audit C5). A single list
 * the caller's RBAC forbids must not bounce the whole connection to "Unable to
 * connect".
 */
class ReactiveKubeClientCombineTest {

    private val noNodes = ResourceState.Success(emptyList<NodeInfo>())
    private val noPods = ResourceState.Success(emptyList<PodInfo>())
    private val noDeps = ResourceState.Success(emptyList<DeploymentInfo>())
    private val noSvcs = ResourceState.Success(emptyList<ServiceInfo>())
    private val twoNs = ResourceState.Success(listOf("default", "ns-a"))

    private fun combine(
        nodes: ResourceState<List<NodeInfo>> = noNodes,
        namespaces: ResourceState<List<String>> = twoNs,
        pods: ResourceState<List<PodInfo>> = noPods,
        deployments: ResourceState<List<DeploymentInfo>> = noDeps,
        services: ResourceState<List<ServiceInfo>> = noSvcs,
    ): ResourceState<com.kubekubedashdash.models.ClusterInfo> = combineClusterInfo("ctx", "https://server", "v1.30", nodes, namespaces, pods, deployments, services)

    @Test
    fun `RBAC-forbidden nodes list does not fail the connection`() {
        val result = combine(nodes = ResourceState.Error("nodes is forbidden"))
        assertTrue(result is ResourceState.Success, "must stay Success when only nodes is forbidden")
        assertEquals(0, result.data.nodesCount, "forbidden nodes contributes 0")
        assertEquals(2, result.data.namespacesCount, "the readable lists still count")
    }

    @Test
    fun `Error only when every source fails`() {
        val result = combine(
            nodes = ResourceState.Error("a"),
            namespaces = ResourceState.Error("b"),
            pods = ResourceState.Error("c"),
            deployments = ResourceState.Error("d"),
            services = ResourceState.Error("e"),
        )
        assertTrue(result is ResourceState.Error)
    }

    @Test
    fun `still Loading while a source is doing its first sync`() {
        assertTrue(combine(pods = ResourceState.Loading) is ResourceState.Loading)
    }

    @Test
    fun `a forbidden list does not hold up Loading, but a pending one does`() {
        // nodes forbidden (resolved) but namespaces still loading -> Loading.
        val result = combine(nodes = ResourceState.Error("forbidden"), namespaces = ResourceState.Loading)
        assertTrue(result is ResourceState.Loading)
    }

    @Test
    fun `all sources success builds counts`() {
        val result = combine()
        assertTrue(result is ResourceState.Success)
        assertEquals(0, result.data.nodesCount)
        assertEquals(2, result.data.namespacesCount)
    }
}
