package com.kubekubedashdash.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The per-pod usage and the pods' requests/limits feed the Pods and Nodes
 * tables only. Both are @Transient, so the MCP tools (`list_resources pod`,
 * `get_resource_usage`) keep returning exactly the JSON they did before.
 */
class UsageSerializationTest {

    // The MCP server's own configuration (McpServerManager.json).
    private val json = Json { prettyPrint = true }

    @Test
    fun `PodInfo resources stay out of the MCP JSON`() {
        val pod = PodInfo(
            uid = "u1",
            name = "web-0",
            namespace = "default",
            status = "Running",
            ready = "1/1",
            restarts = 0,
            age = "1m",
            node = "n1",
            ip = "10.244.0.5",
            labels = emptyMap(),
            annotations = emptyMap(),
            containers = emptyList(),
            resources = listOf(ContainerResources("app", memoryLimitBytes = 1L shl 30)),
        )

        val encoded = json.encodeToString(pod)

        assertEquals(json.encodeToString(pod.copy(resources = emptyList())), encoded)
        assertFalse(encoded.contains("\"resources\""), encoded)
    }

    @Test
    fun `ResourceUsageSummary podUsages stay out of the MCP JSON`() {
        val summary = ResourceUsageSummary(
            cpuUsedMillis = 1,
            cpuCapacityMillis = 4000,
            memoryUsedBytes = 2,
            memoryCapacityBytes = 4L shl 30,
            metricsAvailable = true,
            podUsages = mapOf("default/web-0" to PodUsage(1, 2, emptyList())),
        )

        val encoded = json.encodeToString(summary)

        assertEquals(json.encodeToString(summary.copy(podUsages = emptyMap())), encoded)
        assertFalse(encoded.contains("\"podUsages\""), encoded)
    }
}
