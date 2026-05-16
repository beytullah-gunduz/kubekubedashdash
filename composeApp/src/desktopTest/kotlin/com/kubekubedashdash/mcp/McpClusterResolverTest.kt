package com.kubekubedashdash.mcp

import com.kubekubedashdash.mcp.McpClusterResolver.ClusterResolution
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for the pure MCP cluster-resolution contract (audit A3). The
 * `WorkspaceManager` enumeration is a thin adapter; the decision logic —
 * single-cluster back-compat, explicit-context addressing, and the
 * ambiguous / unknown failure modes — lives in [McpClusterResolver.resolve]
 * and is tested here in isolation with `String` standing in for the client.
 */
class McpClusterResolverTest {

    @Test
    fun `no connected clusters resolves to NoneConnected`() {
        assertEquals(
            ClusterResolution.NoneConnected,
            McpClusterResolver.resolve(emptyList<Pair<String, String>>(), null),
        )
        assertEquals(
            ClusterResolution.NoneConnected,
            McpClusterResolver.resolve(emptyList<Pair<String, String>>(), "anything"),
        )
    }

    @Test
    fun `single cluster with no context requested resolves it (back-compat)`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "clientP"), null)
        assertEquals(ClusterResolution.Resolved("prod", "clientP"), r)
    }

    @Test
    fun `blank context is treated as omitted`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "clientP"), "   ")
        assertEquals(ClusterResolution.Resolved("prod", "clientP"), r)
    }

    @Test
    fun `single cluster with a matching context resolves`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "clientP"), "prod")
        assertEquals(ClusterResolution.Resolved("prod", "clientP"), r)
    }

    @Test
    fun `single cluster with a non-matching context is UnknownContext`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "clientP"), "staging")
        assertEquals(ClusterResolution.UnknownContext("staging", listOf("prod")), r)
    }

    @Test
    fun `multiple clusters with no context is Ambiguous listing the choices`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "P", "staging" to "S"), null)
        assertEquals(ClusterResolution.Ambiguous(listOf("prod", "staging")), r)
    }

    @Test
    fun `multiple clusters with a matching context resolves that one`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "P", "staging" to "S"), "staging")
        assertEquals(ClusterResolution.Resolved("staging", "S"), r)
    }

    @Test
    fun `multiple clusters with an unknown context is UnknownContext with choices`() {
        val r = McpClusterResolver.resolve(listOf("prod" to "P", "staging" to "S"), "dev")
        assertEquals(ClusterResolution.UnknownContext("dev", listOf("prod", "staging")), r)
    }

    @Test
    fun `same context open in several tabs is not ambiguous and picks the first`() {
        // Two tabs/windows on the same cluster — one distinct context, so an
        // omitted context is unambiguous and the first match wins.
        val connected = listOf("prod" to "tabA", "prod" to "tabB")
        assertEquals(ClusterResolution.Resolved("prod", "tabA"), McpClusterResolver.resolve(connected, null))
        assertEquals(ClusterResolution.Resolved("prod", "tabA"), McpClusterResolver.resolve(connected, "prod"))
    }
}
