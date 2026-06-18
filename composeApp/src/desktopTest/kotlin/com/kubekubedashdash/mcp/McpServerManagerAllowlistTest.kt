package com.kubekubedashdash.mcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies S1(a): sensitive kinds are absent from `MCP_YAML_ALLOWLIST` so
 * `list_resources` cannot be used to enumerate their metadata, and at least
 * one safe kind is present to confirm the allowlist is populated.
 */
class McpServerManagerAllowlistTest {

    @Test
    fun `secret is not in MCP_YAML_ALLOWLIST`() {
        assertFalse(
            "secret" in McpServerManager.MCP_YAML_ALLOWLIST,
            "'secret' must not be in MCP_YAML_ALLOWLIST",
        )
    }

    @Test
    fun `configmap is not in MCP_YAML_ALLOWLIST`() {
        assertFalse(
            "configmap" in McpServerManager.MCP_YAML_ALLOWLIST,
            "'configmap' must not be in MCP_YAML_ALLOWLIST",
        )
    }

    @Test
    fun `node is not in MCP_YAML_ALLOWLIST`() {
        assertFalse(
            "node" in McpServerManager.MCP_YAML_ALLOWLIST,
            "'node' must not be in MCP_YAML_ALLOWLIST",
        )
    }

    @Test
    fun `pod is in MCP_YAML_ALLOWLIST`() {
        assertTrue(
            "pod" in McpServerManager.MCP_YAML_ALLOWLIST,
            "'pod' must be in MCP_YAML_ALLOWLIST",
        )
    }
}
