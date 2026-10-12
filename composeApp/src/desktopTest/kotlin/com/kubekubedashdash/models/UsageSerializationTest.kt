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

    @Test
    fun `GenericResourceInfo creationTimestamp stays out of the MCP JSON`() {
        val replicaSet = GenericResourceInfo(
            uid = "uid-rs-1",
            name = "redis-cache-5f7a3b1d0",
            namespace = "default",
            status = "1/1",
            age = "1m",
            labels = emptyMap(),
            annotations = emptyMap(),
            creationTimestamp = "2026-10-12T08:00:00Z",
        )

        val encoded = json.encodeToString(replicaSet)

        assertEquals(json.encodeToString(replicaSet.copy(creationTimestamp = "")), encoded)
        assertFalse(encoded.contains("creationTimestamp"), encoded)
    }

    @Test
    fun `GenericResourceInfo run and spark data stay out of the MCP JSON`() {
        val job = GenericResourceInfo(
            uid = "uid-job-1",
            name = "nightly-backup-29123456",
            namespace = "default",
            status = "Complete",
            age = "1m",
            labels = emptyMap(),
            annotations = emptyMap(),
            run = WorkloadRun(startTime = "2026-10-12T08:00:00Z", endTime = "2026-10-12T08:05:30Z", indexed = true),
            spark = SparkRun("spark-etl-nightly-driver", listOf("spark-etl-nightly-1a2b3c4d5e6f7890-exec-1"), "2026-10-12T08:00:00Z", "", "RUNNING"),
        )

        val encoded = json.encodeToString(job)

        assertEquals(json.encodeToString(job.copy(run = null, spark = null)), encoded)
        assertFalse(encoded.contains("run"), encoded)
        assertFalse(encoded.contains("spark"), encoded)
        assertFalse(encoded.contains("indexed"), encoded)
    }
}
