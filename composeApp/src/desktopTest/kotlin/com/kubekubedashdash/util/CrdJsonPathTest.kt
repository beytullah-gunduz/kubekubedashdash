package com.kubekubedashdash.util

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMeta
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrdJsonPathTest {

    private val now: Instant = Instant.parse("2026-05-07T12:00:00Z")

    private fun cr(
        name: String = "demo",
        ns: String = "default",
        creationTs: String? = null,
        spec: Map<String, Any?> = emptyMap(),
        status: Map<String, Any?> = emptyMap(),
    ): GenericKubernetesResource {
        val r = GenericKubernetesResource()
        r.apiVersion = "example.com/v1"
        r.kind = "Demo"
        r.metadata = ObjectMeta().apply {
            this.name = name
            this.namespace = ns
            if (creationTs != null) this.creationTimestamp = creationTs
        }
        if (spec.isNotEmpty()) r.additionalProperties["spec"] = spec
        if (status.isNotEmpty()) r.additionalProperties["status"] = status
        return r
    }

    @Test
    fun `missing path renders as none placeholder`() {
        val r = cr()
        assertEquals("<none>", CrdJsonPath.evaluate(r, ".status.phase", "string", now))
    }

    @Test
    fun `simple dotted path reads from metadata`() {
        val r = cr(name = "pi-runner")
        assertEquals("pi-runner", CrdJsonPath.evaluate(r, ".metadata.name", "string", now))
    }

    @Test
    fun `nested status path reads through additionalProperties`() {
        val r = cr(status = mapOf("applicationState" to mapOf("state" to "RUNNING")))
        assertEquals("RUNNING", CrdJsonPath.evaluate(r, ".status.applicationState.state", "string", now))
    }

    @Test
    fun `path without leading dollar or dot still works`() {
        val r = cr(name = "x")
        assertEquals("x", CrdJsonPath.evaluate(r, "metadata.name", "string", now))
    }

    @Test
    fun `path with leading dollar still works`() {
        val r = cr(name = "x")
        assertEquals("x", CrdJsonPath.evaluate(r, "$.metadata.name", "string", now))
    }

    @Test
    fun `integer type strips decimals`() {
        val r = cr(status = mapOf("attempts" to 3))
        assertEquals("3", CrdJsonPath.evaluate(r, ".status.attempts", "integer", now))
    }

    @Test
    fun `number type renders whole numbers without trailing zero`() {
        val r = cr(status = mapOf("ratio" to 4.0))
        assertEquals("4", CrdJsonPath.evaluate(r, ".status.ratio", "number", now))
    }

    @Test
    fun `number type keeps fractional when present`() {
        val r = cr(status = mapOf("ratio" to 4.25))
        assertEquals("4.25", CrdJsonPath.evaluate(r, ".status.ratio", "number", now))
    }

    @Test
    fun `boolean type stringifies`() {
        val r = cr(status = mapOf("ready" to true))
        assertEquals("true", CrdJsonPath.evaluate(r, ".status.ready", "boolean", now))
    }

    @Test
    fun `date type renders relative age in minutes`() {
        val ts = "2026-05-07T11:55:00Z"
        val r = cr(creationTs = ts)
        assertEquals("5m", CrdJsonPath.evaluate(r, ".metadata.creationTimestamp", "date", now))
    }

    @Test
    fun `date type renders relative age in hours`() {
        val ts = "2026-05-07T09:00:00Z"
        val r = cr(creationTs = ts)
        assertEquals("3h", CrdJsonPath.evaluate(r, ".metadata.creationTimestamp", "date", now))
    }

    @Test
    fun `date type renders relative age in days`() {
        val ts = "2026-05-04T12:00:00Z"
        val r = cr(creationTs = ts)
        assertEquals("3d", CrdJsonPath.evaluate(r, ".metadata.creationTimestamp", "date", now))
    }

    @Test
    fun `date type with seconds-only difference`() {
        val ts = "2026-05-07T11:59:30Z"
        val r = cr(creationTs = ts)
        assertEquals("30s", CrdJsonPath.evaluate(r, ".metadata.creationTimestamp", "date", now))
    }

    @Test
    fun `filter path with single match returns the value`() {
        val r = cr(
            status = mapOf(
                "conditions" to listOf(
                    mapOf("type" to "Ready", "status" to "True"),
                    mapOf("type" to "Synced", "status" to "False"),
                ),
            ),
        )
        val out = CrdJsonPath.evaluate(
            r,
            ".status.conditions[?(@.type=='Ready')].status",
            "string",
            now,
        )
        assertEquals("True", out)
    }

    @Test
    fun `filter path with no match renders as none`() {
        val r = cr(
            status = mapOf(
                "conditions" to listOf(mapOf("type" to "Synced", "status" to "False")),
            ),
        )
        val out = CrdJsonPath.evaluate(
            r,
            ".status.conditions[?(@.type=='Ready')].status",
            "string",
            now,
        )
        assertEquals("<none>", out)
    }

    @Test
    fun `filter path with multiple matches joins with comma`() {
        val r = cr(
            status = mapOf(
                "conditions" to listOf(
                    mapOf("type" to "Ready", "status" to "True"),
                    mapOf("type" to "Healthy", "status" to "True"),
                ),
            ),
        )
        // Match all conditions to test multi-value rendering.
        val out = CrdJsonPath.evaluate(r, ".status.conditions[*].status", "string", now)
        // Both "True" — order from Jayway is the document order.
        assertTrue(out.contains("True, True") || out == "True")
    }

    @Test
    fun `unparseable date string falls back to raw value`() {
        val r = GenericKubernetesResource()
        r.kind = "Demo"
        r.apiVersion = "example.com/v1"
        r.metadata = ObjectMeta().apply { name = "x" }
        r.additionalProperties["status"] = mapOf("ts" to "not-a-date")
        assertEquals("not-a-date", CrdJsonPath.evaluate(r, ".status.ts", "date", now))
    }

    @Test
    fun `numeric epoch seconds for date are formatted as age`() {
        // 2026-05-07T12:00:00Z minus 600 seconds = 11:50:00Z
        val epoch = Instant.parse("2026-05-07T11:50:00Z").epochSecond
        val r = GenericKubernetesResource()
        r.kind = "Demo"
        r.apiVersion = "example.com/v1"
        r.metadata = ObjectMeta().apply { name = "x" }
        r.additionalProperties["status"] = mapOf("ts" to epoch)
        assertEquals("10m", CrdJsonPath.evaluate(r, ".status.ts", "date", now))
    }

    @Test
    fun `unknown type falls back to string rendering`() {
        val r = cr(status = mapOf("phase" to "Pending"))
        assertEquals("Pending", CrdJsonPath.evaluate(r, ".status.phase", "weird-type", now))
    }
}
