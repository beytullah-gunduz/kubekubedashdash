package com.kubekubedashdash.util

import io.fabric8.kubernetes.client.utils.Serialization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The demo's `grafana-dashboards` ConfigMap value, from the pure builder (the cluster is not seeded
 * here). A ConfigMap is capped at 1 MiB, so the value must stay under that and still be large enough
 * to stand in for the "object near the limit" the YAML editor is demonstrated on.
 */
class GrafanaSeedSizeTest {

    private val json = grafanaDashboardsJson()

    @Test
    fun `the dashboard is between 850 000 and 1 000 000 characters and fits a ConfigMap`() {
        assertTrue(json.length in 850_000..1_000_000, "length ${json.length}")
        // The API caps the sum of a ConfigMap's keys and values in bytes, not characters.
        assertEquals(json.length, json.toByteArray(Charsets.UTF_8).size, "ASCII only")
        assertTrue(json.length < 1_048_576)
    }

    @Test
    fun `the dashboard is valid JSON with numbered panels`() {
        val root = Serialization.unmarshal(json, Map::class.java)

        val panels = (root["panels"] as List<*>).map { it as Map<*, *> }
        assertTrue(panels.size > 500, "panels ${panels.size}")
        assertEquals((1..panels.size).map { "Panel $it" }, panels.map { it["title"] }, "titles are Panel 1..N in order")
        fun expr(panel: Map<*, *>) = ((panel["targets"] as List<*>).first() as Map<*, *>)["expr"]
        assertEquals("rate(http_requests_total{job=\"demo-1\"}[5m])", expr(panels.first()))
        assertEquals("rate(http_requests_total{job=\"demo-${panels.size}\"}[5m])", expr(panels.last()))
    }

    @Test
    fun `the dashboard is pretty-printed over tens of thousands of lines`() {
        val lines = json.lines()

        assertTrue(lines.size > 20_000, "lines ${lines.size}")
        assertTrue(lines.maxOf { it.length } < 200, "no line is a wall of text")
        assertEquals("{", lines.first())
        assertTrue(lines.any { it.startsWith("          \"expr\": ") }, "expressions sit on lines of their own")
    }

    @Test
    fun `the builder is deterministic`() {
        assertEquals(json, grafanaDashboardsJson())
    }
}
